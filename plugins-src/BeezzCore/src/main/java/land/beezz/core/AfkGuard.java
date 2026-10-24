package land.beezz.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK limit by rank (LuckPerms meta afk-hours; regular players 2 h, Silver 4, Gold 8, Diamond 12).
 * Only the player's own actions count as activity: turning the head, chatting, commands,
 * clicks, placing/breaking blocks. Being pushed by water or pistons is still AFK.
 */
final class AfkGuard implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> warned = new ConcurrentHashMap<>();

    AfkGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        // Folia: a world-wide timer, then each player is checked by the region that owns them
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> {
            for (Player p : Bukkit.getOnlinePlayers()) p.getScheduler().run(plugin, x -> check(p), null);
        }, 20L * 30, 20L * 30);
    }

    private void active(Player p) {
        lastActive.put(p.getUniqueId(), System.currentTimeMillis());
        warned.remove(p.getUniqueId());
    }

    /** Minutes this player has been AFK (for tests and /beezzcore). */
    long afkMinutes(Player p) {
        return (System.currentTimeMillis() - lastActive.getOrDefault(p.getUniqueId(), System.currentTimeMillis())) / 60000;
    }

    /** Test hook: pretend the player went AFK this many minutes ago. */
    void setAfkFor(Player p, long minutes) {
        lastActive.put(p.getUniqueId(), System.currentTimeMillis() - minutes * 60000);
    }

    private void check(Player p) {
        long now = System.currentTimeMillis();
        double fallback = plugin.getConfig().getDouble("afk.default-hours", 2);
        long warnBefore = plugin.getConfig().getLong("afk.warn-minutes", 5) * 60000;
        {
            if (p.hasPermission("beezz.afk.unlimited")) return;
            double hours = Ranks.afkHours(p, fallback);
            long limit = (long) (hours * 3600_000);
            long idle = now - lastActive.getOrDefault(p.getUniqueId(), now);
            if (idle >= limit) {
                p.kick(MM.deserialize(plugin.getConfig().getString("afk.kick",
                        "<#FACC15>BeezzLand</#FACC15>\n\n<gray>Ты стоял AFK дольше <white>{hours} ч</white>.")
                        .replace("{hours}", trim(hours))));
            } else if (idle >= limit - warnBefore && warned.putIfAbsent(p.getUniqueId(), true) == null) {
                p.sendMessage(MM.deserialize(plugin.getConfig().getString("afk.warn",
                        "<#FBBF24>Ты давно AFK.</#FBBF24> <gray>Пошевелись, иначе через {minutes} мин кикнет.")
                        .replace("{minutes}", String.valueOf(warnBefore / 60000))));
            }
        }
    }

    private static String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { active(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { lastActive.remove(e.getPlayer().getUniqueId()); warned.remove(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        // head rotation is something only a real player does; position alone can come from water/pistons
        if (e.getFrom().getYaw() != e.getTo().getYaw() || e.getFrom().getPitch() != e.getTo().getPitch()) active(e.getPlayer());
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncPlayerChatEvent e) { active(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onCommand(PlayerCommandPreprocessEvent e) { active(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onInteract(PlayerInteractEvent e) { active(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onPlace(BlockPlaceEvent e) { active(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onBreak(BlockBreakEvent e) { active(e.getPlayer()); }
}
