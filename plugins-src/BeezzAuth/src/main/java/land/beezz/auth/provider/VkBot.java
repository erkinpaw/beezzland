package land.beezz.auth.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import land.beezz.auth.AuthConfig;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** VK community bot over Bots Long Poll API; confirm buttons are inline «callback» buttons. */
public final class VkBot implements Provider {

    private static final String V = "5.199";
    private final AuthConfig.Section cfg;
    private final Logger log;
    private volatile boolean running;
    private Thread thread;

    public VkBot(AuthConfig.Section cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
    }

    @Override public String id() { return "vk"; }
    @Override public String title() { return "ВКонтакте"; }
    @Override public String where() { return cfg.link(); }

    @Override
    public void start(Inbox inbox) {
        running = true;
        thread = Thread.ofVirtual().name("BeezzAuth-vk").start(() -> poll(inbox));
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private JsonObject call(String method, Map<String, String> params) throws Exception {
        Map<String, String> p = new LinkedHashMap<>(params);
        p.put("access_token", cfg.token());
        p.put("v", V);
        JsonObject r = Http.postForm(cfg.apiBase() + "/" + method, p);
        if (r.has("error")) {
            throw new IllegalStateException("VK " + method + ": " + r.getAsJsonObject("error").get("error_msg"));
        }
        return r;
    }

    private void poll(Inbox inbox) {
        String server = null, key = null, ts = null;
        while (running) {
            try {
                if (server == null) {
                    JsonObject s = call("groups.getLongPollServer", Map.of("group_id", String.valueOf(cfg.groupId())))
                            .getAsJsonObject("response");
                    server = s.get("server").getAsString();
                    key = s.get("key").getAsString();
                    ts = s.get("ts").getAsString();
                }
                JsonObject r = Http.get(server + "?act=a_check&key=" + Http.enc(key) + "&ts=" + Http.enc(ts) + "&wait=25",
                        Duration.ofSeconds(40));
                if (r.has("failed")) {
                    int failed = r.get("failed").getAsInt();
                    if (failed == 1 && r.has("ts")) {
                        ts = r.get("ts").getAsString();
                    } else {
                        server = null; // key expired or lost: ask for a new long poll server
                    }
                    continue;
                }
                ts = r.get("ts").getAsString();
                for (JsonElement el : r.getAsJsonArray("updates")) {
                    try {
                        handle(inbox, el.getAsJsonObject());
                    } catch (Exception e) {
                        log.warn("VK: cannot handle update {}", el, e);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!running) return;
                log.warn("VK polling failed: {}", e.toString());
                server = null;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handle(Inbox inbox, JsonObject u) throws Exception {
        String type = u.get("type").getAsString();
        JsonObject o = u.getAsJsonObject("object");
        if (type.equals("message_new")) {
            JsonObject m = o.getAsJsonObject("message");
            String userId = m.get("from_id").getAsString();
            String reply = inbox.onText(this, userId, m.get("text").getAsString());
            if (reply != null) sendText(userId, reply);
        } else if (type.equals("message_event")) {
            String userId = o.get("user_id").getAsString();
            JsonElement payload = o.get("payload");
            JsonObject pl = payload.isJsonObject() ? payload.getAsJsonObject() : JsonParser.parseString(payload.getAsString()).getAsJsonObject();
            String a = pl.get("a").getAsString();
            String result = inbox.onDecision(this, userId, a.substring(3), a.startsWith("ok"));
            JsonObject snackbar = new JsonObject();
            snackbar.addProperty("type", "show_snackbar");
            snackbar.addProperty("text", result);
            call("messages.sendMessageEventAnswer", Map.of(
                    "event_id", o.get("event_id").getAsString(),
                    "user_id", userId,
                    "peer_id", o.get("peer_id").getAsString(),
                    "event_data", snackbar.toString()));
            sendText(userId, result);
        }
    }

    @Override
    public void sendConfirm(String userId, String nick, String maskedIp, String requestId) {
        JsonArray row = new JsonArray();
        row.add(button("Это я", "ok:" + requestId, "positive"));
        row.add(button("Это не я", "no:" + requestId, "negative"));
        JsonArray rows = new JsonArray();
        rows.add(row);
        JsonObject keyboard = new JsonObject();
        keyboard.addProperty("inline", true);
        keyboard.add("buttons", rows);
        send(userId, "🐝 Вход на BeezzLand\n\nНик: " + nick + "\nIP: " + maskedIp + "\n\nЭто ты?", keyboard.toString());
    }

    @Override
    public void sendText(String userId, String text) {
        send(userId, text, null);
    }

    private void send(String userId, String text, String keyboard) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("peer_id", userId);
        p.put("random_id", String.valueOf(ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE)));
        p.put("message", text);
        if (keyboard != null) p.put("keyboard", keyboard);
        try {
            call("messages.send", p);
        } catch (Exception e) {
            log.warn("VK messages.send failed: {}", e.toString());
        }
    }

    private static JsonObject button(String label, String action, String color) {
        JsonObject payload = new JsonObject();
        payload.addProperty("a", action);
        JsonObject act = new JsonObject();
        act.addProperty("type", "callback");
        act.addProperty("label", label);
        act.addProperty("payload", payload.toString());
        JsonObject b = new JsonObject();
        b.add("action", act);
        b.addProperty("color", color);
        return b;
    }
}
