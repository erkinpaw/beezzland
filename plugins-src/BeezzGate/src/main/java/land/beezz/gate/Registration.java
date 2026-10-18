package land.beezz.gate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The registration window: BeezzAuth (on the proxy) knows whether the nick is linked and its code;
 * this shows the code with «Открыть Telegram / Discord» buttons and waits until the link is done.
 */
final class Registration {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String api;
    private final String secret;

    Registration(String api, String secret) {
        this.api = api;
        this.secret = secret;
    }

    boolean enabled() {
        return !api.isEmpty();
    }

    /** Blocks (off the main thread) until the nick is linked; false if the deadline passed first. */
    boolean await(Audience who, String name, String ip, long deadline) throws Exception {
        String shownCode = null;
        while (System.currentTimeMillis() < deadline) {
            JsonObject s = status(name, ip);
            if (s.get("linked").getAsBoolean()) {
                return true;
            }
            String code = s.get("code").getAsString();
            if (!code.equals(shownCode)) { // first time, or the old code ran out and a new one was made
                who.showDialog(window(s));
                shownCode = code;
            }
            Thread.sleep(2000);
        }
        return false;
    }

    private JsonObject status(String name, String ip) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/link?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                        + "&ip=" + URLEncoder.encode(ip, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(5)).header("X-Secret", secret).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new IllegalStateException("BeezzAuth API: HTTP " + r.statusCode());
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private Dialog window(JsonObject s) {
        String code = s.get("code").getAsString();
        StringBuilder text = new StringBuilder("<white>Привяжи аккаунт — и играй без паролей.</white>\n\n")
                .append("<gray>Твой код:</gray> <#FACC15><bold>").append(code).append("</bold></#FACC15>\n\n");
        List<ActionButton> buttons = new ArrayList<>();
        for (JsonElement e : s.getAsJsonArray("bots")) {
            JsonObject b = e.getAsJsonObject();
            String id = b.get("id").getAsString();
            text.append(switch (id) {
                case "telegram" -> "<#60A5FA>Telegram:</#60A5FA> <gray>нажми кнопку и в боте «Запустить» — код отправится сам.</gray>\n";
                case "discord" -> "<#A5B4FC>Discord:</#A5B4FC> <gray>в канале #регистрация нажми «Привязать аккаунт» и введи код.</gray>\n";
                default -> "<white>" + b.get("title").getAsString() + ":</white> <gray>отправь код боту " + b.get("where").getAsString() + "</gray>\n";
            });
            buttons.add(ActionButton.builder(MM.deserialize("<white>Открыть " + b.get("title").getAsString()))
                    .width(150).action(DialogAction.staticAction(ClickEvent.openUrl(b.get("url").getAsString()))).build());
        }
        text.append("\n<dark_gray>Код действует ").append(s.get("minutes").getAsLong())
                .append(" минут. Окно само пустит дальше, как только аккаунт привяжется.");
        return Dialog.create(d -> d.empty()
                .base(DialogBase.builder(MM.deserialize("<#FACC15><bold>Beezz</bold></#FACC15><#60A5FA><bold>Land</bold></#60A5FA>"))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE) // opening a link must not close the window
                        .body(List.of(DialogBody.plainMessage(MM.deserialize(text.toString()), 300)))
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
    }
}
