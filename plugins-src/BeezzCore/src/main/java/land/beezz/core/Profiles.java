package land.beezz.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's name everywhere it is shown through the display name (chat, join messages):
 * rank badge + coloured nick, and on hover a card with rank, «порядочность», hours played and
 * the punishment history from LibertyBans (read straight from its MariaDB tables).
 */
final class Profiles implements Listener {

    /** LibertyBans PunishmentType ordinals */
    private static final int BAN = 0, MUTE = 1, WARN = 2, KICK = 3;
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexCharacter('#').hexColors().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    record History(int warns, int mutes, int bans, double score, boolean known) {
    }

    private final JavaPlugin plugin;
    private final NickStyle nicks;
    private final Map<UUID, History> history = new ConcurrentHashMap<>();
    private Connection db;

    Profiles(JavaPlugin plugin, NickStyle nicks) {
        this.plugin = plugin;
        this.nicks = nicks;
    }

    void start() {
        // refresh everyone once a minute: new punishments, new hours played, rank changes
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::refresh), 20L * 60, 20L * 60);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer()); // badge right away, the history arrives a moment later
        refresh(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        history.remove(e.getPlayer().getUniqueId());
    }

    private void refresh(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            History h = load(id);
            history.put(id, h);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) apply(p);
            });
        });
    }

    /** Set the display name: badge + coloured nick, card on hover, click suggests a private message. */
    void apply(Player p) {
        Component own = nicks.name(p);
        // badge and rank colour from LuckPerms; a donor's own colour replaces only the letters of the nick
        Component name = own == null ? LEGACY.deserialize(Ranks.prefix(p) + p.getName())
                : LEGACY.deserialize(Ranks.prefix(p)).append(own);
        Component shown = name.hoverEvent(HoverEvent.showText(card(p, name)))
                .clickEvent(ClickEvent.suggestCommand("/msg " + p.getName() + " "));
        p.displayName(shown);
    }

    Component card(Player p, Component name) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("card");
        History h = history.getOrDefault(p.getUniqueId(), new History(0, 0, 0, 100, false));
        long hours = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600;
        String since = new SimpleDateFormat("dd.MM.yyyy").format(new Date(p.getFirstPlayed()));
        // a fresh warn (score 90) already costs a star; it comes back as the warn fades (~a month)
        int stars = h.score() >= 95 ? 5 : (int) Math.floor(h.score() / 20.0);
        String starLine = "<#FACC15>" + "★".repeat(stars) + "</#FACC15><dark_gray>" + "★".repeat(5 - stars) + "</dark_gray>";
        String label = h.score() >= 95 ? "<green>образцовый игрок" : h.score() >= 75 ? "<#A3E635>хороший игрок"
                : h.score() >= 40 ? "<#FBBF24>были нарушения" : "<#F87171>под наблюдением";
        String violations = h.known()
                ? "<gray>варны <white>" + h.warns() + "</white> · муты <white>" + h.mutes() + "</white> · баны <white>" + h.bans()
                : "<dark_gray>история нарушений недоступна";
        return Component.text()
                .append(name).append(Component.newline())
                .append(MM.deserialize("<gray>Ранг: <white>" + Ranks.title(p))).append(Component.newline())
                .append(MM.deserialize("<gray>Порядочность: " + starLine + " " + label)).append(Component.newline())
                .append(MM.deserialize("<gray>Наиграно: <white>" + hours + " ч</white> · на сервере с <white>" + since)).append(Component.newline())
                .append(MM.deserialize("<gray>Нарушения: " + violations)).append(Component.newline())
                .append(MM.deserialize(c == null ? "" : c.getString("footer", "")))
                .build();
    }

    // ------------------------------------------------------------------ LibertyBans history

    private History load(UUID id) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("card");
        try {
            Connection conn = connection();
            if (conn == null) return new History(0, 0, 0, 100, false);
            double halfLife = c.getDouble("half-life-days", 30) * 86400;
            double now = System.currentTimeMillis() / 1000.0;
            int warns = 0, mutes = 0, bans = 0;
            double penalty = 0;
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT type, start FROM libertybans_simple_history WHERE victim_type = 0 AND victim_uuid = ?")) {
                st.setBytes(1, bytes(id));
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        int type = rs.getInt(1);
                        double weight = switch (type) {
                            case BAN -> { bans++; yield c.getDouble("weights.ban", 40); }
                            case MUTE -> { mutes++; yield c.getDouble("weights.mute", 20); }
                            case WARN -> { warns++; yield c.getDouble("weights.warn", 10); }
                            case KICK -> c.getDouble("weights.kick", 5);
                            default -> 0;
                        };
                        double age = Math.max(0, now - rs.getLong(2));
                        penalty += weight * Math.pow(0.5, age / halfLife); // old sins fade
                    }
                }
            }
            return new History(warns, mutes, bans, Math.max(0, 100 - penalty), true);
        } catch (SQLException ex) {
            plugin.getLogger().warning("LibertyBans history unavailable: " + ex.getMessage());
            closeQuietly();
            return new History(0, 0, 0, 100, false);
        }
    }

    private synchronized Connection connection() throws SQLException {
        String url = plugin.getConfig().getString("database.url", "");
        if (url.isEmpty()) return null;
        if (db == null || !db.isValid(2)) {
            db = DriverManager.getConnection(url, plugin.getConfig().getString("database.user", ""),
                    plugin.getConfig().getString("database.password", ""));
        }
        return db;
    }

    private synchronized void closeQuietly() {
        try {
            if (db != null) db.close();
        } catch (SQLException ignored) {
        }
        db = null;
    }

    void stop() {
        closeQuietly();
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
