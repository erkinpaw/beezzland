package land.beezz.worlds;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * BeezzLand worlds: the main world from day one, the nether / farm world / End opened by
 * {@code /beezz open}, a separate nether for the farm world, and the honeycomb portal.
 */
public final class BeezzWorlds extends JavaPlugin {

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        Worlds worlds = new Worlds(getDataFolder());
        worlds.load();

        HoneyPortals honey = new HoneyPortals(this, worlds);
        honey.load();

        getServer().getPluginManager().registerEvents(new VanillaPortals(worlds), this);
        getServer().getPluginManager().registerEvents(honey, this);
        getServer().getScheduler().runTaskTimer(this, honey::tickParticles, 20L, 4L);

        BeezzCommand command = new BeezzCommand(worlds, honey);
        getCommand("beezz").setExecutor(command);
        getCommand("beezz").setTabCompleter(command);

        StringBuilder open = new StringBuilder();
        for (Stage s : Stage.values()) {
            open.append(s.key()).append(worlds.isOpen(s) ? "=open " : "=closed ");
        }
        getLogger().info("Worlds ready: farm, farm_nether; " + open.toString().trim()
                + "; honey portals: " + honey.count());
    }
}
