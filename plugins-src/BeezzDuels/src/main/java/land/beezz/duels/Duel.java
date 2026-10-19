package land.beezz.duels;

import org.bukkit.entity.Player;

/** One fight in progress (or being counted down). */
final class Duel {

    final Player a, b;
    final Kit kit;
    final long stake;
    final int arena;
    final long started = System.currentTimeMillis();
    boolean fighting, over;

    Duel(Player a, Player b, Kit kit, long stake, int arena) {
        this.a = a;
        this.b = b;
        this.kit = kit;
        this.stake = stake;
        this.arena = arena;
    }

    Player other(Player p) {
        return p.equals(a) ? b : a;
    }

    boolean has(Player p) {
        return p.equals(a) || p.equals(b);
    }
}
