"""Player accounts on the site: login with «Это я» in the bot, the profile, skin heads and the ban list.

Data sources (all on the same VPS):
- BeezzDuels SQLite (zhuzhiki.db): balances, money log, duels, skins chosen in game (/skin);
- BeezzAuth local API (127.0.0.1:8766, key in api-secret.txt): login confirmation and linked messengers;
- LibertyBans in MariaDB: bans, mutes and their history (needs `pip install pymysql`).
"""
import hashlib, json, os, secrets, sqlite3, threading, time, urllib.parse, urllib.request

CFG = {"db": "", "auth": "", "auth_secret": "", "sessions": "", "bans": None}
SESSION_LIFE = 30 * 86400
_lock = threading.Lock()
_ask_log = {}  # ip -> [times] of login attempts


def setup(db, auth, auth_secret_file, sessions_file, bans_dsn):
    CFG["db"], CFG["auth"], CFG["sessions"] = db, auth.rstrip("/"), sessions_file
    try:
        CFG["auth_secret"] = open(auth_secret_file, encoding="utf-8").read().strip()
    except OSError:
        CFG["auth_secret"] = ""
    CFG["bans"] = bans_dsn


# ---------------------------------------------------------------- data access

def _db():
    # read-only: the game plugin owns the file, the site only looks
    return sqlite3.connect(f"file:{CFG['db']}?mode=ro", uri=True, timeout=5)


def _auth(path, **params):
    url = CFG["auth"] + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"X-Secret": CFG["auth_secret"]})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())


def skin_texture(name):
    """(known, texture): known = the player has been on our server; texture None = default skin."""
    try:
        with _db() as c:
            row = c.execute("SELECT texture FROM skins WHERE name=? COLLATE NOCASE ORDER BY updated DESC LIMIT 1", (name,)).fetchone()
    except sqlite3.Error:
        return False, None
    return (row is not None), (row[0] if row else None)


def head_url(name, size):
    """The in-game skin (also a /skin one) for our players; Steve if they have none; Mojang's skin otherwise."""
    size = max(8, min(256, size))
    known, texture = skin_texture(name)
    if texture:
        return f"https://mc-heads.net/avatar/{texture}/{size}"
    if known:
        return f"https://mc-heads.net/avatar/MHF_Steve/{size}"
    return f"https://mc-heads.net/avatar/{urllib.parse.quote(name)}/{size}"


# ---------------------------------------------------------------- sessions

