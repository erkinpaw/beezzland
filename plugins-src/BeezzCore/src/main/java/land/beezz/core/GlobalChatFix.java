package land.beezz.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Carbon 3.0.0-beta.39 strips the global quick prefix ("!") while decorating the message and then
 * picks the channel again from the stripped text, so "!hello" lands in local chat. Put the prefix
 * back before Carbon's HIGHEST handler; Carbon then routes it to global and strips it once.
 */
final class GlobalChatFix implements Listener {

    private static final String PREFIX = "!";

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        String typed = e.signedMessage().message();
        if (!typed.startsWith(PREFIX) || typed.length() == PREFIX.length()) return;
        String now = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (!now.startsWith(PREFIX)) e.message(Component.text(PREFIX).append(e.message()));
    }
}
