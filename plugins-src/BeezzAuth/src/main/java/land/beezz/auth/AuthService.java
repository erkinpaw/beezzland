package land.beezz.auth;

import land.beezz.auth.provider.Provider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The login decision for every connecting player:
 * <ol>
 *   <li>nickname not linked yet → kick with a link code to send to a bot;</li>
 *   <li>same IP and session still valid → let in;</li>
 *   <li>otherwise → ask in every linked bot «Это ты?» and wait on the loading screen.</li>
 * </ol>
 */
public final class AuthService implements Provider.Inbox {

    /** What to do with a login: null message = let in. */
    public record Verdict(Component kickMessage) {
        static final Verdict ALLOW = new Verdict(null);

        public boolean allowed() {
            return kickMessage == null;
        }
    }

    private record PendingLink(String name, String ip, long expires) {
    }

    private record PendingConfirm(String name, String ip, long created, CompletableFuture<Boolean> answer) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String HEAD = "<#FACC15><bold>Beezz</bold></#FACC15><#60A5FA><bold>Land</bold></#60A5FA>\n\n";
    private static final Pattern CODE = Pattern.compile("(\\d{6})");
    private final SecureRandom random = new SecureRandom();

    private final AuthConfig cfg;
    private final Accounts accounts;
    private final Logger log;
    private final Map<String, Provider> providers = new ConcurrentHashMap<>();
    private final Map<String, PendingLink> codes = new ConcurrentHashMap<>();
    private final Map<String, PendingConfirm> confirms = new ConcurrentHashMap<>();

    public AuthService(AuthConfig cfg, Accounts accounts, Logger log) {
        this.cfg = cfg;
        this.accounts = accounts;
        this.log = log;
    }

    public void addProvider(Provider p) {
        providers.put(p.id(), p);
    }

    public Iterable<Provider> providers() {
        return providers.values();
    }

    // ------------------------------------------------------------------ login

    /** Blocking: may wait up to confirm-timeout for a button press. Run off the event thread. */
    public Verdict decide(String name, String ip) {
        long now = System.currentTimeMillis();
        Accounts.Account acc = accounts.get(name);
        if (acc == null || acc.links.isEmpty()) {
            return new Verdict(linkScreen(newCode(name, ip)));
        }
        if (ip.equals(acc.lastIp) && now < acc.sessionUntil
                || ip.equals(acc.preApprovedIp) && now < acc.preApprovedUntil
                || preApprovedAnyIp(acc)) {
            startSession(acc, ip);
            return Verdict.ALLOW;
        }
        List<Provider> reachable = acc.links.keySet().stream().map(providers::get).filter(p -> p != null).toList();
        if (reachable.isEmpty()) {
            return new Verdict(MM.deserialize(HEAD + "<white>Бот для подтверждения входа сейчас недоступен.\n\n"
                    + "<gray>Попробуй через пару минут или напиши администрации."));
        }
        String requestId = token(8);
        confirms.values().removeIf(c -> c.created < now - cfg.lateApprove.toMillis()); // unanswered, too old
        PendingConfirm pending = new PendingConfirm(acc.name, ip, now, new CompletableFuture<>());
        confirms.put(requestId, pending);
        for (Provider p : reachable) {
            p.sendConfirm(acc.links.get(p.id()), acc.name, mask(ip), requestId);
        }
        log.info("{} logs in from a new address, waiting for confirmation in {}", acc.name,
                reachable.stream().map(Provider::title).toList());
        try {
            boolean ok = pending.answer().get(cfg.confirmTimeout.toSeconds(), TimeUnit.SECONDS);
            if (ok) {
                startSession(acc, ip);
                return Verdict.ALLOW;
            }
            confirms.remove(requestId);
            return new Verdict(MM.deserialize(HEAD + "<#F87171>Вход отклонён.</#F87171>\n\n"
                    + "<gray>Если это был не ты — всё в порядке, никто не зайдёт под твоим ником."));
        } catch (TimeoutException e) {
            // keep the request: a late «Это я» still opens the next login from this IP
            return new Verdict(MM.deserialize(HEAD + "<white>Подтверди вход в боте</white> <gray>("
                    + String.join(", ", reachable.stream().map(Provider::title).toList()) + ")</gray>\n\n"
                    + "<gray>Нажми <#34D399>«Это я»</#34D399> и зайди снова — пустим сразу."));
        } catch (Exception e) {
            confirms.remove(requestId);
            log.warn("confirmation for {} failed", acc.name, e);
            return new Verdict(MM.deserialize(HEAD + "<gray>Что-то пошло не так, попробуй зайти ещё раз."));
        }
    }

    private void startSession(Accounts.Account acc, String ip) {
        acc.lastIp = ip;
        acc.sessionUntil = System.currentTimeMillis() + cfg.session.toMillis();
        acc.preApprovedIp = null;
        acc.preApprovedUntil = 0;
        accounts.save();
    }

