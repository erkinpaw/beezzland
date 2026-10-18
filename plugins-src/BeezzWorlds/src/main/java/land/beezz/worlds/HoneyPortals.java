package land.beezz.worlds;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Portal between the main world and the farm world: a frame of honeycomb blocks, lit with
 * flint and steel. The inside stays air and is drawn with honey particles instead of the
 * purple nether swirl. Portals link 1:1 by coordinates; if the other side has no portal
 * nearby, one is built there.
 */
final class HoneyPortals implements Listener {

    private static final Material FRAME = Material.HONEYCOMB_BLOCK;
    private static final int MIN_W = 2, MIN_H = 3, MAX_SIDE = 21;
    private static final int LINK_RADIUS = 48;
    private static final Particle.DustOptions HONEY_DUST = new Particle.DustOptions(Color.fromRGB(250, 204, 21), 1.3f);
    private static final Particle.DustOptions AMBER_DUST = new Particle.DustOptions(Color.fromRGB(234, 179, 8), 0.9f);

    private final JavaPlugin plugin;
    private final Worlds worlds;
    private final File file;
    private final List<HoneyPortal> portals = new ArrayList<>();
    /** Players standing in a portal right now; they must step out before it can fire again. */
    private final Set<UUID> inside = new HashSet<>();

    HoneyPortals(JavaPlugin plugin, Worlds worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.file = new File(plugin.getDataFolder(), "portals.yml");
    }

    // ---------------------------------------------------------------- storage

