"""Shop on the site: donor ranks and the unban for rubles (ЮMoney), the unban also for жужики.

How a ruble payment goes:
  1. the logged-in player picks an item → POST /api/shop/order makes an order and returns the ЮMoney form;
  2. the browser posts that form to yoomoney.ru, the player pays by card or from a wallet;
  3. ЮMoney sends an HTTP notification to /api/shop/yoomoney (signed with the wallet's secret word);
  4. we check the signature and the sum, mark the order paid and deliver it through BeezzAuth's
     local API on the proxy (/shop/grant → LuckPerms, /shop/unban → LibertyBans). If the proxy is down,
     a background thread retries every minute until it goes through.

The wallet belongs to whoever runs the shop money: its number and secret word are written into shop.json
on the VPS by that person (never sent in chat). Orders and the unban counter are in shop.db next to it;
neither is touched by season wipes — the unban price doubles for the whole life of an account.
"""
import hashlib, hmac, json, os, secrets, sqlite3, threading, time, urllib.parse, urllib.request

import account

CFG = {"file": "", "db": "", "jujik_db": ""}
_lock = threading.Lock()

DEFAULTS = {
    "yoomoney": {"wallet": "", "secret": ""},
    # who gets a Telegram message about every purchase (the person running the money); empty = nobody
    "notify": {"bot_token": "", "chat_id": ""},
    "site": "https://beezzland.ru",
    "days": 90,
    "ranks": {
        "silver": {"title": "Silver", "rub": 199, "color": "#E2E8F0",
                   "perks": ["значок в чате и TAB", "AFK до 4 часов без кика", "3 свои пластинки за сезон"]},
        "gold": {"title": "Gold", "rub": 399, "color": "#FCD34D",
                 "perks": ["всё, что у Silver", "свой цвет ника (/color)", "/trade из любого места", "AFK до 8 часов, 5 пластинок"]},
        "diamond": {"title": "Diamond", "rub": 699, "color": "#67E8F9",
                    "perks": ["всё, что у Gold", "градиент ника из 2–4 цветов", "AFK до 12 часов, 7 пластинок"]},
    },
    # the first unban costs this, every next one twice as much: 299, 599, 1199… and 3000, 6000, 12000… жужиков
    "unban": {"rub": 299, "jujik": 3000},
}


