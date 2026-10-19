package land.beezz.worlds;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * BeezzLand worlds: the main world from day one, the nether / farm world / End opened by
 * {@code /beezz open}, a separate nether for the farm world, and the honeycomb portal.
 * Loads at STARTUP so the seed protection sees the very first chunks (spawn area) too;
 * everything that needs the main world waits for the first tick.
 */
public final class BeezzWorlds extends JavaPlugin {

    @Override
    public void onEnable() {
        BedrockScrambler scrambler = new BedrockScrambler(); // seed protection for every new chunk
        getServer().getPluginManager().registerEvents(scrambler, this);
        BedrockScrambler.attach(getServer().getWorlds(), scrambler);
        getServer().getScheduler().runTask(this, this::start);
    }

    private void start() {
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
