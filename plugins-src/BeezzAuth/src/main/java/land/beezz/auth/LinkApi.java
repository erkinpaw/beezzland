package land.beezz.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local HTTP API for BeezzGate (the game server): {@code GET /link?name=<nick>&ip=<ip>} with header
 * {@code X-Secret} → {"linked":true} or {"linked":false,"code":"123456","minutes":10,"bots":[…]}.
 * Listens on 127.0.0.1 only; the key is generated once into api-secret.txt.
 */
final class LinkApi {

    private final HttpServer server;

    LinkApi(AuthService auth, Path dir, int port, Logger log) throws IOException {
        Path keyFile = dir.resolve("api-secret.txt");
        if (Files.notExists(keyFile)) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            Files.writeString(keyFile, HexFormat.of().formatHex(b) + "\n");
        }
        byte[] secret = Files.readString(keyFile).trim().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/link", ex -> {
            try (ex) {
                String given = ex.getRequestHeaders().getFirst("X-Secret");
                if (given == null || !MessageDigest.isEqual(secret, given.trim().getBytes(StandardCharsets.UTF_8))) {
                    reply(ex, 403, "{\"error\":\"bad secret\"}");
                    return;
                }
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                String name = q.getOrDefault("name", "");
                if (!name.matches("[A-Za-z0-9_.]{1,32}")) {
                    reply(ex, 400, "{\"error\":\"bad name\"}");
                    return;
                }
                reply(ex, 200, auth.linkStatus(name, q.getOrDefault("ip", "")).toString());
            } catch (Exception e) {
                log.warn("link API: {}", e.toString());
            }
        });
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange ex, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) m.put(part.substring(0, i), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
