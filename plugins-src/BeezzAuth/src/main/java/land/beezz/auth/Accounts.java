package land.beezz.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who owns which nickname: nickname → linked social accounts + last session.
 * Kept in accounts.json; small enough for a few thousand players, written atomically.
 */
public final class Accounts {

    public static final class Account {
        public String name;
        /** provider id ("telegram", "vk") → user id there */
        public Map<String, String> links = new LinkedHashMap<>();
        public String lastIp;
        public long sessionUntil;
        /** set when «Это я» came after the game stopped waiting: next login from this IP passes */
        public String preApprovedIp;
        public long preApprovedUntil;
        public long created;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Map<String, Account> byName = new LinkedHashMap<>();

    public Accounts(Path dir) {
        this.file = dir.resolve("accounts.json");
    }

    public synchronized void load() throws IOException {
        if (Files.notExists(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Account> m = GSON.fromJson(r, new TypeToken<Map<String, Account>>() { }.getType());
            if (m != null) {
                byName.putAll(m);
            }
        }
    }

    public synchronized void save() {
        try {
            Path tmp = file.resolveSibling("accounts.json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(byName, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("cannot save accounts.json", e);
        }
    }

    public synchronized Account get(String name) {
        return byName.get(key(name));
    }

    public synchronized Account getOrCreate(String name) {
        return byName.computeIfAbsent(key(name), k -> {
            Account a = new Account();
            a.name = name;
            a.created = System.currentTimeMillis();
            return a;
        });
    }

    public synchronized List<Account> linkedTo(String provider, String userId) {
        List<Account> out = new ArrayList<>();
        for (Account a : byName.values()) {
            if (userId.equals(a.links.get(provider))) {
                out.add(a);
            }
        }
        return out;
    }

    public synchronized boolean remove(String name) {
        return byName.remove(key(name)) != null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
