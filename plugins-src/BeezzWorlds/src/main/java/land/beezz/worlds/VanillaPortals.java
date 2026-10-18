package land.beezz.worlds;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.world.PortalCreateEvent;

/**
 * Obsidian and End portals: closed stages stay shut, and the farm world gets its own nether.
 * <pre>
 *   main overworld ⇄ main nether      (vanilla)
 *   farm           ⇄ farm_nether      (rerouted here)
 *   farm_nether    ✕ main nether      (no link at all)
 * </pre>
 */
final class VanillaPortals implements Listener {

    private final Worlds worlds;

    VanillaPortals(Worlds worlds) {
        this.worlds = worlds;
    }

    /** Lighting an obsidian frame in the main overworld while the nether is closed does nothing. */
    @EventHandler(ignoreCancelled = true)
    public void onLight(PortalCreateEvent e) {
        if (e.getReason() != PortalCreateEvent.CreateReason.FIRE) {
            return;
        }
        World w = e.getWorld();
        boolean mainSide = w.equals(worlds.main()) || worlds.isMainNether(w);
        if (mainSide && !worlds.isOpen(Stage.NETHER)) {
            e.setCancelled(true);
            if (e.getEntity() instanceof Player p) {
                Msg.closed(p, Stage.NETHER);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        Location to = route(e.getPlayer(), e.getFrom(), e.getTo(), e.getCause() == TeleportCause.END_PORTAL);
        if (to == null) {
            e.setCancelled(true);
        } else if (to != e.getTo()) {
            e.setTo(to);
            e.setCanCreatePortal(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        boolean end = e.getTo() != null && e.getTo().getWorld() != null
                && e.getTo().getWorld().getEnvironment() == World.Environment.THE_END;
        Location to = route(null, e.getFrom(), e.getTo(), end);
        if (to == null) {
            e.setCancelled(true);
        } else if (to != e.getTo()) {
            e.setTo(to);
        }
    }

    /** @return where to go, the unchanged target, or null to block the trip */
    private Location route(Entity who, Location from, Location to, boolean endPortal) {
        World w = from.getWorld();
        if (endPortal) {
            if (worlds.isFarm(w)) {
                if (who != null) Msg.actionBar(who, "<gray>В Энд можно попасть только из основного мира.");
                return null;
            }
            boolean goingToEnd = to == null || to.getWorld() == null
                    || to.getWorld().getEnvironment() == World.Environment.THE_END;
            if (goingToEnd && !worlds.isOpen(Stage.END)) {
                if (who != null) Msg.closed(who, Stage.END);
                return null;
            }
            return to;
        }
        if (worlds.isFarm(w)) {
            return scaled(from, worlds.farmNether(), 1 / 8.0);
        }
        if (worlds.isFarmNether(w)) {
            return scaled(from, worlds.farm(), 8.0);
        }
        if (w.equals(worlds.main()) && !worlds.isOpen(Stage.NETHER)) {
            if (who != null) Msg.closed(who, Stage.NETHER);
            return null;
        }
        return to;
    }

    private static Location scaled(Location from, World target, double factor) {
        double y = Math.clamp(from.getY(), target.getMinHeight() + 8, target.getMaxHeight() - 8);
        if (target.getEnvironment() == World.Environment.NETHER) {
            y = Math.min(y, 120); // stay under the nether roof
        }
        return new Location(target, from.getX() * factor, y, from.getZ() * factor, from.getYaw(), from.getPitch());
    }
}
