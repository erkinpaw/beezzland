package land.beezz.worlds;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * A lit honeycomb portal: the air rectangle inside a honeycomb frame.
 * {@code alongX} — the portal plane runs along X (fixed Z), otherwise along Z (fixed X).
 */
record HoneyPortal(String world, boolean alongX, int minA, int maxA, int minY, int maxY, int fixed) {

    int x(int a) {
        return alongX ? a : fixed;
    }

    int z(int a) {
        return alongX ? fixed : a;
    }

    boolean contains(Block b) {
        if (!b.getWorld().getName().equals(world) || b.getY() < minY || b.getY() > maxY) {
            return false;
        }
        int a = alongX ? b.getX() : b.getZ();
        int f = alongX ? b.getZ() : b.getX();
        return f == fixed && a >= minA && a <= maxA;
    }

    /** Is this block part of the frame (the ring around the interior)? */
    boolean isFrame(Block b) {
        if (!b.getWorld().getName().equals(world)) {
            return false;
        }
        int a = alongX ? b.getX() : b.getZ();
        int f = alongX ? b.getZ() : b.getX();
        int y = b.getY();
        boolean inBox = f == fixed && a >= minA - 1 && a <= maxA + 1 && y >= minY - 1 && y <= maxY + 1;
        return inBox && !(a >= minA && a <= maxA && y >= minY && y <= maxY);
    }

    double centerA() {
        return (minA + maxA + 1) / 2.0;
    }

    /** Where a player arrives: bottom middle of the interior, facing out of the plane. */
    Location arrival(World w) {
        double a = centerA();
        double f = fixed + 0.5;
        Location l = alongX ? new Location(w, a, minY, f) : new Location(w, f, minY, a);
        l.setYaw(alongX ? 0 : 90);
        return l;
    }

    double distanceSqXZ(double x, double z) {
        double cx = alongX ? centerA() : fixed + 0.5;
        double cz = alongX ? fixed + 0.5 : centerA();
        return (cx - x) * (cx - x) + (cz - z) * (cz - z);
    }
}
