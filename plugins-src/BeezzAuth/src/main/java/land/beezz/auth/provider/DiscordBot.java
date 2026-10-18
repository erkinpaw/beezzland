package land.beezz.auth.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import land.beezz.auth.AuthConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Discord bot: receives DMs and button presses over the Gateway (WebSocket), answers over REST.
 * Players DM the bot their code; it must share a server with them (our Discord) to DM them back.
 * Only the DIRECT_MESSAGES intent is used — DM text does not need the privileged content intent.
 */
public final class DiscordBot implements Provider {

    private static final int INTENT_DIRECT_MESSAGES = 1 << 12;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final AuthConfig.Section cfg;
    private final Logger log;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("BeezzAuth-discord-heartbeat").unstarted(r));
    private volatile boolean running;
    private volatile WebSocket ws;
    private volatile Long seq;
    private ScheduledFuture<?> heartbeat;
    private Inbox inbox;

    public DiscordBot(AuthConfig.Section cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
    }

    @Override public String id() { return "discord"; }
    @Override public String title() { return "Discord"; }
    @Override public String where() { return cfg.link(); }

    @Override
    public void start(Inbox inbox) {
        this.inbox = inbox;
        running = true;
        Thread.ofVirtual().name("BeezzAuth-discord").start(this::connect);
    }

    @Override
    public void stop() {
        running = false;
        if (heartbeat != null) heartbeat.cancel(true);
        if (ws != null) ws.abort();
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ gateway

    private void connect() {
        while (running) {
            try {
                String url = rest("GET", "/gateway/bot", null).get("url").getAsString() + "/?v=10&encoding=json";
                CompletableFuture<Void> closed = new CompletableFuture<>();
                ws = HTTP.newWebSocketBuilder().buildAsync(URI.create(url), new Listener(closed)).join();
                closed.join(); // stay here until the socket closes, then reconnect
            } catch (Exception e) {
                if (!running) return;
                log.warn("Discord gateway: {}", e.toString());
            }
            if (heartbeat != null) heartbeat.cancel(true);
            sleepQuietly(5000);
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Void> closed;
        private final StringBuilder buf = new StringBuilder();

        Listener(CompletableFuture<Void> closed) {
            this.closed = closed;
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    onPayload(socket, JsonParser.parseString(msg).getAsJsonObject());
                } catch (Exception e) {
                    log.warn("Discord: cannot handle {}", msg, e);
                }
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            log.info("Discord gateway closed: {} {}", code, reason);
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("Discord gateway error: {}", error.toString());
            closed.complete(null);
        }
    }

    private void onPayload(WebSocket socket, JsonObject p) throws Exception {
        if (p.has("s") && !p.get("s").isJsonNull()) seq = p.get("s").getAsLong();
        int op = p.get("op").getAsInt();
        switch (op) {
            case 10 -> { // Hello: start heartbeating, then identify
                long every = p.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                heartbeat = timer.scheduleAtFixedRate(() -> send(socket, heartbeatPayload()), every, every, TimeUnit.MILLISECONDS);
                JsonObject props = new JsonObject();
                props.addProperty("os", "linux");
                props.addProperty("browser", "beezzauth");
                props.addProperty("device", "beezzauth");
                JsonObject d = new JsonObject();
                d.addProperty("token", cfg.token());
                d.addProperty("intents", INTENT_DIRECT_MESSAGES);
                d.add("properties", props);
                JsonObject identify = new JsonObject();
                identify.addProperty("op", 2);
                identify.add("d", d);
                send(socket, identify.toString());
            }
            case 1 -> send(socket, heartbeatPayload()); // server asks for a heartbeat now
            case 7, 9 -> socket.sendClose(4000, "reconnect"); // reconnect / invalid session: start over
            case 0 -> dispatch(p.get("t").getAsString(), p.getAsJsonObject("d"));
            default -> { }
        }
    }

    private String heartbeatPayload() {
        return "{\"op\":1,\"d\":" + (seq == null ? "null" : seq) + "}";
    }

    private void send(WebSocket socket, String text) {
        socket.sendText(text, true);
    }

    private void dispatch(String type, JsonObject d) throws Exception {
        if (type.equals("READY")) {
            log.info("Discord bot connected as {}", d.getAsJsonObject("user").get("username").getAsString());
        } else if (type.equals("MESSAGE_CREATE")) {
            JsonObject author = d.getAsJsonObject("author");
            if (d.has("guild_id") || (author.has("bot") && author.get("bot").getAsBoolean())) {
                return; // only DMs from people
            }
            String reply = inbox.onText(this, author.get("id").getAsString(), d.get("content").getAsString());
            if (reply != null) post(d.get("channel_id").getAsString(), reply, null);
        } else if (type.equals("INTERACTION_CREATE") && d.get("type").getAsInt() == 3) {
            JsonObject user = d.has("user") ? d.getAsJsonObject("user") : d.getAsJsonObject("member").getAsJsonObject("user");
            String custom = d.getAsJsonObject("data").get("custom_id").getAsString();
            if (custom.length() < 4 || custom.charAt(2) != ':') return;
            String result = inbox.onDecision(this, user.get("id").getAsString(), custom.substring(3), custom.startsWith("ok"));
            // 7 = update the message the button was on: replace text, drop the buttons
            JsonObject data = new JsonObject();
            data.addProperty("content", result);
            data.add("components", new JsonArray());
            JsonObject body = new JsonObject();
            body.addProperty("type", 7);
            body.add("data", data);
            rest("POST", "/interactions/" + d.get("id").getAsString() + "/" + d.get("token").getAsString() + "/callback", body);
        }
    }

    // ------------------------------------------------------------------ sending

    @Override
    public void sendConfirm(String userId, String nick, String maskedIp, String requestId) {
        JsonArray buttons = new JsonArray();
        buttons.add(button(3, "Это я", "ok:" + requestId));
        buttons.add(button(4, "Это не я", "no:" + requestId));
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        row.add("components", buttons);
        JsonArray rows = new JsonArray();
        rows.add(row);
        dm(userId, "🐝 **Вход на BeezzLand**\nНик: `" + nick + "`\nIP: `" + maskedIp + "`\n\nЭто ты?", rows);
    }

    @Override
    public void sendText(String userId, String text) {
        dm(userId, text, null);
    }

    private void dm(String userId, String text, JsonArray components) {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("recipient_id", userId);
            String channel = rest("POST", "/users/@me/channels", req).get("id").getAsString();
            post(channel, text, components);
        } catch (Exception e) {
            log.warn("Discord DM to {} failed: {}", userId, e.toString());
        }
    }

    private void post(String channelId, String text, JsonArray components) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("content", text);
        if (components != null) body.add("components", components);
        rest("POST", "/channels/" + channelId + "/messages", body);
    }

    private JsonObject rest(String method, String path, JsonObject body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(cfg.apiBase() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + cfg.token())
                .header("User-Agent", "DiscordBot (https://beezzland.ru, 1.0)");
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 300) {
            throw new IllegalStateException("Discord " + method + " " + path.replaceAll("/[^/]{40,}", "/…") + ": HTTP " + r.statusCode());
        }
        return r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static JsonObject button(int style, String label, String customId) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2);
        b.addProperty("style", style);
        b.addProperty("label", label);
        b.addProperty("custom_id", customId);
        return b;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
