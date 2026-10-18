package land.beezz.worlds;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which worlds exist and which stages are open.
 * <p>
 * Main worlds are the server's own overworld / nether / end. The farm world and its nether
 * are extra worlds created by this plugin; they always exist (so they can be pre-generated),
 * but nobody gets in until the FARM stage is opened.
 */
public final class Worlds {

    public static final String FARM = "farm";
    public static final String FARM_NETHER = "farm_nether";

    private final File stateFile;
    private final Set<Stage> open = EnumSet.noneOf(Stage.class);
    private World farm;
    private World farmNether;

    public Worlds(File dataFolder) {
        this.stateFile = new File(dataFolder, "state.yml");
    }

    public void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(stateFile);
        for (Stage stage : Stage.values()) {
            if (yml.getBoolean("open." + stage.key(), false)) {
                open.add(stage);
            }
        }
        farm = new WorldCreator(FARM).environment(World.Environment.NORMAL).createWorld();
        farmNether = new WorldCreator(FARM_NETHER).environment(World.Environment.NETHER).createWorld();
    }

    public void save() throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        for (Stage stage : Stage.values()) {
            yml.set("open." + stage.key(), open.contains(stage));
        }
        yml.save(stateFile);
    }

    public boolean isOpen(Stage stage) {
        return open.contains(stage);
    }

    public void setOpen(Stage stage, boolean value) throws IOException {
        if (value) {
            open.add(stage);
        } else {
            open.remove(stage);
        }
        save();
    }

    /** The server's main overworld — the first world Bukkit loads. */
    public World main() {
        return Bukkit.getWorlds().getFirst();
    }

    public World farm() {
        return farm;
    }

    public World farmNether() {
        return farmNether;
    }

    public boolean isFarm(World w) {
        return w.equals(farm);
    }

    public boolean isFarmNether(World w) {
        return w.equals(farmNether);
    }

    /** The main nether: any nether world that is not the farm's own. */
    public boolean isMainNether(World w) {
        return w.getEnvironment() == World.Environment.NETHER && !isFarmNether(w);
    }
}
