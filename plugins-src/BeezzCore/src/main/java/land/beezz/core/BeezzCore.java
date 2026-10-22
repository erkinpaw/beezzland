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
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;

/**
 * Small things every BeezzLand player meets: the first-join welcome, /rules, /links, chat tips,
 * the rank badge + player card on the nick, the AFK limit and the own-discs limit.
 */
public final class BeezzCore extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private BukkitTask tipsTask;
    private int nextTip;
    private Profiles profiles;
    private AfkGuard afk;
    private DiscLimit discs;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        profiles = new Profiles(this);
        afk = new AfkGuard(this);
        discs = new DiscLimit(this);
        getServer().getPluginManager().registerEvents(profiles, this);
        getServer().getPluginManager().registerEvents(afk, this);
        getServer().getPluginManager().registerEvents(discs, this);
        getServer().getPluginManager().registerEvents(new GlobalChatFix(), this);
        profiles.start();
        afk.start();
        startTips();
    }

    @Override
    public void onDisable() {
        if (profiles != null) profiles.stop();
    }

    /** Join/quit lines with the badge and the hover card instead of vanilla's yellow text. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoinMessage(PlayerJoinEvent e) {
        e.joinMessage(MM.deserialize("<dark_gray>[<#34D399>+</#34D399>]</dark_gray> ").append(e.getPlayer().displayName()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuitMessage(PlayerQuitEvent e) {
        e.quitMessage(MM.deserialize("<dark_gray>[<#F87171>-</#F87171>]</dark_gray> ").append(e.getPlayer().displayName()));
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
            case "discs" -> discs.show(sender);
            case "beezzcore" -> {
                if (args.length == 3 && args[0].equalsIgnoreCase("afk")) { // /beezzcore afk <player> <minutes>: test hook
                    Player t = Bukkit.getPlayerExact(args[1]);
                    if (t == null) { sender.sendMessage("no such player"); return true; }
                    afk.setAfkFor(t, Long.parseLong(args[2]));
                    sender.sendMessage(t.getName() + " is now AFK for " + args[2] + " min");
                    return true;
                }
                reloadConfig();
                startTips();
                Bukkit.getOnlinePlayers().forEach(profiles::apply);
                sender.sendMessage(MM.deserialize("<#FACC15>BeezzCore:</#FACC15> <gray>config.yml перечитан."));
            }
            default -> { return false; }
        }
        return true;
    }
}
