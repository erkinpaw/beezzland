package land.beezz.auth;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import land.beezz.auth.provider.DiscordBot;
import land.beezz.auth.provider.Provider;
import land.beezz.auth.provider.TelegramBot;
import land.beezz.auth.provider.VkBot;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(id = "beezzauth", name = "BeezzAuth", version = "1.0.0",
        description = "Вход без паролей: привязка к ВК / Telegram / Discord и подтверждение входа кнопкой",
        authors = {"BeezzLand"})
public final class BeezzAuth {

    private final ProxyServer proxy;
    private final Logger log;
    private final Path dir;
    private AuthService auth;

    @Inject
    public BeezzAuth(ProxyServer proxy, Logger log, @DataDirectory Path dir) {
        this.proxy = proxy;
        this.log = log;
        this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) throws Exception {
        AuthConfig cfg = AuthConfig.load(dir);
        Accounts accounts = new Accounts(dir);
        accounts.load();
        auth = new AuthService(cfg, accounts, log);
        if (cfg.telegram.enabled()) auth.addProvider(new TelegramBot(cfg.telegram, log));
        if (cfg.vk.enabled()) auth.addProvider(new VkBot(cfg.vk, log));
        if (cfg.discord.enabled()) auth.addProvider(new DiscordBot(cfg.discord, log));
        for (Provider p : auth.providers()) {
            p.start(auth);
            log.info("{} bot started ({})", p.title(), p.where());
        }
        if (!auth.providers().iterator().hasNext()) {
            log.warn("No bots enabled in plugins/beezzauth/config.yml — new players can only be linked with /bauth link");
        }
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("bauth").plugin(this).build(),
                new AuthCommand(auth, accounts));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (auth != null) {
            for (Provider p : auth.providers()) p.stop();
        }
    }

    /** Waiting for a button press must not block Velocity's event threads, hence an async task. */
    @Subscribe
    public EventTask onLogin(LoginEvent e) {
        if (!e.getResult().isAllowed()) {
            return null;
        }
        String name = e.getPlayer().getUsername();
        String ip = e.getPlayer().getRemoteAddress().getAddress().getHostAddress();
        return EventTask.async(() -> {
            AuthService.Verdict v = auth.decide(name, ip);
            if (!v.allowed()) {
                e.setResult(ResultedEvent.ComponentResult.denied(v.kickMessage()));
            }
        });
    }
}
