package land.beezz.duels;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Жужики (the site currency: earned by daily login and events, never sold) and duels for them:
 * {@code /дуэль Ник 50 [кит]} → the other player gets a window «Принять / Отказаться» → both stakes are
 * held, both go to an arena with the same kit, the winner takes the pot minus the server's cut.
 * Survival inventories never enter the arena: they are saved to disk and given back after the fight.
 */
public final class BeezzDuels extends JavaPlugin implements Listener, TabExecutor {

    private record Request(Player from, Player to, Kit kit, long stake, long expires) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String P = "<#FACC15>Дуэли</#FACC15> <dark_gray>»</dark_gray> ";
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();

    private Store store;
    private Arenas arenas;
    private File pendingDir, exportFile;
    private long minStake, maxStake, commission, requestMs, fightTicks, dailyBase, dailyStep, dailyMax;
    private int pairPerDay;
    private boolean sameIp;
    private ZoneId zone;
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Duel> duels = new HashMap<>();
    private final Map<Duel, BukkitTask> timeouts = new HashMap<>();
    private final Map<UUID, Long> lastHurt = new ConcurrentHashMap<>();
    private volatile boolean dirty = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var c = getConfig();
        minStake = c.getLong("duel.min-stake", 10);
        maxStake = c.getLong("duel.max-stake", 5000);
        commission = c.getLong("duel.commission-percent", 5);
        requestMs = c.getLong("duel.request-seconds", 60) * 1000;
        fightTicks = c.getLong("duel.max-fight-seconds", 180) * 20;
        pairPerDay = c.getInt("duel.pair-per-day", 5);
        sameIp = c.getBoolean("duel.allow-same-ip", false);
        dailyBase = c.getLong("daily.base", 10);
        dailyStep = c.getLong("daily.step", 2);
        dailyMax = c.getLong("daily.max", 30);
        zone = ZoneId.of(c.getString("daily.zone", "Europe/Moscow"));
        pendingDir = new File(getDataFolder(), "pending");
        String ex = c.getString("export-file", "");
        exportFile = ex.isEmpty() ? new File(getDataFolder(), "duels.json") : new File(ex);
        try {
            store = new Store(new File(getDataFolder(), "zhuzhiki.db"));
        } catch (Exception e) {
            getLogger().severe("Cannot open zhuzhiki.db: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        arenas = new Arenas(c.getInt("duel.arenas", 8));
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("дуэль").setExecutor(this);
        getCommand("дуэль").setTabCompleter(this);
        getCommand("жужики").setExecutor(this);
        getCommand("жужики").setTabCompleter(this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::export, 40L, 600L);
        getLogger().info("Duels ready: " + c.getInt("duel.arenas", 8) + " arenas, commission " + commission + "%");
    }

    @Override
    public void onDisable() {
        // a restart in the middle of a fight: give everyone their things back, return the stakes
        for (Duel d : new ArrayList<>(duels.values())) end(d, null, "сервер перезапускается", true);
        if (store != null) store.close();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equals("жужики")) return zhuzhiki(s, a);
        if (!(s instanceof Player p)) {
            s.sendMessage("Только для игроков.");
            return true;
        }
        if (a.length == 0) {
            say(p, "<gray>Вызов: <white>/дуэль <ник> <ставка> [кит]</white>. Киты: <white>алмазный, незеритовый, лучник</white>.");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "принять", "accept" -> accept(p);
            case "отказ", "отказаться", "decline" -> decline(p);
            case "сдаться", "surrender" -> {
                Duel d = duels.get(p.getUniqueId());
                if (d == null || !d.fighting) say(p, "<gray>Ты сейчас не в бою.");
                else end(d, d.other(p), p.getName() + " сдался", false);
            }
            default -> challenge(p, a);
        }
        return true;
    }

