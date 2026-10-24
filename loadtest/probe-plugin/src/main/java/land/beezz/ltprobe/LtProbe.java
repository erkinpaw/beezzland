package land.beezz.ltprobe;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;

/**
 * Load test helper. Folia has no /forceload and no /data for blocks, so the farms' chests are read here:
 * {@code ltchest count|clear <world-key> x y z [tag]} loads the chunk, then the region that owns it reads
 * (or empties) the container and appends one line to plugins/LtProbe/chests.log:
 * {@code tag<TAB>item=count,item=count}. Works the same on Paper/Purpur.
 */
public final class LtProbe extends JavaPlugin {

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length < 5) {
            s.sendMessage("ltchest <count|clear> <world-key> <x> <y> <z> [tag]");
            return true;
        }
        World w = Bukkit.getWorld(NamespacedKey.fromString(a[1]));
        if (w == null) {
            s.sendMessage("no world " + a[1]);
            return true;
        }
        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), z = Integer.parseInt(a[4]);
        boolean clear = a[0].equalsIgnoreCase("clear");
        String tag = a.length > 5 ? a[5] : a[1] + " " + x + " " + y + " " + z;
        w.getChunkAtAsync(x >> 4, z >> 4).thenRun(() -> Bukkit.getRegionScheduler().run(this, w, x >> 4, z >> 4, t -> {
            StringBuilder line = new StringBuilder(tag).append('\t');
            if (w.getBlockAt(x, y, z).getState() instanceof Container c) {
                if (clear) {
                    c.getInventory().clear();
                    line.append("cleared");
                } else {
                    TreeMap<String, Integer> got = new TreeMap<>();
                    for (ItemStack i : c.getInventory().getContents()) {
                        if (i != null) got.merge(i.getType().getKey().getKey(), i.getAmount(), Integer::sum);
                    }
                    got.forEach((k, v) -> line.append(k).append('=').append(v).append(','));
                }
            } else {
                line.append("no container");
            }
            write(line.toString());
        }));
        return true;
    }

    private synchronized void write(String line) {
        try {
            getDataFolder().mkdirs();
            Files.writeString(getDataFolder().toPath().resolve("chests.log"), line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            getLogger().warning(e.toString());
        }
    }
}
