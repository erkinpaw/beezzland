package land.beezz.duels;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /donate}: donor ranks for жужики. Rubles are paid on the site (the same ranks and the unban),
 * here the button only opens it. A bought rank is a LuckPerms temporary parent: buying again adds days.
 */
final class Donate {

    record Rank(String id, String title, String color, long price, List<String> perks) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String P = "<#FACC15>Донат</#FACC15> <dark_gray>»</dark_gray> ";
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();

    private final Plugin plugin;
    private final Store store;
    private final Runnable changed;
    private final List<Rank> ranks = new ArrayList<>();
    private final int days;
    private final String site;

    Donate(Plugin plugin, Store store, ConfigurationSection c, Runnable changed) {
        this.plugin = plugin;
        this.store = store;
        this.changed = changed;
        days = c == null ? 90 : c.getInt("days", 90);
        site = c == null ? "" : c.getString("site", "");
        ConfigurationSection rs = c == null ? null : c.getConfigurationSection("ranks");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                ranks.add(new Rank(id, r.getString("title", id), r.getString("color", "#FFFFFF"),
                        r.getLong("price"), r.getStringList("perks")));
            }
        }
    }

    void open(Player p) {
        long balance = balance(p);
        List<ActionButton> buttons = new ArrayList<>();
        for (Rank r : ranks) {
            buttons.add(ActionButton.builder(MM.deserialize("<" + r.color() + ">" + r.title() + "</" + r.color() + "> <gray>— "
                            + r.price() + " жужиков"))
                    .tooltip(MM.deserialize(String.join("\n", r.perks())))
                    .width(220)
                    .action(DialogAction.customClick((v, aud) -> later(() -> confirm(p, r)), ONCE)).build());
        }
        if (!site.isEmpty()) {
            buttons.add(ActionButton.builder(MM.deserialize("<#93C5FD>Купить за рубли на сайте"))
                    .width(220).action(DialogAction.staticAction(ClickEvent.openUrl(site))).build());
        }
        String body = "<gray>У тебя</gray> <#FACC15>" + balance + " жужиков</#FACC15><gray>.</gray>\n"
                + "<gray>Ранг даётся на " + days + " дней. Купишь ещё раз — дни добавятся.</gray>\n"
                + "<dark_gray>Наведи на ранг, чтобы увидеть, что он даёт.</dark_gray>";
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Донат")).body(List.of(DialogBody.plainMessage(MM.deserialize(body), 300))).build())
                .type(DialogType.multiAction(buttons, ActionButton.builder(Component.text("Закрыть")).width(220).build(), 1))));
    }

    private void confirm(Player p, Rank r) {
        long balance = balance(p);
        if (balance < r.price()) {
            say(p, "<#F87171>Не хватает жужиков:</#F87171> <gray>нужно " + r.price() + ", у тебя " + balance
                    + ". Их дают за вход каждый день и на ивентах.");
            return;
        }
        String body = "<gray>Купить</gray> <" + r.color() + ">" + r.title() + "</" + r.color() + "> <gray>на " + days + " дней за</gray> <#FACC15>"
                + r.price() + " жужиков</#FACC15><gray>?\nОстанется " + (balance - r.price()) + ".</gray>";
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Покупка")).body(List.of(DialogBody.plainMessage(MM.deserialize(body), 300))).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(MM.deserialize("<#34D399>Купить")).width(120)
                                .action(DialogAction.customClick((v, aud) -> later(() -> buy(p, r)), ONCE)).build(),
                        ActionButton.builder(MM.deserialize("<#F87171>Отмена")).width(120)
                                .action(DialogAction.customClick((v, aud) -> later(() -> open(p)), ONCE)).build()))));
    }

    private void buy(Player p, Rank r) {
        if (!p.isOnline()) return;
        String reason = "ранг " + r.title() + " на " + days + " дней";
        try {
            if (!store.add(p.getUniqueId(), p.getName(), -r.price(), reason)) {
                say(p, "<#F87171>Не хватает жужиков.");
                return;
            }
        } catch (Exception e) {
            say(p, "<#F87171>Ошибка базы, жужики не списаны. Попробуй позже.");
            return;
        }
        // accumulate: a second purchase of the same rank adds days instead of replacing the timer
        String cmd = "lp user " + p.getName() + " parent addtemp " + r.id() + " " + days + "d accumulate";
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)) {
            try {
                store.add(p.getUniqueId(), p.getName(), r.price(), "возврат: " + reason);
            } catch (Exception ignored) {
            }
            say(p, "<#F87171>Не получилось выдать ранг, жужики вернули. Напиши администрации.");
            plugin.getLogger().warning("donate: '" + cmd + "' failed");
            return;
        }
        changed.run();
        plugin.getLogger().info("donate: " + p.getName() + " bought " + r.id() + " for " + r.price() + " жужиков");
        say(p, "<#34D399>Спасибо!</#34D399> <gray>Теперь у тебя</gray> <" + r.color() + ">" + r.title() + "</" + r.color() + "><gray> на " + days + " дней.");
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
    }

    private long balance(Player p) {
        try {
            return store.balance(p.getUniqueId());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Dialog clicks may arrive off the main thread; commands and sounds need it. */
    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private static void say(Player p, String mini) {
        p.sendMessage(MM.deserialize(P + mini));
    }
}
