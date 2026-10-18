package land.beezz.worlds;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/** /beezz open|close|status [nether|farm|end] */
final class BeezzCommand implements TabExecutor {

    private final Worlds worlds;
    private final HoneyPortals portals;

    BeezzCommand(Worlds worlds, HoneyPortals portals) {
        this.worlds = worlds;
        this.portals = portals;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            Msg.chat(sender, "Состояние миров:");
            for (Stage s : Stage.values()) {
                Msg.chat(sender, " " + s.title() + ": " + (worlds.isOpen(s) ? "<green>открыт" : "<red>закрыт")
                        + " <dark_gray>(" + s.key() + ")");
            }
            Msg.chat(sender, " Порталов из сот: " + Msg.HONEY + portals.count());
            return true;
        }
        if (args.length < 2 || !(args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("close"))) {
            return false;
        }
        Stage stage = Stage.parse(args[1]);
        if (stage == null) {
            Msg.chat(sender, "<red>Не знаю мир «" + args[1] + "». Есть: nether, farm, end.");
            return true;
        }
        boolean open = args[0].equalsIgnoreCase("open");
        try {
            worlds.setOpen(stage, open);
        } catch (IOException ex) {
            Msg.chat(sender, "<red>Не удалось сохранить состояние: " + ex.getMessage());
            return true;
        }
        if (open) {
            announce(stage);
        } else {
            Msg.chat(sender, stage.title() + " закрыт.");
        }
        return true;
    }

    private static void announce(Stage stage) {
        String how = switch (stage) {
            case NETHER -> "Порталы из обсидиана теперь работают.";
            case FARM -> "Построй раму из " + Msg.HONEY + "блоков пчелиных сот</#FACC15> и подожги её зажигалкой.";
            case END -> "Найди крепость и активируй портал в Энд.";
        };
        Title title = Title.title(
                Msg.text(Msg.HONEY + "<bold>" + stage.title() + " открыт!"),
                Msg.text("<gray>" + how),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(1)));
        Bukkit.getServer().showTitle(title);
        Bukkit.getServer().playSound(net.kyori.adventure.sound.Sound.sound(
                Sound.UI_TOAST_CHALLENGE_COMPLETE, net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f));
        Msg.chat(Bukkit.getServer(), Msg.HONEY + stage.title() + " открыт!</#FACC15> " + how);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("open", "close", "status"), args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("status")) {
            return filter(Arrays.stream(Stage.values()).map(Stage::key).toList(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.startsWith(prefix.toLowerCase())).toList();
    }
}
