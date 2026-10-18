package land.beezz.auth.provider;

/** A messenger bot players link their nickname to (Telegram, VK, later Discord). */
public interface Provider {

    /** Called by a provider when something arrives from a player. */
    interface Inbox {
        /** The player sent a text (normally the link code). Returns the reply to send back. */
        String onText(Provider from, String userId, String text);

        /** The player pressed «Это я» / «Это не я». Returns a short result text for the button answer. */
        String onDecision(Provider from, String userId, String requestId, boolean approve);
    }

    String id();

    /** Human name for messages: «ВКонтакте», «Telegram». */
    String title();

    /** Where players find the bot, shown on the kick screen: «vk.me/beezzland», «@beezzland_bot». */
    String where();

    void start(Inbox inbox);

    void stop();

    void sendConfirm(String userId, String nick, String maskedIp, String requestId);

    void sendText(String userId, String text);
}
