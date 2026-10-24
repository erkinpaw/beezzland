package land.beezz.worlds;

import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.plugin.Plugin;

/**
 * Iron golems without sleeping villagers. Vanilla only lets a villager call a golem (panic or gossip)
 * if it slept within the last in-game day (24000 ticks). On BeezzLand iron farms work without beds:
 * every few minutes each loaded villager's «last slept» is set to now, so the rest of the vanilla rules
 * (3 scared villagers, a zombie in sight, no golem seen recently) still decide when a golem comes.
 */
final class Golems {

    /** Well under a day, so the memory never runs out between two passes. */
    private static final long EVERY_TICKS = 20L * 60 * 5;

    private Golems() {
    }

    static void start(Plugin plugin) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (World world : plugin.getServer().getWorlds()) {
                long now = world.getGameTime();
                for (Villager v : world.getEntitiesByClass(Villager.class)) {
                    v.setMemory(MemoryKey.LAST_SLEPT, now);
                }
            }
        }, 40L, EVERY_TICKS);
    }
}
