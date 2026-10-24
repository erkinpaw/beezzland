package land.beezz.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
import java.util.concurrent.TimeUnit;

/**
 * Local HTTP API for BeezzGate (the game server): {@code GET /link?name=<nick>&ip=<ip>} with header
 * {@code X-Secret} → {"linked":true} or {"linked":false,"code":"123456","minutes":10,"bots":[…]}.
 * The site uses {@code /site/*} (login, linked bots) and {@code /shop/grant|unban} (delivery of a paid order).
 * Listens on 127.0.0.1 only; the key is generated once into api-secret.txt.
 */
final class LinkApi {

    private final HttpServer server;

    LinkApi(AuthService auth, ProxyServer proxy, Path dir, int port, Logger log) throws IOException {
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
        // the site: login with «Это я» in a bot, and which messengers a nick has
        server.createContext("/site/", ex -> {
            try (ex) {
                String given = ex.getRequestHeaders().getFirst("X-Secret");
                if (given == null || !MessageDigest.isEqual(secret, given.trim().getBytes(StandardCharsets.UTF_8))) {
                    reply(ex, 403, "{\"error\":\"bad secret\"}");
                    return;
                }
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                String path = ex.getRequestURI().getPath();
                String name = q.getOrDefault("name", "");
                switch (path) {
                    case "/site/login" -> reply(ex, 200, name.matches("[A-Za-z0-9_.]{1,32}")
                            ? auth.siteLogin(name, q.getOrDefault("ip", "")).toString() : "{\"error\":\"bad_name\"}");
                    case "/site/status" -> reply(ex, 200, auth.siteLoginStatus(q.getOrDefault("request", "")).toString());
                    case "/site/links" -> reply(ex, 200, auth.links(name).toString());
                    default -> reply(ex, 404, "{}");
                }
            } catch (Exception e) {
                log.warn("site API: {}", e.toString());
            }
        });
        // the site's shop, after a payment: the rank (LuckPerms, shared database) and the unban (LibertyBans) live here
        server.createContext("/shop/", ex -> {
            try (ex) {
                String given = ex.getRequestHeaders().getFirst("X-Secret");
                if (given == null || !MessageDigest.isEqual(secret, given.trim().getBytes(StandardCharsets.UTF_8))) {
                    reply(ex, 403, "{\"error\":\"bad secret\"}");
                    return;
                }
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                String name = q.getOrDefault("name", "");
                if (!name.matches("[A-Za-z0-9_.]{1,32}")) {
                    reply(ex, 400, "{\"error\":\"bad_name\"}");
                    return;
                }
                switch (ex.getRequestURI().getPath()) {
                    case "/shop/grant" -> {
                        String rank = q.getOrDefault("rank", "");
                        String days = q.getOrDefault("days", "");
                        if (!rank.matches("[a-z]{1,16}") || !days.matches("[1-9][0-9]{0,3}")) {
                            reply(ex, 400, "{\"error\":\"bad_rank\"}");
                            return;
                        }
                        // accumulate: buying the same rank again adds days instead of resetting the timer
                        boolean ok = console(proxy, "lpv user " + name + " parent addtemp " + rank + " " + days + "d accumulate", log);
                        if (ok) tell(proxy, name, "<#34D399>Спасибо за поддержку!</#34D399> <gray>Ранг <white>"
                                + q.getOrDefault("title", rank) + "</white> выдан на " + days + " дней.");
                        reply(ex, 200, "{\"ok\":" + ok + "}");
                    }
                    case "/shop/unban" -> reply(ex, 200, "{\"ok\":" + console(proxy, "libertybans unban " + name, log) + "}");
                    default -> reply(ex, 404, "{}");
                }
            } catch (Exception e) {
                log.warn("shop API: {}", e.toString());
            }
        });
        server.start();
    }

    /** Runs a console command on the proxy; false if no plugin knows it. */
    private static boolean console(ProxyServer proxy, String command, Logger log) throws Exception {
        log.info("shop: {}", command);
        return proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command).get(15, TimeUnit.SECONDS);
    }

    private static void tell(ProxyServer proxy, String name, String mini) {
        proxy.getPlayer(name).ifPresent(p -> p.sendMessage(MiniMessage.miniMessage().deserialize(mini)));
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
