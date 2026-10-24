package land.beezz.worlds;

import org.bukkit.World;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Iron golems for farms.
 * <ul>
 *   <li>No sleeping villagers needed. Vanilla only lets a villager call a golem (panic or gossip) if it slept
 *   within the last in-game day (24000 ticks). Every few minutes each loaded villager's «last slept» is set
 *   to now, so the rest of the vanilla rules (3 scared villagers, a zombie in sight, no golem seen recently)
 *   still decide when a golem comes.</li>
 *   <li>In the farm world, golems called by villagers have no AI: they don't look for targets or walk around,
 *   they only fall and get carried by water — straight into the farm's kill chamber. Golems built by
 *   players (pumpkin on iron blocks) keep their AI everywhere.</li>
 * </ul>
 */
final class Golems implements Listener {

    /** Well under a day, so the memory never runs out between two passes. */
    private static final long EVERY_TICKS = 20L * 60 * 5;

    private final Worlds worlds;

    private Golems(Worlds worlds) {
        this.worlds = worlds;
    }

    static void start(Plugin plugin, Worlds worlds) {
        plugin.getServer().getPluginManager().registerEvents(new Golems(worlds), plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (World world : plugin.getServer().getWorlds()) {
                long now = world.getGameTime();
                for (Villager v : world.getEntitiesByClass(Villager.class)) {
                    v.setMemory(MemoryKey.LAST_SLEPT, now);
                }
            }
        }, 40L, EVERY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof IronGolem golem
                && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE
                && worlds.isFarm(golem.getWorld())) {
            golem.setAware(false);
        }
    }
}
