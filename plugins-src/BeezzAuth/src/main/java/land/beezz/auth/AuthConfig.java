package land.beezz.auth;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** config.yml, copied from the jar on first start. */
public final class AuthConfig {

    public final Duration session;
    public final Duration confirmTimeout;
    public final Duration linkCodeLife;
    public final Duration lateApprove;
    /** Bedrock players via Floodgate are already authenticated by Xbox Live: let them in without linking */
    public final boolean trustFloodgate;
    public final Section telegram;
    public final Section vk;
    public final Section discord;

    public record Section(boolean enabled, String token, String username, String link, long groupId, String apiBase, String channel) {
    }

    private AuthConfig(Map<String, Object> m) {
        session = Duration.ofHours(num(m, "session-hours", 12));
        confirmTimeout = Duration.ofSeconds(num(m, "confirm-timeout-seconds", 25));
        linkCodeLife = Duration.ofMinutes(num(m, "link-code-minutes", 10));
        lateApprove = Duration.ofMinutes(num(m, "late-approve-minutes", 5));
        trustFloodgate = !Boolean.FALSE.equals(m.get("trust-floodgate"));
        telegram = section(m, "telegram", "https://api.telegram.org");
        vk = section(m, "vk", "https://api.vk.com/method");
        discord = section(m, "discord", "https://discord.com/api/v10");
    }

    public static AuthConfig load(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream in = AuthConfig.class.getResourceAsStream("/config.yml")) {
                Files.copy(in, file);
            }
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> m = new Yaml().load(r);
            return new AuthConfig(m == null ? Map.of() : m);
        }
    }

    @SuppressWarnings("unchecked")
    private static Section section(Map<String, Object> m, String key, String defaultApi) {
        Map<String, Object> s = (Map<String, Object>) m.getOrDefault(key, Map.of());
        return new Section(Boolean.TRUE.equals(s.get("enabled")), str(s, "token"), str(s, "username"),
                str(s, "link"), num(s, "group-id", 0), s.get("api-base") == null ? defaultApi : str(s, "api-base"),
                str(s, "channel"));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static long num(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : def;
    }
}