def setup(config_file, db_file, jujik_db):
    CFG.update(file=config_file, db=db_file, jujik_db=jujik_db)
    if not os.path.exists(config_file):
        with open(config_file, "w", encoding="utf-8") as f:
            json.dump(DEFAULTS, f, ensure_ascii=False, indent=2)
    with _db() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS orders (id TEXT PRIMARY KEY, name TEXT NOT NULL, item TEXT NOT NULL,
                     method TEXT NOT NULL, price INTEGER NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL,
                     paid INTEGER, delivered INTEGER, operation TEXT, note TEXT)""")
        c.execute("CREATE TABLE IF NOT EXISTS unbans (name TEXT PRIMARY KEY, count INTEGER NOT NULL)")
    threading.Thread(target=_retry_loop, daemon=True).start()


def conf():
    """Read on every use: prices and the wallet can be changed in shop.json without a restart."""
    try:
        with open(CFG["file"], encoding="utf-8") as f:
            c = json.load(f)
    except (OSError, ValueError):
        c = {}
    return {**DEFAULTS, **c}


def _db():
    return sqlite3.connect(CFG["db"], timeout=10)


# ---------------------------------------------------------------- prices

def unban_count(name):
    with _db() as c:
        row = c.execute("SELECT count FROM unbans WHERE name=?", (name.lower(),)).fetchone()
    return row[0] if row else 0


def unban_price(name):
    """Doubles with each bought unban: 299 → 599 → 1199 (rubles end in 99), 3000 → 6000 → 12000 жужиков."""
    n, u = unban_count(name), conf()["unban"]
    return {"rub": (u["rub"] + 1) * 2 ** n - 1, "jujik": u["jujik"] * 2 ** n, "bought": n}


def banned(name):
    """True / False, or None when the ban list can't be read (then we don't sell the unban)."""
    try:
        rows = account.bans(limit=5000)
    except Exception as e:
        print("shop: bans:", e)
        return None
    if rows is None:
        return None
    return any(r["type"] == "ban" and r["active"] and r["name"].lower() == name.lower() for r in rows)


def jujik_balance(name):
    try:
        with sqlite3.connect(f"file:{CFG['jujik_db']}?mode=ro", uri=True, timeout=5) as c:
            row = c.execute("SELECT amount FROM balances WHERE name=? COLLATE NOCASE", (name,)).fetchone()
    except sqlite3.Error:
        return None
    return row[0] if row else 0


def catalog(name):
    c = conf()
    out = {"name": name, "days": c["days"], "methods": [],
           "ranks": [{"id": k, **{x: v[x] for x in ("title", "rub", "color", "perks")}} for k, v in c["ranks"].items()]}
    if c["yoomoney"].get("wallet") and c["yoomoney"].get("secret"):
        out["methods"].append("yoomoney")
    if name:
        out["balance"] = jujik_balance(name)
        out["banned"] = banned(name)
        out["unban"] = unban_price(name)
    return out


# ---------------------------------------------------------------- orders

def order(name, item, method, pay="AC"):
    """→ (http code, reply). Rubles: an order + the ЮMoney form. Жужики (unban only): paid and delivered right here."""
    c = conf()
    if item in c["ranks"]:
        if method != "yoomoney":
            return 400, {"error": "ranks_for_jujik_in_game"}  # ranks for жужики are bought in game: /donate
        price = c["ranks"][item]["rub"]
    elif item == "unban":
        state = banned(name)
        if state is None:
            return 503, {"error": "bans_unavailable"}
        if not state:
            return 400, {"error": "not_banned"}
        p = unban_price(name)
        price = p["rub"] if method == "yoomoney" else p["jujik"]
    else:
        return 400, {"error": "bad_item"}
    if item == "unban":
        with _db() as db:
            if db.execute("SELECT 1 FROM orders WHERE name=? COLLATE NOCASE AND item='unban' AND status='paid'", (name,)).fetchone():
                return 409, {"error": "in_progress"}  # paid, the proxy hasn't unbanned yet: don't take money twice
    if method == "jujik":
        with _lock:
            return _pay_jujik(name, price)
    if method != "yoomoney" or "yoomoney" not in catalog(None)["methods"]:
        return 400, {"error": "bad_method"}
    oid = secrets.token_hex(8)
    with _db() as db:
        db.execute("INSERT INTO orders(id, name, item, method, price, status, created) VALUES (?,?,?,?,?,?,?)",
                   (oid, name, item, method, price, "new", int(time.time())))
    title = c["ranks"][item]["title"] + f" на {c['days']} дней" if item in c["ranks"] else "Разбан"
    return 200, {"order": oid, "form": {
        "action": "https://yoomoney.ru/quickpay/confirm",
        "fields": {"receiver": c["yoomoney"]["wallet"], "quickpay-form": "button", "label": oid, "sum": str(price),
                   "targets": f"BeezzLand: {title} для {name}",
                   "paymentType": pay if pay in ("AC", "PC") else "AC",  # AC — bank card, PC — ЮMoney wallet
                   "successURL": c["site"].rstrip("/") + f"/shop.html?order={oid}"}}}


def _pay_jujik(name, price):
    """Takes жужики from the game's balance in one statement (it never goes below zero), then unbans."""
    reason = "разбан на сайте"
    try:
        with sqlite3.connect(CFG["jujik_db"], timeout=10) as c:
            c.execute("PRAGMA busy_timeout=5000")
            row = c.execute("SELECT uuid FROM balances WHERE name=? COLLATE NOCASE", (name,)).fetchone()
            if not row or c.execute("UPDATE balances SET amount=amount-? WHERE uuid=? AND amount>=?",
                                    (price, row[0], price)).rowcount == 0:
                return 400, {"error": "not_enough"}
            c.execute("INSERT INTO log(uuid, delta, reason, at) VALUES (?,?,?,?)", (row[0], -price, reason, int(time.time() * 1000)))
    except sqlite3.Error as e:
        print("shop: jujik:", e)
        return 503, {"error": "db"}
    oid = secrets.token_hex(8)
    with _db() as db:
        db.execute("INSERT INTO orders(id, name, item, method, price, status, created, paid) VALUES (?,?,?,?,?,?,?,?)",
                   (oid, name, "unban", "jujik", price, "paid", int(time.time()), int(time.time())))
    threading.Thread(target=_deliver, args=(oid,), daemon=True).start()  # outside the lock: the proxy may be slow
    return 200, {"order": oid, "status": "paid"}


