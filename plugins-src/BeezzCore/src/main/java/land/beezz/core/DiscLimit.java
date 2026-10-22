package land.beezz.core;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * Own music discs per season (pv-addon-discs: /disc burn <link>): the limit comes from LuckPerms
 * meta «discs» (Silver 3, Gold 5, Diamond 7). Every burn this season counts; the wipe moves
 * discs.yml away with the old season, so the count starts from zero.
 */
final class DiscLimit implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration used;

    DiscLimit(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "discs.yml");
        this.used = YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String[] args = e.getMessage().substring(1).trim().split("\\s+");
        String cmd = args[0].toLowerCase();
        if (!(cmd.equals("disc") || cmd.endsWith(":disc")) || args.length < 3 || !args[1].equalsIgnoreCase("burn")) {
            return;
        }
        Player p = e.getPlayer();
        if (p.hasPermission("beezzcore.admin")) return;
        int limit = Ranks.discs(p);
        int count = used.getInt(p.getUniqueId().toString());
        if (count >= limit) {
            e.setCancelled(true);
            p.sendMessage(MM.deserialize(limit == 0
                    ? "<gray>Свои пластинки — привилегия доната: <#CBD5E1>Silver</#CBD5E1> 3, <#FBBF24>Gold</#FBBF24> 5, <#67E8F9>Diamond</#67E8F9> 7 за сезон."
                    : "<gray>Лимит своих пластинок на сезон исчерпан: <white>" + count + " из " + limit + "</white>."));
            return;
        }
        used.set(p.getUniqueId().toString(), count + 1);
        save();
        p.sendMessage(MM.deserialize("<gray>Пластинка записывается. Осталось в этом сезоне: <white>" + (limit - count - 1) + " из " + limit));
    }

    void show(CommandSender to) {
        if (!(to instanceof Player p)) return;
        int limit = Ranks.discs(p);
        int count = used.getInt(p.getUniqueId().toString());
        to.sendMessage(MM.deserialize(limit == 0
                ? "<gray>Своих пластинок нет: это привилегия Silver (3), Gold (5) и Diamond (7)."
                : "<gray>Своих пластинок в этом сезоне: <white>" + count + " из " + limit + "</white>. Запись: <#93C5FD>/disc burn <ссылка>"));
    }

    private void save() {
        try {
            used.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Cannot save discs.yml: " + ex.getMessage());
        }
    }
}
