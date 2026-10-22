package land.beezz.core;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import org.bukkit.entity.Player;

/**
 * Rank data from LuckPerms meta, so every limit lives in one place (the group):
 *   prefix         — badge glyph + name colour, e.g. " &#67E8F9"
 *   rank-title     — shown in the player card: «Diamond», «Модер»
 *   afk-hours      — how long the player may stand AFK before a kick
 *   discs          — how many own music discs per season
 * Without LuckPerms everything falls back to the defaults.
 */
final class Ranks {

    private static LuckPerms lp;

    private Ranks() {
    }

    private static CachedMetaData meta(Player p) {
        try {
            if (lp == null) lp = LuckPermsProvider.get();
            return lp.getPlayerAdapter(Player.class).getMetaData(p);
        } catch (IllegalStateException | NoClassDefFoundError e) {
            return null; // LuckPerms not loaded
        }
    }

    static String prefix(Player p) {
        CachedMetaData m = meta(p);
        return m == null || m.getPrefix() == null ? "" : m.getPrefix();
    }

    static String title(Player p) {
        CachedMetaData m = meta(p);
        String t = m == null ? null : m.getMetaValue("rank-title");
        return t == null ? "Игрок" : t;
    }

    static double afkHours(Player p, double fallback) {
        return number(p, "afk-hours", fallback);
    }

    static int discs(Player p) {
        return (int) number(p, "discs", 0);
    }

    private static double number(Player p, String key, double fallback) {
        CachedMetaData m = meta(p);
        String v = m == null ? null : m.getMetaValue(key);
        try {
            return v == null ? fallback : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
