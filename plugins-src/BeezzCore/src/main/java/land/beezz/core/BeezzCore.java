package land.beezz.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;

/** Small things every BeezzLand player meets: the first-join welcome, /rules, /links and chat tips. */
public final class BeezzCore extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private BukkitTask tipsTask;
    private int nextTip;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        startTips();
    }

    private Component text(String line, Player player) {
        String withSite = line.replace("{site}", getConfig().getString("site", ""));
        return MM.deserialize(withSite, Placeholder.unparsed("player", player == null ? "" : player.getName()))
                .replaceText(b -> b.matchLiteral("{player}").replacement(player == null ? "" : player.getName()));
    }

    private void send(CommandSender to, List<String> lines, Player player) {
        for (String line : lines) {
            to.sendMessage(text(line, player));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPlayedBefore()) {
            return;
        }
        // let the client finish loading the world before the title shows
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            p.showTitle(Title.title(text(getConfig().getString("welcome.title", ""), p),
                    text(getConfig().getString("welcome.subtitle", ""), p),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            send(p, getConfig().getStringList("welcome.first-join"), p);
            String all = getConfig().getString("welcome.broadcast-first-join", "");
            if (!all.isEmpty()) {
                for (Player other : Bukkit.getOnlinePlayers()) {
                    if (other != p) other.sendMessage(text(all, p));
                }
            }
        }, 40L);
    }

    private void startTips() {
        if (tipsTask != null) tipsTask.cancel();
        long minutes = getConfig().getLong("tips.every-minutes", 0);
        List<String> tips = getConfig().getStringList("tips.messages");
        if (minutes <= 0 || tips.isEmpty()) return;
        long ticks = minutes * 60 * 20;
        tipsTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            List<String> list = getConfig().getStringList("tips.messages");
            String line = getConfig().getString("tips.prefix", "") + list.get(nextTip++ % list.size());
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(text(line, p));
        }, ticks, ticks);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player p = sender instanceof Player pl ? pl : null;
        switch (command.getName()) {
            case "rules" -> send(sender, getConfig().getStringList("rules"), p);
            case "links" -> send(sender, getConfig().getStringList("links"), p);
            case "beezzcore" -> {
                reloadConfig();
                startTips();
                sender.sendMessage(MM.deserialize("<#FACC15>BeezzCore:</#FACC15> <gray>config.yml перечитан."));
            }
            default -> { return false; }
        }
        return true;
    }
}