def status(oid, name):
    with _db() as db:
        row = db.execute("SELECT item, method, price, status FROM orders WHERE id=? AND name=? COLLATE NOCASE", (oid, name)).fetchone()
    if not row:
        return {"status": "unknown"}
    return {"item": row[0], "method": row[1], "price": row[2], "status": row[3]}


# ---------------------------------------------------------------- ЮMoney

def yoomoney_notification(form):
    """HTTP notification from ЮMoney (form fields). → True if it was ours and is now handled."""
    c = conf()["yoomoney"]
    keys = ["notification_type", "operation_id", "amount", "currency", "datetime", "sender", "codepro"]
    line = "&".join([form.get(k, "") for k in keys] + [c.get("secret", ""), form.get("label", "")])
    if not c.get("secret") or not hmac.compare_digest(hashlib.sha1(line.encode()).hexdigest(), form.get("sha1_hash", "")):
        print("shop: ЮMoney notification with a wrong signature, ignored")
        return False
    oid = form.get("label", "")
    with _lock, _db() as db:
        row = db.execute("SELECT price, status FROM orders WHERE id=?", (oid,)).fetchone()
        if not row:
            print("shop: payment without our order:", oid, form.get("amount"))
            return True  # someone paid the wallet by hand — nothing to deliver, still a valid notice
        if row[1] != "new":
            return True  # ЮMoney repeats notifications: the order is already handled
        # withdraw_amount — what the payer paid; amount — what reached the wallet (minus ЮMoney's fee)
        paid = float(form.get("withdraw_amount") or form.get("amount") or 0)
        if form.get("currency") != "643" or form.get("codepro") == "true" or form.get("unaccepted") == "true" or paid + 0.001 < row[0]:
            db.execute("UPDATE orders SET status='check', operation=?, note=? WHERE id=?",
                       (form.get("operation_id"), f"paid {paid}, currency {form.get('currency')}, codepro {form.get('codepro')}, "
                                                 f"unaccepted {form.get('unaccepted')}", oid))
            _notify(f"⚠ Заказ {oid}: оплата не совпала с ценой ({paid} из {row[0]} ₽) — проверь в ЮMoney и выдай руками")
            return True
        db.execute("UPDATE orders SET status='paid', paid=?, operation=? WHERE id=?", (int(time.time()), form.get("operation_id"), oid))
    _deliver(oid)
    return True


# ---------------------------------------------------------------- delivery

def _deliver(oid):
    """Paid → delivered through the proxy. Failures stay 'paid' and are retried by the background thread."""
    with _db() as db:
        row = db.execute("SELECT name, item, method, price, status FROM orders WHERE id=?", (oid,)).fetchone()
    if not row or row[4] != "paid":
        return
    name, item, method, price = row[:4]
    c = conf()
    try:
        if item == "unban":
            ok = account.auth_call("/shop/unban", _timeout=20, name=name).get("ok")
        else:
            rank = c["ranks"][item]
            ok = account.auth_call("/shop/grant", _timeout=20, name=name, rank=item, days=c["days"], title=rank["title"]).get("ok")
    except OSError as e:
        print("shop: delivery of", oid, "failed, will retry:", e)
        return
    if not ok:
        print("shop: the proxy did not run the command for", oid)
        return
    with _lock, _db() as db:
        if db.execute("UPDATE orders SET status='delivered', delivered=? WHERE id=? AND status='paid'", (int(time.time()), oid)).rowcount and item == "unban":
            db.execute("INSERT INTO unbans(name, count) VALUES (?, 1) ON CONFLICT(name) DO UPDATE SET count=count+1", (name.lower(),))
    what = "разбан" if item == "unban" else c["ranks"][item]["title"]
    _notify(f"💰 {name}: {what} — {price} {'₽' if method == 'yoomoney' else 'жужиков'}")


def _retry_loop():
    while True:
        time.sleep(60)
        try:
            with _db() as db:
                ids = [r[0] for r in db.execute("SELECT id FROM orders WHERE status='paid'")]
            for oid in ids:
                _deliver(oid)
        except Exception as e:
            print("shop: retry:", e)


def _notify(text):
    n = conf()["notify"]
    if not n.get("bot_token") or not n.get("chat_id"):
        return
    try:
        data = urllib.parse.urlencode({"chat_id": n["chat_id"], "text": text}).encode()
        urllib.request.urlopen(f"https://api.telegram.org/bot{n['bot_token']}/sendMessage", data=data, timeout=10).read()
    except OSError as e:
        print("shop: telegram:", e)