def _sessions():
    try:
        return json.load(open(CFG["sessions"], encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save_sessions(s):
    tmp = CFG["sessions"] + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(s, f)
    os.replace(tmp, CFG["sessions"])


def _h(token):
    return hashlib.sha256(token.encode()).hexdigest()  # only hashes on disk: a leaked file logs nobody in


def new_session(name, ip):
    token = secrets.token_urlsafe(32)
    with _lock:
        s = {k: v for k, v in _sessions().items() if v["expires"] > time.time()}
        s[_h(token)] = {"name": name, "ip": ip, "created": int(time.time()), "expires": int(time.time()) + SESSION_LIFE}
        _save_sessions(s)
    return token


def session_name(token):
    if not token:
        return None
    v = _sessions().get(_h(token))
    return v["name"] if v and v["expires"] > time.time() else None


def end_session(token, everywhere=False):
    with _lock:
        s = _sessions()
        v = s.get(_h(token or ""))
        if v and everywhere:
            s = {k: x for k, x in s.items() if x["name"].lower() != v["name"].lower()}
        else:
            s.pop(_h(token or ""), None)
        _save_sessions(s)


# ---------------------------------------------------------------- login

def login_start(name, ip):
    now = time.time()
    with _lock:
        times = [t for t in _ask_log.get(ip, []) if t > now - 600]
        if len(times) >= 10:
            return 429, {"error": "too_many"}
        _ask_log[ip] = times + [now]
    try:
        r = _auth("/site/login", name=name, ip=ip)
    except OSError:
        return 503, {"error": "auth_down"}
    return (200 if "request" in r else 400), r


def login_status(request):
    try:
        return _auth("/site/status", request=request)
    except OSError:
        return {"state": "error"}


# ---------------------------------------------------------------- profile

def profile(name):
    out = {"name": name, "balance": 0, "streak": 0, "log": [], "duels": {"wins": 0, "losses": 0, "draws": 0, "earned": 0, "place": None},
           "recent": [], "links": []}
    try:
        links = _auth("/site/links", name=name)
        out["links"] = links.get("links", [])
        out["name"] = links.get("name", name)
    except OSError:
        pass
    try:
        with _db() as c:
            row = c.execute("SELECT uuid, name, amount, streak, last_day FROM balances WHERE name=? COLLATE NOCASE", (name,)).fetchone()
            if not row:
                return out
            uid = row[0]
            out.update(name=row[1], balance=row[2], streak=row[3], last_day=row[4])
            out["log"] = [{"delta": d, "reason": r, "at": a} for d, r, a in
                          c.execute("SELECT delta, reason, at FROM log WHERE uuid=? ORDER BY id DESC LIMIT 30", (uid,))]
            stats = c.execute("""SELECT SUM(winner=?), SUM(winner IS NOT NULL AND winner<>?), SUM(winner IS NULL),
                   SUM(CASE WHEN winner=? THEN prize-stake WHEN winner IS NULL THEN 0 ELSE -stake END)
                   FROM duels WHERE a=? OR b=?""", (uid, uid, uid, uid, uid)).fetchone()
            out["duels"].update(wins=stats[0] or 0, losses=stats[1] or 0, draws=stats[2] or 0, earned=stats[3] or 0)
            if out["duels"]["wins"]:
                out["duels"]["place"] = 1 + c.execute("""SELECT COUNT(*) FROM (SELECT id, SUM(w) AS wins FROM (
                    SELECT a AS id, winner=a AS w FROM duels UNION ALL SELECT b, winner=b FROM duels) GROUP BY id) WHERE wins>?""",
                    (out["duels"]["wins"],)).fetchone()[0]
            out["recent"] = [{"a": a, "b": b, "winner": w, "kit": k, "stake": s, "prize": p, "seconds": sec, "at": at} for a, b, w, k, s, p, sec, at in c.execute(
                """SELECT a_name, b_name, CASE WHEN winner=a THEN a_name WHEN winner=b THEN b_name END, kit, stake, prize, seconds, at
                   FROM duels WHERE a=? OR b=? ORDER BY at DESC LIMIT 20""", (uid, uid))]
    except sqlite3.Error:
        pass
    return out


# ---------------------------------------------------------------- bans (LibertyBans, MariaDB)

def bans(limit=200):
    """Bans and mutes with history. LibertyBans keeps them in libertybans_* tables of our MariaDB."""
    if not CFG["bans"]:
        return None
    try:
        import pymysql
    except ImportError:
        return None
    u = urllib.parse.urlparse(CFG["bans"])  # mysql://user:pass@host:3306/db
    conn = pymysql.connect(host=u.hostname, port=u.port or 3306, user=urllib.parse.unquote(u.username or ""),
                           password=urllib.parse.unquote(u.password or ""), database=u.path.lstrip("/"), connect_timeout=5)
    try:
        with conn.cursor() as cur:
            cur.execute(BANS_SQL, (limit,))
            rows = cur.fetchall()
    finally:
        conn.close()
    now = int(time.time())
    out = []
    for kind, victim, operator, reason, start, end, active in rows:
        out.append({"type": "ban" if kind == 0 else "mute", "name": victim or "?", "by": operator or "Консоль",
                    "reason": reason or "", "start": start * 1000, "end": end * 1000 if end else None,
                    "active": bool(active) and (not end or end > now)})
    return out


# LibertyBans' own views: simple_history = every punishment ever, simple_active = the ones still in force.
# type 0 = ban, 1 = mute; start/end in seconds, end 0 = forever; victim_type 0 = player (1, 2 = IP bans)
BANS_SQL = """
SELECT h.type, CASE WHEN h.victim_type = 0 THEN vn.name ELSE 'IP' END, onn.name, h.reason, h.start, h.end, a.id IS NOT NULL
FROM libertybans_simple_history h
LEFT JOIN libertybans_simple_active a ON a.id = h.id
LEFT JOIN libertybans_latest_names vn ON vn.uuid = h.victim_uuid
LEFT JOIN libertybans_latest_names onn ON onn.uuid = h.operator
WHERE h.type IN (0, 1)
ORDER BY h.start DESC LIMIT %s
"""