    private void challenge(Player p, String[] a) {
        Player to = Bukkit.getPlayerExact(a[0]);
        if (to == null) {
            say(p, "<#F87171>Игрок не в сети.");
            return;
        }
        if (a.length < 2) {
            say(p, "<gray>Укажи ставку: <white>/дуэль " + to.getName() + " 50");
            return;
        }
        long stake;
        try {
            stake = Long.parseLong(a[1]);
        } catch (NumberFormatException e) {
            say(p, "<#F87171>Ставка — это число жужиков.");
            return;
        }
        if (a.length >= 3) {
            Kit kit = Kit.byId(a[2]);
            if (kit == null) say(p, "<#F87171>Нет такого кита. Есть: алмазный, незеритовый, лучник.");
            else sendRequest(p, to, kit, stake);
            return;
        }
        // no kit given: let them pick in a window
        List<ActionButton> buttons = new ArrayList<>();
        for (Kit k : Kit.values()) {
            buttons.add(ActionButton.builder(MM.deserialize("<#FACC15>" + k.title()))
                    .tooltip(MM.deserialize("<gray>" + k.description())).width(150)
                    .action(DialogAction.customClick((v, aud) -> sendRequest(p, to, k, stake), ONCE)).build());
        }
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Дуэль</bold> <gray>с " + to.getName()))
                        .body(List.of(DialogBody.plainMessage(MM.deserialize("<white>Ставка: <#FACC15>" + stake
                                + " жужиков</#FACC15>\n\n<gray>Выбери кит — у обоих будет одинаковый:"), 300)))
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build())));
    }

    private void sendRequest(Player from, Player to, Kit kit, long stake) {
        String err = checkPair(from, to, stake);
        if (err == null) err = ready(from, "Ты");
        if (err != null) {
            say(from, "<#F87171>" + err);
            return;
        }
        Request old = requests.get(to.getUniqueId());
        if (old != null && old.expires > System.currentTimeMillis() && !old.from.equals(from)) {
            say(from, "<#F87171>У " + to.getName() + " уже есть вызов, подожди минуту.");
            return;
        }
        Request r = new Request(from, to, kit, stake, System.currentTimeMillis() + requestMs);
        requests.put(to.getUniqueId(), r);
        long prize = prize(stake);
        say(from, "<gray>Вызов отправлен <white>" + to.getName() + "</white>: кит <white>" + kit.title()
                + "</white>, ставка <#FACC15>" + stake + "</#FACC15>. Ждём ответа " + requestMs / 1000 + " с.");
        long bal = balance(to);
        Component body = MM.deserialize("<white><bold>" + from.getName() + "</bold> вызывает тебя на дуэль!</white>\n\n"
                + "<gray>Кит:</gray> <white>" + kit.title() + "</white> <dark_gray>— " + kit.description() + "</dark_gray>\n"
                + "<gray>Ставка:</gray> <#FACC15>" + stake + " жужиков</#FACC15> <gray>с каждого</gray>\n"
                + "<gray>Победитель получит:</gray> <#34D399>" + prize + " жужиков</#34D399>\n\n"
                + "<dark_gray>У тебя: " + bal + " жужиков. Свои вещи на арену не берутся — после боя всё вернётся.");
        to.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Вызов на дуэль")).body(List.of(DialogBody.plainMessage(body, 300))).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(MM.deserialize("<#34D399>Принять")).width(120)
                                .action(DialogAction.customClick((v, aud) -> accept(to), ONCE)).build(),
                        ActionButton.builder(MM.deserialize("<#F87171>Отказаться")).width(120)
                                .action(DialogAction.customClick((v, aud) -> decline(to), ONCE)).build()))));
        to.sendMessage(MM.deserialize(P + "<white>" + from.getName() + "</white> <gray>вызывает тебя на дуэль ("
                + kit.title() + ", " + stake + " жужиков). </gray><#34D399><click:run_command:'/дуэль принять'>[Принять]</click></#34D399> "
                + "<#F87171><click:run_command:'/дуэль отказ'>[Отказаться]</click></#F87171>"));
        to.playSound(to.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1.2f);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (requests.get(to.getUniqueId()) == r) {
                requests.remove(to.getUniqueId());
                if (from.isOnline()) say(from, "<gray>" + to.getName() + " не ответил на вызов.");
            }
        }, requestMs / 50);
    }

    private void accept(Player to) {
        Request r = requests.remove(to.getUniqueId());
        if (r == null || r.expires < System.currentTimeMillis() || !r.from.isOnline()) {
            say(to, "<gray>Вызов уже неактуален.");
            return;
        }
        Player from = r.from;
        String err = checkPair(from, to, r.stake);
        if (err == null) err = ready(from, from.getName());
        if (err == null) err = ready(to, "Ты");
        if (err != null) {
            say(to, "<#F87171>" + err);
            say(from, "<#F87171>Дуэль не началась: " + err);
            return;
        }
        // hold both stakes before anything else
        if (!take(from, r.stake, "ставка в дуэли с " + to.getName())) {
            say(to, "<#F87171>У " + from.getName() + " не хватает жужиков.");
            return;
        }
        if (!take(to, r.stake, "ставка в дуэли с " + from.getName())) {
            give(from, r.stake, "возврат ставки");
            say(to, "<#F87171>У тебя не хватает жужиков.");
            return;
        }
        int arena = arenas.take();
        if (arena < 0) {
            give(from, r.stake, "возврат ставки");
            give(to, r.stake, "возврат ставки");
            say(from, "<#F87171>Все арены заняты, попробуй через минуту.");
            say(to, "<#F87171>Все арены заняты, попробуй через минуту.");
            return;
        }
        Duel d = new Duel(from, to, r.kit, r.stake, arena);
        try {
            Snapshot.save(from, pending(from));
            Snapshot.save(to, pending(to));
        } catch (Exception e) {
            getLogger().warning("cannot save inventories for a duel: " + e);
            pending(from).delete();
            pending(to).delete();
            arenas.free(arena);
            give(from, r.stake, "возврат ставки");
            give(to, r.stake, "возврат ставки");
            say(from, "<#F87171>Не получилось начать дуэль, ставки возвращены.");
            say(to, "<#F87171>Не получилось начать дуэль, ставки возвращены.");
            return;
        }
        duels.put(from.getUniqueId(), d);
        duels.put(to.getUniqueId(), d);
        prepare(from, d, arenas.spawn(arena, true));
        prepare(to, d, arenas.spawn(arena, false));
        countdown(d, 3);
        timeouts.put(d, getServer().getScheduler().runTaskLater(this, () -> end(d, null, "время вышло", false), fightTicks + 60));
    }

    private void decline(Player to) {
        Request r = requests.remove(to.getUniqueId());
        if (r == null) {
            say(to, "<gray>Вызовов нет.");
            return;
        }
        say(to, "<gray>Ты отказался от дуэли.");
        if (r.from.isOnline()) say(r.from, "<gray>" + to.getName() + " отказался от дуэли.");
    }

    private void prepare(Player p, Duel d, Location spawn) {
        p.closeInventory();
        p.setGameMode(GameMode.SURVIVAL);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setLevel(0);
        p.setExp(0);
        d.kit.give(p);
        p.teleport(spawn);
    }

    private void countdown(Duel d, int left) {
        if (d.over) return;
        for (Player p : List.of(d.a, d.b)) {
            if (left > 0) {
                p.showTitle(Title.title(MM.deserialize("<#FACC15><bold>" + left), MM.deserialize("<gray>" + d.a.getName() + " против " + d.b.getName()),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            } else {
                p.showTitle(Title.title(MM.deserialize("<#F87171><bold>Бой!"), Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(200))));
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
            }
        }
        if (left > 0) getServer().getScheduler().runTaskLater(this, () -> countdown(d, left - 1), 20L);
        else d.fighting = true;
    }

    /** winner null = draw (stakes back). */
    private void end(Duel d, Player winner, String why, boolean now) {
        if (d.over) return;
        d.over = true;
        BukkitTask t = timeouts.remove(d);
        if (t != null) t.cancel();
        int seconds = (int) ((System.currentTimeMillis() - d.started) / 1000);
        long prize = winner == null ? 0 : prize(d.stake);
        if (winner == null) {
            give(d.a, d.stake, "ничья в дуэли, возврат");
            give(d.b, d.stake, "ничья в дуэли, возврат");
        } else {
            give(winner, prize, "победа в дуэли с " + d.other(winner).getName());
        }
        try {
            store.record(d, winner == null ? null : winner.getUniqueId(), prize, seconds);
        } catch (Exception e) {
            getLogger().warning("cannot record a duel: " + e);
        }
        dirty = true;
        if (winner == null) {
            for (Player p : List.of(d.a, d.b)) say(p, "<gray>Ничья (" + why + "). Ставки вернулись.");
        } else {
            Player loser = d.other(winner);
            winner.showTitle(Title.title(MM.deserialize("<#34D399><bold>Победа!"), MM.deserialize("<#FACC15>+" + prize + " жужиков")));
            winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            if (loser.isOnline()) loser.showTitle(Title.title(MM.deserialize("<#F87171><bold>Поражение"), MM.deserialize("<gray>" + why)));
            Bukkit.broadcast(MM.deserialize(P + "<white><w></white> <gray>победил</gray> <white><l></white> <gray>(" + d.kit.title()
                            + ") и забрал</gray> <#FACC15>" + prize + " жужиков</#FACC15>",
                    Placeholder.unparsed("w", winner.getName()), Placeholder.unparsed("l", loser.getName())));
        }
        Runnable back = () -> {
            for (Player p : List.of(d.a, d.b)) {
                duels.remove(p.getUniqueId());
                if (p.isOnline() && !p.isDead()) restore(p); // offline: on next join; dead: on respawn
            }
            arenas.free(d.arena);
        };
        if (now) back.run();
        else getServer().getScheduler().runTaskLater(this, back, 60L); // 3 s to enjoy the win
    }

    private void restore(Player p) {
        File f = pending(p);
        if (f.exists()) Snapshot.restore(p, f);
    }

    private String checkPair(Player from, Player to, long stake) {
        if (from.equals(to)) return "Нельзя вызвать самого себя.";
        if (stake < minStake || stake > maxStake) return "Ставка — от " + minStake + " до " + maxStake + " жужиков.";
        if (duels.containsKey(to.getUniqueId())) return to.getName() + " уже в бою.";
        if (balance(from) < stake) return from.getName() + ": не хватает жужиков (есть " + balance(from) + ").";
        if (balance(to) < stake) return to.getName() + ": не хватает жужиков для такой ставки.";
        if (!sameIp && from.getAddress() != null && to.getAddress() != null
                && from.getAddress().getAddress().equals(to.getAddress().getAddress())) {
            return "Дуэли между игроками с одного IP запрещены.";
        }
        try {
            if (store.pairToday(from.getUniqueId(), to.getUniqueId()) >= pairPerDay) {
                return "С этим игроком уже было " + pairPerDay + " дуэлей за сутки.";
            }
        } catch (Exception e) {
            return "Ошибка базы, попробуй позже.";
        }
        return null;
    }

    /** Can this player be taken to the arena right now? */
    private String ready(Player p, String who) {
        if (duels.containsKey(p.getUniqueId())) return who + " уже в бою.";
        if (p.getWorld().equals(arenas.world())) return who + " сейчас на арене.";
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return who + " не в режиме выживания.";
        if (System.currentTimeMillis() - lastHurt.getOrDefault(p.getUniqueId(), 0L) < 10_000) return who + " недавно получал урон — подожди 10 секунд.";
        if (p.isInsideVehicle() || p.getFallDistance() > 2 || p.isGliding() || p.isDead()) return who + " должен стоять на земле.";
        return null;
    }

    private long prize(long stake) {
        return stake * 2 * (100 - commission) / 100;
    }

    // ------------------------------------------------------------------ жужики

    private boolean zhuzhiki(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) {
                say(p, "<gray>У тебя</gray> <#FACC15>" + balance(p) + " жужиков</#FACC15><gray>. Зарабатываются за вход каждый день и на ивентах. "
                        + "Топ: <white>/жужики топ</white>.");
            }
            return true;
        }
        if (a[0].equalsIgnoreCase("топ") || a[0].equalsIgnoreCase("top")) {
            try {
                StringBuilder sb = new StringBuilder(P + "<white>Топ по жужикам:");
                int i = 1;
                for (Store.Top t : store.richest(10)) sb.append("\n<gray>").append(i++).append(". <white>").append(t.name()).append("</white> — <#FACC15>").append(t.earned());
                s.sendMessage(MM.deserialize(sb.toString()));
            } catch (Exception e) {
                s.sendMessage("Ошибка базы: " + e.getMessage());
            }
            return true;
        }
        boolean giveCmd = a[0].equalsIgnoreCase("дать") || a[0].equalsIgnoreCase("give");
        boolean takeCmd = a[0].equalsIgnoreCase("забрать") || a[0].equalsIgnoreCase("take");
        if ((giveCmd || takeCmd) && s.hasPermission("beezzduels.admin")) {
            if (a.length < 3) {
                s.sendMessage(MM.deserialize(P + "<gray>/жужики дать <ник> <сумма> [за что]"));
                return true;
            }
            OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
            long n;
            try {
                n = Long.parseLong(a[2]);
            } catch (NumberFormatException e) {
                n = -1;
            }
            if (t == null || n <= 0) {
                s.sendMessage(MM.deserialize(P + "<#F87171>Нет такого игрока или сумма не число."));
                return true;
            }
            String why = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : (giveCmd ? "ивент" : "администрация");
            try {
                boolean ok = store.add(t.getUniqueId(), t.getName(), giveCmd ? n : -n, why + " (" + s.getName() + ")");
                s.sendMessage(MM.deserialize(P + (ok ? "<#34D399>Готово: " + (giveCmd ? "+" : "-") + n + " — " + t.getName()
                        : "<#F87171>У игрока нет столько жужиков.")));
                if (ok && giveCmd && t.getPlayer() != null) {
                    say(t.getPlayer(), "<#34D399>+" + n + " жужиков</#34D399> <gray>— " + why + "</gray>");
                }
                dirty = true;
            } catch (Exception e) {
                s.sendMessage("Ошибка базы: " + e.getMessage());
            }
            return true;
        }
        s.sendMessage(MM.deserialize(P + "<gray>/жужики, /жужики топ"));
        return true;
    }

    private long balance(Player p) {
        try {
            return store.balance(p.getUniqueId());
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean take(Player p, long n, String why) {
        try {
            return store.add(p.getUniqueId(), p.getName(), -n, why);
        } catch (Exception e) {
            getLogger().warning("balance change failed: " + e);
            return false;
        }
    }

    private void give(Player p, long n, String why) {
        try {
            store.add(p.getUniqueId(), p.getName(), n, why);
        } catch (Exception e) {
            getLogger().severe("could not give " + n + " to " + p.getName() + " (" + why + "): " + e);
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTask(this, () -> {
            if (pending(p).exists()) {
                restore(p); // left (or the server stopped) in the middle of a duel
                say(p, "<gray>Дуэль прервалась — вещи и место вернулись.");
            } else if (p.getWorld().equals(arenas.world())) {
                p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
            }
        });
        String today = LocalDate.now(zone).toString(), yesterday = LocalDate.now(zone).minusDays(1).toString();
        UUID id = p.getUniqueId();
        String name = p.getName();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Store.Daily d = store.daily(id, name, today, yesterday, dailyBase, dailyStep, dailyMax);
                if (d == null) return;
                dirty = true;
                getServer().getScheduler().runTaskLater(this, () -> {
                    if (p.isOnline()) say(p, "<#34D399>+" + d.reward() + " жужиков</#34D399> <gray>за вход"
                            + (d.streak() > 1 ? " — " + d.streak() + " дней подряд!" : ". Заходи завтра — будет больше."));
                }, 60L);
            } catch (Exception ex) {
                getLogger().warning("daily reward for " + name + ": " + ex);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        requests.remove(p.getUniqueId());
        Duel d = duels.get(p.getUniqueId());
        if (d != null && !d.over) end(d, d.other(p), p.getName() + " вышел с сервера", false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = duels.get(p.getUniqueId());
        if (d == null) {
            lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
            return;
        }
        if (!d.fighting || d.over) {
            e.setCancelled(true); // countdown and after the end: nobody gets hurt
            return;
        }
        boolean totem = p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
        if (e.getFinalDamage() >= p.getHealth() && !totem) {
            e.setCancelled(true); // no real death: no drops, no death screen
            end(d, d.other(p), "убит", false);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        // only if something got past onDamage (e.g. /kill): never lose the kit on the floor
        Duel d = duels.get(e.getPlayer().getUniqueId());
        if (d == null) return;
        e.getDrops().clear();
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        end(d, d.other(e.getPlayer()), "убит", false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (pending(p).exists()) getServer().getScheduler().runTask(this, () -> restore(p));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Duel d = duels.get(e.getPlayer().getUniqueId());
        if (d != null && !d.fighting && e.hasChangedPosition()) {
            Location to = e.getFrom().clone();
            to.setYaw(e.getTo().getYaw());
            to.setPitch(e.getTo().getPitch());
            e.setTo(to); // frozen during the countdown, may look around
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!duels.containsKey(e.getPlayer().getUniqueId())) return;
        String m = e.getMessage().toLowerCase();
        if (!m.startsWith("/дуэль сдаться") && !m.startsWith("/duel surrender") && !m.startsWith("/duel сдаться")) {
            e.setCancelled(true);
            say(e.getPlayer(), "<gray>Во время дуэли команды не работают. Сдаться: <white>/дуэль сдаться");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (duels.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getWorld().equals(arenas.world()) && !e.getPlayer().isOp()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getWorld().equals(arenas.world()) && !e.getPlayer().isOp()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getLocation().getWorld().equals(arenas.world())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent e) {
        if (e.getWorld().equals(arenas.world()) && e.toWeatherState()) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ site export

    /** duels.json for the site's «Дуэли» tab: top 10, the last fights, kits, rules. */
    private void export() {
        if (!dirty) return;
        dirty = false;
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("updated", System.currentTimeMillis());
            out.put("currency", "жужики");
            out.put("commission_percent", commission);
            out.put("min_stake", minStake);
            out.put("max_stake", maxStake);
            List<Map<String, String>> kits = new ArrayList<>();
            for (Kit k : Kit.values()) kits.add(Map.of("id", k.id(), "title", k.title(), "description", k.description()));
            out.put("kits", kits);
            out.put("top", store.top(10));
            out.put("recent", store.recent(20));
            out.put("richest", store.richest(10));
            Gson g = new GsonBuilder().disableHtmlEscaping().create();
            File tmp = new File(exportFile.getPath() + ".tmp");
            exportFile.getAbsoluteFile().getParentFile().mkdirs();
            Files.writeString(tmp.toPath(), g.toJson(out), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), exportFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            dirty = true;
            getLogger().warning("duels.json export failed: " + e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private File pending(Player p) {
        return new File(pendingDir, p.getUniqueId() + ".yml");
    }

    private static void say(Player p, String mini) {
        p.sendMessage(MM.deserialize(P + mini));
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equals("жужики")) {
            if (a.length == 1) out.addAll(s.hasPermission("beezzduels.admin") ? List.of("топ", "дать", "забрать") : List.of("топ"));
            else if (a.length == 2 && !a[0].equalsIgnoreCase("топ")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 1) {
            out.addAll(List.of("принять", "отказ", "сдаться"));
            Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(s)).forEach(p -> out.add(p.getName()));
        } else if (a.length == 2 && Bukkit.getPlayerExact(a[0]) != null) {
            out.addAll(List.of("10", "50", "100"));
        } else if (a.length == 3) {
            for (Kit k : Kit.values()) out.add(k.id());
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
