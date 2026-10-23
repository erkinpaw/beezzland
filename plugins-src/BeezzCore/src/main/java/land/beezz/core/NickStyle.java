package land.beezz.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Own nick colour, a donor perk: Gold picks one colour, Diamond a gradient of 2–4 colours (/цвет).
 * The choice is kept in nicks.yml; it only shows while the player still has the permission, so an
 * expired rank falls back to the rank colour by itself, and renewing it brings the colour back.
 */
final class NickStyle {

    static final String COLOR = "beezz.nick.color";
    static final String GRADIENT = "beezz.nick.gradient";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int MAX_STOPS = 4;
    /** Darker than this is unreadable on the dark chat background. */
    private static final double MIN_LUMINANCE = 0.18;

    /** Named colours anyone with the perk can type instead of #RRGGBB. */
    static final Map<String, String> SOLID = new LinkedHashMap<>();
    /** Ready-made gradients for Diamond. */
    static final Map<String, String> GRADIENTS = new LinkedHashMap<>();

    static {
        SOLID.put("мёд", "#FACC15");
        SOLID.put("апельсин", "#FB923C");
        SOLID.put("коралл", "#FB7185");
        SOLID.put("роза", "#F9A8D4");
        SOLID.put("лаванда", "#C4B5FD");
        SOLID.put("небо", "#60A5FA");
        SOLID.put("лёд", "#67E8F9");
        SOLID.put("мята", "#6EE7B7");
        SOLID.put("лайм", "#A3E635");
        SOLID.put("снег", "#F1F5F9");
        GRADIENTS.put("пчела", "#FDE047:#F59E0B");
        GRADIENTS.put("закат", "#FB923C:#EC4899");
        GRADIENTS.put("огонь", "#FDE047:#F97316:#EF4444");
        GRADIENTS.put("море", "#67E8F9:#3B82F6");
        GRADIENTS.put("аврора", "#6EE7B7:#22D3EE:#A78BFA");
        GRADIENTS.put("сакура", "#FBCFE8:#F472B6");
        GRADIENTS.put("радуга", "#F87171:#FBBF24:#34D399:#60A5FA");
    }

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration saved;

