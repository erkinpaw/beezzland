package land.beezz.worlds;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Seed protection: the bedrock floor (and nether roof) and the stone→deepslate transition are drawn
 * by vanilla from the world seed, and tools can recover the seed from a few chunks of them. Every new
 * chunk gets these layers re-rolled with a random that has nothing to do with the seed — same look,
 * nothing to crack. Only bedrock / filler blocks are touched: ores, caves, lava stay as generated.
 */
final class BedrockScrambler extends BlockPopulator implements Listener {

    static void attach(Iterable<World> worlds, BedrockScrambler s) {
        for (World w : worlds) s.add(w);
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent e) {
        add(e.getWorld());
    }

    private void add(World w) {
        if (w.getEnvironment() != World.Environment.THE_END && !w.getPopulators().contains(this)) {
            w.getPopulators().add(this);
        }
    }

    @Override
    public void populate(WorldInfo info, Random seeded, int cx, int cz, LimitedRegion r) {
        Random rnd = ThreadLocalRandom.current(); // deliberately NOT the seeded random we are given
        int min = info.getMinHeight();
        boolean nether = info.getEnvironment() == World.Environment.NETHER;
        Material fill = nether ? Material.NETHERRACK : Material.DEEPSLATE;
        for (int x = cx << 4; x < (cx << 4) + 16; x++) {
            for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                // floor: min is always bedrock; min+1..min+4 are bedrock with chance 4/5 .. 1/5, like vanilla
                for (int i = 1; i <= 4; i++) roll(r, x, min + i, z, rnd.nextInt(5) < 5 - i, fill);
                if (nether) {
                    // roof: 127 always bedrock; 126..123 thin out downwards
                    for (int i = 1; i <= 4; i++) roll(r, x, 127 - i, z, rnd.nextInt(5) < 5 - i, fill);
                } else {
                    // y 0..7: the more deepslate the lower, like vanilla's gradient
                    for (int y = 0; y < 8; y++) {
                        Material m = r.getType(x, y, z);
                        if (m == Material.STONE || m == Material.DEEPSLATE) {
                            Material want = rnd.nextInt(8) >= y ? Material.DEEPSLATE : Material.STONE;
                            if (m != want) r.setType(x, y, z, want);
                        }
                    }
                }
            }
        }
    }

    private static void roll(LimitedRegion r, int x, int y, int z, boolean bedrock, Material fill) {
        Material m = r.getType(x, y, z);
        if (bedrock && m == fill) r.setType(x, y, z, Material.BEDROCK);
        else if (!bedrock && m == Material.BEDROCK) r.setType(x, y, z, fill);
    }
}
