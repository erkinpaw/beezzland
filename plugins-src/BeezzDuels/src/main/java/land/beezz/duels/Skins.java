package land.beezz.duels;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Reads the skin texture id out of a game profile («textures» property → SKIN url → its last part). */
final class Skins {

    private Skins() {
    }

    static String texture(PlayerProfile profile) {
        for (ProfileProperty pp : profile.getProperties()) {
            if (!pp.getName().equals("textures")) continue;
            try {
                JsonObject o = JsonParser.parseString(new String(Base64.getDecoder().decode(pp.getValue()), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");
                if (o == null || !o.has("SKIN")) return null;
                String url = o.getAsJsonObject("SKIN").get("url").getAsString();
                String id = url.substring(url.lastIndexOf('/') + 1);
                return id.matches("[0-9a-f]{32,80}") ? id : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
