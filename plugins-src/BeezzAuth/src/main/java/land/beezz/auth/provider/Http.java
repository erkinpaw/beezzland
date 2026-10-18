package land.beezz.auth.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** Tiny JSON-over-HTTP helper shared by the bots. */
final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private Http() {
    }

    static JsonObject get(String url, Duration timeout) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build());
    }

    static JsonObject postJson(String url, JsonObject body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build());
    }

    static JsonObject postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8)).build());
    }

    static String form(Map<String, String> params) {
        StringJoiner j = new StringJoiner("&");
        params.forEach((k, v) -> j.add(enc(k) + "=" + enc(v)));
        return j.toString();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonObject send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 500) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }
}
