package land.beezz.auth.provider;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import land.beezz.auth.AuthConfig;
import org.slf4j.Logger;

import java.time.Duration;

/** Telegram bot over the Bot API with long polling (no webhook, no open ports needed). */
public final class TelegramBot implements Provider {

    private final AuthConfig.Section cfg;
    private final Logger log;
    private volatile boolean running;
    private Thread thread;

    public TelegramBot(AuthConfig.Section cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
    }

    @Override public String id() { return "telegram"; }
    @Override public String title() { return "Telegram"; }
    @Override public String where() { return "@" + cfg.username(); }
    // t.me/<bot>?start=<code>: Telegram sends «/start <code>» by itself, the player only presses «Start»
    @Override public String url(String code) { return "https://t.me/" + cfg.username() + "?start=" + code; }

    private String api(String method) {
        return cfg.apiBase() + "/bot" + cfg.token() + "/" + method;
    }

    @Override
    public void start(Inbox inbox) {
        running = true;
        thread = Thread.ofVirtual().name("BeezzAuth-telegram").start(() -> poll(inbox));
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void poll(Inbox inbox) {
        long offset = 0;
        while (running) {
            try {
                JsonObject r = Http.get(api("getUpdates") + "?timeout=25&offset=" + offset, Duration.ofSeconds(40));
                if (!r.has("ok") || !r.get("ok").getAsBoolean()) {
                    log.warn("Telegram: {}", r);
                    Thread.sleep(5000);
                    continue;
                }
                for (JsonElement el : r.getAsJsonArray("result")) {
                    JsonObject u = el.getAsJsonObject();
                    offset = Math.max(offset, u.get("update_id").getAsLong() + 1);
                    try {
                        handle(inbox, u);
                    } catch (Exception e) {
                        log.warn("Telegram: cannot handle update {}", u, e);
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (!running) return;
                log.warn("Telegram polling failed: {}", e.toString());
                sleepQuietly(5000);
            }
        }
    }

    private void handle(Inbox inbox, JsonObject u) throws Exception {
        if (u.has("message")) {
            JsonObject m = u.getAsJsonObject("message");
            if (!m.has("text")) return;
            String userId = m.getAsJsonObject("from").get("id").getAsString();
            String reply = inbox.onText(this, userId, m.get("text").getAsString());
            if (reply != null) sendText(userId, reply);
        } else if (u.has("callback_query")) {
            JsonObject q = u.getAsJsonObject("callback_query");
            String userId = q.getAsJsonObject("from").get("id").getAsString();
            String data = q.has("data") ? q.get("data").getAsString() : "";
            if (data.length() < 4 || data.charAt(2) != ':') return;
            String result = inbox.onDecision(this, userId, data.substring(3), data.startsWith("ok"));
            JsonObject ans = new JsonObject();
            ans.addProperty("callback_query_id", q.get("id").getAsString());
            ans.addProperty("text", result);
            Http.postJson(api("answerCallbackQuery"), ans);
            if (q.has("message")) {
                JsonObject msg = q.getAsJsonObject("message");
                JsonObject edit = new JsonObject();
                edit.addProperty("chat_id", msg.getAsJsonObject("chat").get("id").getAsString());
                edit.addProperty("message_id", msg.get("message_id").getAsLong());
                edit.addProperty("text", (msg.has("text") ? msg.get("text").getAsString() + "\n\n" : "") + result);
                Http.postJson(api("editMessageText"), edit);
            }
        }
    }

    @Override
    public void sendConfirm(String userId, String what, String nick, String maskedIp, String requestId) {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", userId);
        body.addProperty("text", "🐝 " + what + "\n\nНик: " + nick + "\nIP: " + maskedIp + "\n\nЭто ты?");
        JsonArray row = new JsonArray();
        row.add(button("✅ Это я", "ok:" + requestId));
        row.add(button("❌ Это не я", "no:" + requestId));
        JsonArray rows = new JsonArray();
        rows.add(row);
        JsonObject markup = new JsonObject();
        markup.add("inline_keyboard", rows);
        body.add("reply_markup", markup);
        post("sendMessage", body);
    }

    @Override
    public void sendText(String userId, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", userId);
        body.addProperty("text", text);
        post("sendMessage", body);
    }

    private void post(String method, JsonObject body) {
        try {
            JsonObject r = Http.postJson(api(method), body);
            if (!r.get("ok").getAsBoolean()) log.warn("Telegram {}: {}", method, r);
        } catch (Exception e) {
            log.warn("Telegram {} failed: {}", method, e.toString());
        }
    }

    private static JsonObject button(String text, String data) {
        JsonObject b = new JsonObject();
        b.addProperty("text", text);
        b.addProperty("callback_data", data);
        return b;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
