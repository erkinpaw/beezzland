package land.beezz.worlds;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/** Russian player-facing messages in BeezzLand colors. */
final class Msg {

    static final String HONEY = "<#FACC15>";
    static final String SKY = "<#93C5FD>";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    static Component text(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    static void chat(Audience to, String miniMessage) {
        to.sendMessage(text("<dark_gray>[" + HONEY + "Beezz</#FACC15><#60A5FA>Land</#60A5FA>]</dark_gray> <gray>" + miniMessage));
    }

    static void actionBar(Audience to, String miniMessage) {
        to.sendActionBar(text(miniMessage));
    }

    static void closed(Audience to, Stage stage) {
        actionBar(to, "<#F87171>" + stage.title() + " пока закрыт.</#F87171> <gray>Следи за новостями — откроем скоро!");
    }
}