    NickStyle(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "nicks.yml");
        this.saved = YamlConfiguration.loadConfiguration(file);
    }

    /** Colour stops the player has chosen and may still use; empty — the rank colour applies. */
    List<TextColor> stops(Player p) {
        String value = saved.getString(p.getUniqueId().toString());
        if (value == null) return List.of();
        List<TextColor> stops = parse(value);
        if (stops.size() > 1 && !p.hasPermission(GRADIENT)) return p.hasPermission(COLOR) ? stops.subList(0, 1) : List.of();
        if (!p.hasPermission(COLOR) && !p.hasPermission(GRADIENT)) return List.of();
        return stops;
    }

    /** The nick in the chosen colours, or null when the player keeps the rank colour. */
    Component name(Player p) {
        List<TextColor> stops = stops(p);
        if (stops.isEmpty()) return null;
        return paint(p.getName(), stops);
    }

    /** Legacy &#RRGGBB per letter for TAB's player list (it cannot take components from us). */
    String legacy(Player p) {
        List<TextColor> stops = stops(p);
        if (stops.isEmpty()) return p.getName();
        StringBuilder out = new StringBuilder();
        String name = p.getName();
        for (int i = 0; i < name.length(); i++) {
            out.append("&").append(colorAt(stops, i, name.length()).asHexString()).append(name.charAt(i));
        }
        return out.toString();
    }

    static Component paint(String text, List<TextColor> stops) {
        TextComponent.Builder out = Component.text();
        for (int i = 0; i < text.length(); i++) {
            out.append(Component.text(text.charAt(i), colorAt(stops, i, text.length())));
        }
        return out.build();
    }

    private static TextColor colorAt(List<TextColor> stops, int i, int length) {
        if (stops.size() == 1 || length == 1) return stops.get(0);
        double t = (double) i / (length - 1) * (stops.size() - 1);
        int from = Math.min((int) t, stops.size() - 2);
        return TextColor.lerp((float) (t - from), stops.get(from), stops.get(from + 1));
    }

    private static List<TextColor> parse(String value) {
        List<TextColor> stops = new ArrayList<>();
        for (String part : value.split(":")) {
            TextColor c = TextColor.fromHexString(part);
            if (c != null) stops.add(c);
        }
        return stops;
    }

    // ------------------------------------------------------------------ /цвет

    /** @return true when the nick changed and the profile needs re-applying */
    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Только для игроков.");
            return false;
        }
        boolean solid = p.hasPermission(COLOR), gradient = p.hasPermission(GRADIENT);
        if (!solid && !gradient) {
            say(p, "<gray>Свой цвет ника — привилегия <#FCD34D>Gold</#FCD34D> (один цвет) и <#67E8F9>Diamond</#67E8F9> (градиент).");
            return false;
        }
        if (args.length == 0) {
            menu(p, gradient);
            return false;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("сброс") || first.equals("reset")) {
            saved.set(p.getUniqueId().toString(), null);
            save();
            say(p, "<gray>Ник снова в цвете ранга.");
            return true;
        }
        List<String> hex = new ArrayList<>();
        for (String arg : args) {
            String preset = find(SOLID, arg), grad = find(GRADIENTS, arg);
            if (grad != null) hex.addAll(List.of(grad.split(":")));
            else if (preset != null) hex.add(preset);
            else if (arg.matches("#?[0-9a-fA-F]{6}")) hex.add(arg.startsWith("#") ? arg.toUpperCase(Locale.ROOT) : "#" + arg.toUpperCase(Locale.ROOT));
            else {
                say(p, "<gray>Не понял цвет <white>" + MM.escapeTags(arg) + "</white>. Напиши <#93C5FD>/цвет</#93C5FD>, чтобы увидеть варианты.");
                return false;
            }
        }
        if (hex.size() > MAX_STOPS) {
            say(p, "<gray>В градиенте не больше " + MAX_STOPS + " цветов.");
            return false;
        }
        if (hex.size() > 1 && !gradient) {
            say(p, "<gray>Градиент — привилегия <#67E8F9>Diamond</#67E8F9>. С Gold можно выбрать один цвет.");
            return false;
        }
        for (String h : hex) {
            if (luminance(TextColor.fromHexString(h)) < MIN_LUMINANCE) {
                say(p, "<gray>Цвет <" + h + ">" + h + "</" + h + "> слишком тёмный: на фоне чата ник не прочитать.");
                return false;
            }
        }
        saved.set(p.getUniqueId().toString(), String.join(":", hex));
        save();
        p.sendMessage(MM.deserialize("<gray>Готово, теперь твой ник: ").append(paint(p.getName(), parse(String.join(":", hex)))));
        return true;
    }

    private void menu(Player p, boolean gradient) {
        Component current = name(p);
        say(p, "<#FACC15>Цвет ника</#FACC15> <dark_gray>·</dark_gray> <gray>нажми на вариант или напиши <white>/цвет #RRGGBB</white>");
        p.sendMessage(MM.deserialize("<gray>Сейчас: ").append(current != null ? current : p.displayName()));
        p.sendMessage(row("Цвета: ", SOLID, p));
        if (gradient) {
            p.sendMessage(row("Градиенты: ", GRADIENTS, p));
            say(p, "<dark_gray>Свой градиент: <gray>/цвет #FACC15 #F472B6</gray> (до 4 цветов). Вернуть цвет ранга: <gray>/цвет сброс</gray>");
        } else {
            say(p, "<dark_gray>Градиенты открываются с Diamond. Вернуть цвет ранга: <gray>/цвет сброс</gray>");
        }
    }

    private Component row(String label, Map<String, String> presets, Player p) {
        TextComponent.Builder line = Component.text().append(MM.deserialize("<gray>" + label));
        boolean firstItem = true;
        for (Map.Entry<String, String> e : presets.entrySet()) {
            if (!firstItem) line.append(Component.text("  "));
            firstItem = false;
            line.append(paint(e.getKey(), parse(e.getValue()))
                    .hoverEvent(HoverEvent.showText(paint(p.getName(), parse(e.getValue()))))
                    .clickEvent(ClickEvent.runCommand("/цвет " + e.getKey())));
        }
        return line.build();
    }

    /** Relative luminance 0..1 (sRGB, WCAG formula). */
    private static double luminance(TextColor c) {
        double r = channel(c.red()), g = channel(c.green()), b = channel(c.blue());
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static void say(Player p, String miniMessage) {
        p.sendMessage(MM.deserialize(miniMessage));
    }

    private void save() {
        try {
            saved.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Cannot save nicks.yml: " + ex.getMessage());
        }
    }

    /** Preset by name; «мед» and «мёд» are the same thing. */
    private static String find(Map<String, String> presets, String name) {
        String wanted = name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (Map.Entry<String, String> e : presets.entrySet()) {
            if (e.getKey().replace('ё', 'е').equals(wanted)) return e.getValue();
        }
        return null;
    }
}
