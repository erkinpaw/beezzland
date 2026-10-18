package land.beezz.gate;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Holds every new player on the loading screen (configuration phase — not in the world yet, so it
 * costs almost nothing) until they pass the rules quiz. Passed players are remembered per rules version.
 */
public final class BeezzGate extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private Quiz quiz;
    private File passedFile;
    private YamlConfiguration passed;

    @Override
    public void onEnable() {
        saveResource("rules.yml", false);
        quiz = new Quiz(new File(getDataFolder(), "rules.yml"));
        passedFile = new File(getDataFolder(), "passed.yml");
        passed = YamlConfiguration.loadConfiguration(passedFile);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Rules quiz v" + quiz.version + " is on");
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent e) {
        PlayerConfigurationConnection conn = e.getConnection();
        UUID id = conn.getProfile().getId();
        if (id == null || passed.getInt(id.toString(), 0) >= quiz.version) {
            return; // already passed this version of the rules
        }
        try {
            // this event runs off the main thread: waiting here keeps the player on the loading screen
            quiz.run(conn.getAudience()).get(15, TimeUnit.MINUTES);
            synchronized (this) {
                passed.set(id.toString(), quiz.version);
                passed.save(passedFile);
            }
            getLogger().info(conn.getProfile().getName() + " passed the rules quiz");
        } catch (TimeoutException ex) {
            conn.disconnect(MM.deserialize("<#FACC15>BeezzLand</#FACC15>\n\n<gray>Время на тест по правилам вышло. Заходи снова!"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            getLogger().warning("quiz for " + conn.getProfile().getName() + " failed: " + ex);
            conn.disconnect(MM.deserialize("<gray>Не получилось показать правила, попробуй зайти ещё раз."));
        }
    }

    void forget(UUID id) throws IOException {
        passed.set(id.toString(), null);
        passed.save(passedFile);
    }
}
