package land.beezz.duels;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

import java.util.BitSet;
import java.util.concurrent.CompletableFuture;

/**
 * The duel world: empty void with a small honeycomb-rimmed arena every 300 blocks along X.
 * Arenas are built on first use; nobody can enter the world except through a duel.
 * <p>
 * The world is the void dimension minecraft:duels from our data pack (Folia can't create worlds
 * at runtime); without the data pack (an older install on Purpur) it is created here as before.
 * Arena blocks and leftovers are handled by the region that owns the arena (Folia).
 */
final class Arenas {

    static final String WORLD = "duels";
    private static final int Y = 100, R = 15, SPACING = 300, WALL = 4, CEILING = 14;

    private final Plugin plugin;
    private final World world;
    private final BitSet busy = new BitSet();
    private final int count;

    Arenas(Plugin plugin, int count) {
        this.plugin = plugin;
        this.count = count;
        World w = Bukkit.getWorld(NamespacedKey.minecraft(WORLD));
        if (w == null) w = Bukkit.getWorld(WORLD);
        if (w == null) {
            w = new WorldCreator(WORLD).generator(new ChunkGenerator() { }) // nothing generated: void
                    .generateStructures(false).createWorld();
        }
        world = w;
        World fw = w;
        // world-wide settings belong to the global region on Folia
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> {
            fw.setDifficulty(Difficulty.NORMAL);
            fw.setSpawnLocation(0, Y + 1, 0);
            fw.setStorm(false);
            fw.setTime(6000);
        });
    }

    World world() {
        return world;
    }

    /** Takes a free arena number; -1 if all are busy. Build it with {@link #build} before use. */
    synchronized int reserve() {
        int i = busy.nextClearBit(0);
        if (i >= count) return -1;
        busy.set(i);
        return i;
    }

    void free(int i) {
        int cx = i * SPACING;
        Bukkit.getRegionScheduler().run(plugin, world, cx >> 4, 0, t -> {
            clear(i);
            synchronized (this) {
                busy.clear(i);
            }
        });
    }

    Location spawn(int arena, boolean first) {
        int cx = arena * SPACING;
        Location l = new Location(world, cx + (first ? -10 : 10) + 0.5, Y + 1, 0.5);
        l.setYaw(first ? -90 : 90); // facing each other
        return l;
    }

    boolean inside(Location l, int arena) {
        int cx = arena * SPACING;
        return l.getWorld() == world && Math.abs(l.getX() - cx) <= R + 1 && Math.abs(l.getZ()) <= R + 1 && l.getY() > Y - 5;
    }

    /** Loads the arena's chunks and builds it (once) in the region that owns it; completes when ready. */
    CompletableFuture<Void> build(int arena) {
        int cx = arena * SPACING;
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture.allOf(world.getChunkAtAsync((cx - R) >> 4, (-R) >> 4), world.getChunkAtAsync((cx + R) >> 4, R >> 4))
                .thenRun(() -> Bukkit.getRegionScheduler().run(plugin, world, cx >> 4, 0, t -> {
                    try {
                        buildNow(cx);
                    } finally {
                        done.complete(null);
                    }
                }));
        return done;
    }

    private void buildNow(int cx) {
        if (world.getBlockAt(cx, Y, 0).getType() == Material.HONEYCOMB_BLOCK) return; // built before
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                boolean edge = Math.abs(x) == R || Math.abs(z) == R;
                boolean center = x == 0 && z == 0;
                set(cx + x, Y, z, edge || center ? Material.HONEYCOMB_BLOCK
                        : (x + z & 1) == 0 ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE);
                if (edge) {
                    for (int y = 1; y <= WALL; y++) set(cx + x, Y + y, z, Material.YELLOW_STAINED_GLASS);
                    for (int y = WALL + 1; y <= CEILING; y++) set(cx + x, Y + y, z, Material.BARRIER);
                } else {
                    set(cx + x, Y + CEILING, z, Material.BARRIER);
                }
            }
        }
        for (int x = -R + 3; x <= R - 3; x += 2 * R - 6) {
            for (int z = -R + 3; z <= R - 3; z += 2 * R - 6) set(cx + x, Y + 1, z, Material.LANTERN);
        }
    }

    /** Arrows, dropped items, potions left after the fight. */
    private void clear(int arena) {
        int cx = arena * SPACING;
        for (Entity e : world.getNearbyEntities(new Location(world, cx, Y + 5, 0), R + 3, 20, R + 3)) {
            if (!(e instanceof Player)) e.remove();
        }
    }

    private void set(int x, int y, int z, Material m) {
        Block b = world.getBlockAt(x, y, z);
        if (b.getType() != m) b.setType(m, false);
    }
}
