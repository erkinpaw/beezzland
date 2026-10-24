package land.beezz.duels;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Balances, the money log and the duel history in one SQLite file (Paper ships the driver).
 * One connection, every call synchronized: balance changes must never interleave.
 */
final class Store {

    record Top(String name, int wins, int losses, long earned) {
    }

    record Fight(String a, String b, String winner, String kit, long stake, long prize, int seconds, long at) {
    }

    record Daily(long reward, int streak) {
    }

    private final Connection db;

    Store(File file) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            // the site writes here too (unban for жужики): wait for its short transaction instead of failing
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS balances (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0,"
                    + " last_day TEXT, streak INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS log (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, delta INTEGER NOT NULL,"
                    + " reason TEXT NOT NULL, at INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS duels (id INTEGER PRIMARY KEY, a TEXT NOT NULL, a_name TEXT NOT NULL,"
                    + " b TEXT NOT NULL, b_name TEXT NOT NULL, winner TEXT, kit TEXT NOT NULL, stake INTEGER NOT NULL,"
                    + " prize INTEGER NOT NULL, seconds INTEGER NOT NULL, at INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS duels_at ON duels(at)");
            // the skin each player has in game (also a /skin from SkinsRestorer): the site shows these heads
            s.execute("CREATE TABLE IF NOT EXISTS skins (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, texture TEXT, updated INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS skins_name ON skins(name COLLATE NOCASE)");
        }
    }

    synchronized void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
        }
    }

    synchronized long balance(UUID id) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT amount FROM balances WHERE uuid=?")) {
            p.setString(1, id.toString());
            ResultSet r = p.executeQuery();
            return r.next() ? r.getLong(1) : 0;
        }
    }

    /** Adds (or with a negative delta takes) money. Returns false and changes nothing if it would go below zero. */
    synchronized boolean add(UUID id, String name, long delta, String reason) throws SQLException {
        ensure(id, name);
        try (PreparedStatement p = db.prepareStatement("UPDATE balances SET amount=amount+?, name=? WHERE uuid=? AND amount+?>=0")) {
            p.setLong(1, delta);
            p.setString(2, name);
            p.setString(3, id.toString());
            p.setLong(4, delta);
            if (p.executeUpdate() == 0) return false;
        }
        try (PreparedStatement p = db.prepareStatement("INSERT INTO log(uuid, delta, reason, at) VALUES (?,?,?,?)")) {
            p.setString(1, id.toString());
            p.setLong(2, delta);
            p.setString(3, reason);
            p.setLong(4, System.currentTimeMillis());
            p.executeUpdate();
        }
        return true;
    }

    /** Daily reward: null if already taken today. */
    synchronized Daily daily(UUID id, String name, String today, String yesterday, long base, long step, long max) throws SQLException {
        ensure(id, name);
        String last;
        int streak;
        try (PreparedStatement p = db.prepareStatement("SELECT last_day, streak FROM balances WHERE uuid=?")) {
            p.setString(1, id.toString());
            ResultSet r = p.executeQuery();
            r.next();
            last = r.getString(1);
            streak = r.getInt(2);
        }
        if (today.equals(last)) return null;
        streak = yesterday.equals(last) ? streak + 1 : 1;
        long reward = Math.min(max, base + step * (streak - 1));
        try (PreparedStatement p = db.prepareStatement("UPDATE balances SET last_day=?, streak=? WHERE uuid=?")) {
            p.setString(1, today);
            p.setInt(2, streak);
            p.setString(3, id.toString());
            p.executeUpdate();
        }
        add(id, name, reward, "вход, день " + streak);
        return new Daily(reward, streak);
    }

    synchronized int pairToday(UUID a, UUID b) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT COUNT(*) FROM duels WHERE at>? AND ((a=? AND b=?) OR (a=? AND b=?))")) {
            p.setLong(1, System.currentTimeMillis() - 86_400_000L);
            p.setString(2, a.toString());
            p.setString(3, b.toString());
            p.setString(4, b.toString());
            p.setString(5, a.toString());
            ResultSet r = p.executeQuery();
            return r.next() ? r.getInt(1) : 0;
        }
    }

    synchronized void record(Duel d, UUID winner, long prize, int seconds) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO duels(a, a_name, b, b_name, winner, kit, stake, prize, seconds, at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, d.a.getUniqueId().toString());
            p.setString(2, d.a.getName());
            p.setString(3, d.b.getUniqueId().toString());
            p.setString(4, d.b.getName());
            p.setString(5, winner == null ? null : winner.toString());
            p.setString(6, d.kit.id());
            p.setLong(7, d.stake);
            p.setLong(8, prize);
            p.setInt(9, seconds);
            p.setLong(10, System.currentTimeMillis());
            p.executeUpdate();
        }
    }

    synchronized List<Top> top(int n) throws SQLException {
        List<Top> out = new ArrayList<>();
        String sql = "SELECT name, SUM(win), SUM(loss), SUM(earned) FROM ("
                + " SELECT a_name AS name, a AS id, winner=a AS win, winner=b AS loss, CASE WHEN winner=a THEN prize-stake WHEN winner=b THEN -stake ELSE 0 END AS earned FROM duels"
                + " UNION ALL SELECT b_name, b, winner=b, winner=a, CASE WHEN winner=b THEN prize-stake WHEN winner=a THEN -stake ELSE 0 END FROM duels)"
                + " GROUP BY id ORDER BY SUM(win) DESC, SUM(earned) DESC LIMIT ?";
        try (PreparedStatement p = db.prepareStatement(sql)) {
            p.setInt(1, n);
            ResultSet r = p.executeQuery();
            while (r.next()) out.add(new Top(r.getString(1), r.getInt(2), r.getInt(3), r.getLong(4)));
        }
        return out;
    }

    synchronized List<Fight> recent(int n) throws SQLException {
        List<Fight> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT a_name, b_name, CASE WHEN winner=a THEN a_name WHEN winner=b THEN b_name END,"
                + " kit, stake, prize, seconds, at FROM duels ORDER BY at DESC LIMIT ?")) {
            p.setInt(1, n);
            ResultSet r = p.executeQuery();
            while (r.next()) {
                out.add(new Fight(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5), r.getLong(6), r.getInt(7), r.getLong(8)));
            }
        }
        return out;
    }

    synchronized List<Top> richest(int n) throws SQLException {
        List<Top> out = new ArrayList<>();
        try (PreparedStatement p = db.prepareStatement("SELECT name, amount FROM balances ORDER BY amount DESC LIMIT ?")) {
            p.setInt(1, n);
            ResultSet r = p.executeQuery();
            while (r.next()) out.add(new Top(r.getString(1), 0, 0, r.getLong(2)));
        }
        return out;
    }

    /** Remembers the skin texture (hash on textures.minecraft.net, null = default skin). */
    synchronized void skin(UUID id, String name, String texture) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO skins(uuid, name, texture, updated) VALUES (?,?,?,?)"
                + " ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, texture=excluded.texture, updated=excluded.updated")) {
            p.setString(1, id.toString());
            p.setString(2, name);
            p.setString(3, texture);
            p.setLong(4, System.currentTimeMillis());
            p.executeUpdate();
        }
    }

    private void ensure(UUID id, String name) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO balances(uuid, name) VALUES (?,?)")) {
            p.setString(1, id.toString());
            p.setString(2, name);
            p.executeUpdate();
        }
    }
}
