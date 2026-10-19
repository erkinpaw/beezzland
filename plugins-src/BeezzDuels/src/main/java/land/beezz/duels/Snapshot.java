package land.beezz.duels;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a player had before the duel. Written to disk before they leave for the arena, so a crash
 * or a disconnect mid-fight still gives them back their survival inventory and position on next join.
 */
final class Snapshot {

    private Snapshot() {
    }

    static void save(Player p, File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("location", p.getLocation());
        y.set("contents", java.util.Arrays.asList(p.getInventory().getContents())); // empty slots are null
        y.set("level", p.getLevel());
        y.set("exp", (double) p.getExp());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("saturation", (double) p.getSaturation());
        y.set("fire", p.getFireTicks());
        y.set("gamemode", p.getGameMode().name());
        y.set("effects", new ArrayList<>(p.getActivePotionEffects()));
        file.getParentFile().mkdirs();
        y.save(file);
    }

    /** Puts the player back exactly as they were and deletes the file. */
    @SuppressWarnings("unchecked")
    static void restore(Player p, File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        p.getInventory().clear();
        List<?> contents = y.getList("contents", List.of());
        p.getInventory().setContents(contents.stream().map(o -> (ItemStack) o).toArray(ItemStack[]::new));
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        for (Object e : y.getList("effects", List.of())) p.addPotionEffect((PotionEffect) e);
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.max(1, Math.min(max, y.getDouble("health", max))));
        p.setFoodLevel(y.getInt("food", 20));
        p.setSaturation((float) y.getDouble("saturation", 5));
        p.setLevel(y.getInt("level"));
        p.setExp((float) y.getDouble("exp"));
        p.setFireTicks(y.getInt("fire"));
        p.setFallDistance(0);
        p.setGameMode(GameMode.valueOf(y.getString("gamemode", "SURVIVAL")));
        Location back = y.getLocation("location");
        if (back != null && back.getWorld() != null) p.teleport(back);
        file.delete();
    }
}
