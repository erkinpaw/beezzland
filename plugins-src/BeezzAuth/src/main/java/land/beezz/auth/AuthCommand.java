package land.beezz.auth;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Instant;
import java.util.List;

/**
 * /bauth info &lt;nick&gt;              — what is linked, last session
 * /bauth approve &lt;nick&gt;           — let the nick in once from any IP (support case: lost phone)
 * /bauth unlink &lt;nick&gt;            — forget the nick, it can be linked again
 * /bauth link &lt;code&gt; &lt;provider&gt; &lt;id&gt; — link by hand (support, tests)
 */
final class AuthCommand implements SimpleCommand {

    private final AuthService auth;
    private final Accounts accounts;

    AuthCommand(AuthService auth, Accounts accounts) {
        this.auth = auth;
        this.accounts = accounts;
    }

    @Override
    public boolean hasPermission(Invocation inv) {
        return inv.source().hasPermission("beezzauth.admin");
    }

    @Override
    public void execute(Invocation inv) {
        CommandSource src = inv.source();
        String[] a = inv.arguments();
        if (a.length >= 2 && a[0].equalsIgnoreCase("info")) {
            Accounts.Account acc = accounts.get(a[1]);
            if (acc == null) {
                say(src, a[1] + " не привязан.");
                return;
            }
            say(src, acc.name + ": " + acc.links + ", последний IP " + acc.lastIp
                    + ", сессия до " + Instant.ofEpochMilli(acc.sessionUntil));
        } else if (a.length >= 2 && a[0].equalsIgnoreCase("approve")) {
            say(src, auth.preApproveAnyIp(a[1]) ? a[1] + " может зайти с любого IP в ближайшие минуты."
                    : a[1] + " не привязан.");
        } else if (a.length >= 2 && a[0].equalsIgnoreCase("unlink")) {
            boolean removed = accounts.remove(a[1]);
            accounts.save();
            say(src, removed ? a[1] + " отвязан." : a[1] + " и так не привязан.");
        } else if (a.length >= 4 && a[0].equalsIgnoreCase("link")) {
            say(src, auth.link(a[2], a[3], a[1]));
        } else {
            say(src, "/bauth info|approve|unlink <ник>, /bauth link <код> <telegram|vk> <id>");
        }
    }

    @Override
    public List<String> suggest(Invocation inv) {
        return inv.arguments().length <= 1 ? List.of("info", "approve", "unlink", "link") : List.of();
    }

    private static void say(CommandSource src, String text) {
        src.sendMessage(Component.text(text, NamedTextColor.YELLOW));
    }
}
