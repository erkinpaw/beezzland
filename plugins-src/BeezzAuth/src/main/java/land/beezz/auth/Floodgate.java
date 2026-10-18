package land.beezz.auth;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Asks Floodgate (if installed) whether a player joined from Bedrock through Geyser.
 * Reflection keeps BeezzAuth working without Floodgate on the proxy. The API is looked up
 * lazily: Floodgate may finish its own startup after ours.
 */
final class Floodgate {

    private static volatile Object api;
    private static volatile Method isFloodgatePlayer;

    private Floodgate() {
    }

    private static boolean resolve() {
        if (api != null) return true;
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = c.getMethod("getInstance").invoke(null);
            if (instance == null) return false;
            isFloodgatePlayer = c.getMethod("isFloodgatePlayer", UUID.class);
            api = instance;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false; // Floodgate not installed
        }
    }

    static boolean present() {
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    static boolean isBedrockPlayer(UUID uuid) {
        if (!resolve()) return false;
        try {
            return (Boolean) isFloodgatePlayer.invoke(api, uuid);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
