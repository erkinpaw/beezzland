package land.beezz.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * %beezzcore_name% — the nick in the donor's own colours as &#RRGGBB per letter, or just the name.
 * TAB puts it after the rank badge in the player list (customtabname in TAB/groups.yml).
 */
final class NamePlaceholder extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final NickStyle nicks;

    NamePlaceholder(JavaPlugin plugin, NickStyle nicks) {
        this.plugin = plugin;
        this.nicks = nicks;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "beezzcore";
    }

    @Override
    public @NotNull String getAuthor() {
        return "BeezzLand";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (!params.equals("name") || player == null) return null;
        return player instanceof Player online ? nicks.legacy(online) : player.getName();
    }
}