    private Component linkScreen(String code) {
        StringBuilder sb = new StringBuilder(HEAD)
                .append("<white>Привяжи аккаунт — и играй без паролей.</white>\n\n")
                .append("<gray>Отправь код <#FACC15><bold>").append(code).append("</bold></#FACC15> нашему боту:\n");
        for (Provider p : providers.values()) {
            sb.append("<white>").append(p.title()).append(": <#93C5FD>").append(p.where()).append("</#93C5FD>\n");
        }
        if (providers.isEmpty()) {
            sb.append("<#F87171>боты ещё не подключены — попроси администратора</#F87171>\n");
        }
        sb.append("\n<dark_gray>Код действует ").append(cfg.linkCodeLife.toMinutes())
                .append(" минут. После привязки просто зайди снова.");
        return MM.deserialize(sb.toString());
    }

    private String newCode(String name, String ip) {
        long now = System.currentTimeMillis();
        codes.values().removeIf(c -> c.expires < now);
        // the same player reconnecting keeps their code instead of getting a new one each time
        for (Map.Entry<String, PendingLink> e : codes.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        String code;
        do {
            code = String.format("%06d", random.nextInt(1_000_000));
        } while (codes.containsKey(code));
        codes.put(code, new PendingLink(name, ip, now + cfg.linkCodeLife.toMillis()));
        return code;
    }

    // ------------------------------------------------------------------ bots

    @Override
    public String onText(Provider from, String userId, String text) {
        Matcher m = CODE.matcher(text);
        if (!m.find()) {
            return "Привет! 🐝 Я бот сервера BeezzLand.\n\n"
                    + "Зайди на сервер — он покажет 6-значный код. Пришли этот код сюда, "
                    + "и твой ник будет привязан к этому аккаунту. Паролей не нужно.";
        }
        return link(from.id(), userId, m.group(1));
    }

    /** Also used by the admin command to link by hand. */
    public String link(String providerId, String userId, String code) {
        PendingLink pl = codes.remove(code);
        if (pl == null || pl.expires < System.currentTimeMillis()) {
            return "Код не найден или устарел. Зайди на сервер ещё раз — он покажет новый.";
        }
        Accounts.Account acc = accounts.getOrCreate(pl.name);
        acc.links.put(providerId, userId);
        startSession(acc, pl.ip); // the next login from the same computer goes straight in
        log.info("{} linked to {} {}", acc.name, providerId, userId);
        return "Готово! Ник " + acc.name + " привязан ✅\n\nЗаходи на сервер. "
                + "Если войдут с другого устройства — я спрошу здесь, ты ли это.";
    }

    @Override
    public String onDecision(Provider from, String userId, String requestId, boolean approve) {
        PendingConfirm pc = confirms.remove(requestId);
        if (pc == null) {
            return "Этот запрос уже неактуален.";
        }
        Accounts.Account acc = accounts.get(pc.name);
        if (acc == null || !userId.equals(acc.links.get(from.id()))) {
            confirms.put(requestId, pc); // not this user's request: leave it for the real owner
            return "Это не твой запрос.";
        }
        if (approve && !pc.answer().complete(true)) {
            // the game had already stopped waiting: let the next login from this IP in
            acc.preApprovedIp = pc.ip;
            acc.preApprovedUntil = System.currentTimeMillis() + cfg.lateApprove.toMillis();
            accounts.save();
            return "Подтверждено ✅ Зайди на сервер снова.";
        }
        if (!approve) {
            pc.answer().complete(false);
            log.warn("{} rejected a login from {}", acc.name, pc.ip);
            return "Вход отклонён ❌ Никто не зайдёт под твоим ником.";
        }
        return "Вход подтверждён ✅ Приятной игры!";
    }

    /** Admin: let this nickname in once from any IP within late-approve minutes. */
    public boolean preApproveAnyIp(String name) {
        Accounts.Account acc = accounts.get(name);
        if (acc == null) return false;
        acc.preApprovedIp = "*";
        acc.preApprovedUntil = System.currentTimeMillis() + cfg.lateApprove.toMillis();
        accounts.save();
        return true;
    }

    boolean preApprovedAnyIp(Accounts.Account acc) {
        return "*".equals(acc.preApprovedIp) && System.currentTimeMillis() < acc.preApprovedUntil;
    }

    static String mask(String ip) {
        String[] parts = ip.split("\\.");
        return parts.length == 4 ? parts[0] + "." + parts[1] + ".*.*" : ip.replaceAll(":[^:]*:[^:]*$", ":*:*");
    }

    private String token(int len) {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(abc.charAt(random.nextInt(abc.length())));
        return sb.toString();
    }
}