    void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yml.getMapList("portals")) {
            portals.add(new HoneyPortal((String) m.get("world"), (Boolean) m.get("alongX"),
                    (Integer) m.get("minA"), (Integer) m.get("maxA"), (Integer) m.get("minY"),
                    (Integer) m.get("maxY"), (Integer) m.get("fixed")));
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (HoneyPortal p : portals) {
            list.add(Map.of("world", p.world(), "alongX", p.alongX(), "minA", p.minA(), "maxA", p.maxA(),
                    "minY", p.minY(), "maxY", p.maxY(), "fixed", p.fixed()));
        }
        yml.set("portals", list);
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить portals.yml: " + ex.getMessage());
        }
    }

    int count() {
        return portals.size();
    }

    // ---------------------------------------------------------------- lighting

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(PlayerInteractEvent e) {
        Block clicked = e.getClickedBlock();
        ItemStack item = e.getItem();
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null || clicked.getType() != FRAME
                || item == null || item.getType() != Material.FLINT_AND_STEEL) {
            return;
        }
        World w = clicked.getWorld();
        if (!w.equals(worlds.main()) && !worlds.isFarm(w)) {
            return;
        }
        Block start = clicked.getRelative(e.getBlockFace());
        if (!start.getType().isAir()) {
            return;
        }
        HoneyPortal found = findFrame(start, true);
        if (found == null) {
            found = findFrame(start, false);
        }
        if (found == null) {
            return; // not a closed honeycomb frame: let the lighter work as usual
        }
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!worlds.isOpen(Stage.FARM)) {
            Msg.closed(p, Stage.FARM);
            return;
        }
        if (portalAt(start) != null) {
            return;
        }
        portals.add(found);
        save();
        w.playSound(start.getLocation(), Sound.BLOCK_BEEHIVE_WORK, 1.2f, 0.8f);
        w.playSound(start.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 1f, 1f);
        burst(found, w, 60);
        Msg.actionBar(p, Msg.HONEY + "Портал в мир ферм открыт!</#FACC15>");
    }

    /** Flood-fill the air inside a frame in one plane; null if it is not a closed honeycomb rectangle. */
    private static HoneyPortal findFrame(Block start, boolean alongX) {
        Set<Block> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        BlockFace[] dirs = alongX
                ? new BlockFace[]{BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}
                : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.UP, BlockFace.DOWN};
        int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        while (!queue.isEmpty()) {
            Block b = queue.poll();
            int a = alongX ? b.getX() : b.getZ();
            minA = Math.min(minA, a);
            maxA = Math.max(maxA, a);
            minY = Math.min(minY, b.getY());
            maxY = Math.max(maxY, b.getY());
            if (maxA - minA >= MAX_SIDE || maxY - minY >= MAX_SIDE) {
                return null;
            }
            for (BlockFace f : dirs) {
                Block n = b.getRelative(f);
                if (seen.contains(n)) continue;
                if (n.getType().isAir()) {
                    seen.add(n);
                    queue.add(n);
                } else if (n.getType() != FRAME) {
                    return null; // the frame has a gap or a wrong block
                }
            }
        }
        int w = maxA - minA + 1, h = maxY - minY + 1;
        if (w < MIN_W || h < MIN_H || seen.size() != w * h) {
            return null;
        }
        return new HoneyPortal(start.getWorld().getName(), alongX, minA, maxA, minY, maxY,
                alongX ? start.getZ() : start.getX());
    }

    private HoneyPortal portalAt(Block b) {
        for (HoneyPortal p : portals) {
            if (p.contains(b)) return p;
        }
        return null;
    }

    // ---------------------------------------------------------------- breaking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() == FRAME) {
            breakFrame(List.of(e.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        breakFrame(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent e) {
        breakFrame(e.blockList());
    }

    private void breakFrame(List<Block> blocks) {
        boolean changed = portals.removeIf(p -> blocks.stream().anyMatch(b -> b.getType() == FRAME && p.isFrame(b)));
        if (changed) {
            save();
        }
    }

    // ---------------------------------------------------------------- travel

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return;
        }
        Player p = e.getPlayer();
        HoneyPortal portal = portalAt(e.getTo().getBlock());
        if (portal == null) {
            inside.remove(p.getUniqueId());
            return;
        }
        if (!inside.add(p.getUniqueId())) {
            return; // still standing in it after arriving
        }
        if (!worlds.isOpen(Stage.FARM)) {
            Msg.closed(p, Stage.FARM);
            return;
        }
        travel(p, portal);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inside.remove(e.getPlayer().getUniqueId());
    }

    private void travel(Player p, HoneyPortal from) {
        World here = Bukkit.getWorld(from.world());
        World there = worlds.isFarm(here) ? worlds.main() : worlds.farm();
        Location src = from.arrival(here);
        HoneyPortal target = nearest(there, src.getX(), src.getZ());
        if (target == null) {
            target = build(there, src.getBlockX(), src.getBlockZ(), from.alongX());
        }
        Location dest = target.arrival(there);
        here.playSound(src, Sound.BLOCK_HONEY_BLOCK_SLIDE, 1f, 0.7f);
        burst(from, here, 40);
        HoneyPortal arrived = target;
        p.teleportAsync(dest, TeleportCause.PLUGIN).thenAccept(ok -> {
            if (ok) {
                there.playSound(dest, Sound.BLOCK_BEEHIVE_EXIT, 1f, 1f);
                burst(arrived, there, 40);
                Msg.actionBar(p, worlds.isFarm(there)
                        ? Msg.HONEY + "Мир ферм</#FACC15> <gray>— строй фермы и механизмы"
                        : Msg.SKY + "Основной мир</#93C5FD>");
            }
        });
    }

    private HoneyPortal nearest(World w, double x, double z) {
        HoneyPortal best = null;
        double bestD = (double) LINK_RADIUS * LINK_RADIUS;
        for (HoneyPortal p : portals) {
            if (!p.world().equals(w.getName())) continue;
            double d = p.distanceSqXZ(x, z);
            if (d <= bestD) {
                best = p;
                bestD = d;
            }
        }
        return best;
    }

    /** Build a 4x5 honeycomb frame (2x3 inside) on the surface at x/z and register it. */
    private HoneyPortal build(World w, int x, int z, boolean alongX) {
        int y = Math.min(w.getHighestBlockYAt(x, z) + 1, w.getMaxHeight() - 6);
        int fixed = alongX ? z : x;
        int a0 = alongX ? x : z;
        HoneyPortal portal = new HoneyPortal(w.getName(), alongX, a0, a0 + 1, y, y + 2, fixed);
        for (int a = a0 - 1; a <= a0 + 2; a++) {
            for (int yy = y - 1; yy <= y + 3; yy++) {
                Block b = w.getBlockAt(portal.x(a), yy, portal.z(a));
                boolean frame = a < a0 || a > a0 + 1 || yy < y || yy > y + 2;
                b.setType(frame ? FRAME : Material.AIR);
                if (!frame) {
                    // room to step out on both sides, with something to stand on
                    for (int side : new int[]{-1, 1}) {
                        int fx = alongX ? portal.x(a) : x + side;
                        int fz = alongX ? z + side : portal.z(a);
                        w.getBlockAt(fx, yy, fz).setType(Material.AIR);
                        Block floor = w.getBlockAt(fx, y - 1, fz);
                        if (!floor.getType().isSolid()) floor.setType(FRAME);
                    }
                }
            }
        }
        portals.add(portal);
        save();
        return portal;
    }

    // ---------------------------------------------------------------- particles

    /** Called every few ticks: honey shimmer inside every portal that has a player nearby. */
    void tickParticles() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (HoneyPortal p : portals) {
            World w = Bukkit.getWorld(p.world());
            if (w == null) continue;
            Location c = p.arrival(w).add(0, (p.maxY() - p.minY()) / 2.0, 0);
            if (!w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4)
                    || w.getNearbyPlayers(c, 32).isEmpty()) {
                continue;
            }
            int area = (p.maxA() - p.minA() + 1) * (p.maxY() - p.minY() + 1);
            for (int i = 0; i < area * 2; i++) {
                Location at = randomInside(p, w, r);
                w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, r.nextBoolean() ? HONEY_DUST : AMBER_DUST);
            }
            if (r.nextInt(3) == 0) {
                w.spawnParticle(Particle.FALLING_HONEY, randomInside(p, w, r), 1);
            }
            if (r.nextInt(4) == 0) {
                w.spawnParticle(Particle.WAX_ON, randomInside(p, w, r), 1, 0.1, 0.1, 0.1, 0);
            }
        }
    }

    private void burst(HoneyPortal p, World w, int n) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            w.spawnParticle(Particle.WAX_ON, randomInside(p, w, r), 1, 0.2, 0.2, 0.2, 0.5);
        }
    }

    private static Location randomInside(HoneyPortal p, World w, ThreadLocalRandom r) {
        double a = p.minA() + r.nextDouble() * (p.maxA() - p.minA() + 1);
        double y = p.minY() + r.nextDouble() * (p.maxY() - p.minY() + 1);
        double f = p.fixed() + 0.3 + r.nextDouble() * 0.4;
        return p.alongX() ? new Location(w, a, y, f) : new Location(w, f, y, a);
    }
}
