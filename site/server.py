"""BeezzLand site: static files + /api/status (live server ping) + /map/ (proxy to Pl3xMap)
+ /webtrade/calculator.html (the price calculator WebTrade exports with current prices)
+ /api/duels (top, last fights and kits that BeezzDuels exports to duels.json)
+ /head/<nick>/<size> (skin head as chosen in game, also /skin) + /api/login, /api/me, /api/logout
  (personal page, login with «Это я» in the bot) + /api/bans (LibertyBans), see account.py.

Usage: python server.py [--port 8080] [--mc 127.0.0.1:25565] [--map 127.0.0.1:8100]
                        [--webtrade /opt/mc/survival/plugins/WebTrade]
                        [--duels /opt/mc/survival/plugins/BeezzDuels/duels.json]
On the VPS nginx can take over static files and /map/; this script is enough to start.
"""
import argparse, json, os, socket, struct, time, urllib.error, urllib.request
from http.cookies import SimpleCookie
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # run with -I: our own modules are still found
import account  # noqa: E402

ROOT = os.path.dirname(os.path.abspath(__file__))
HOP = {"connection", "keep-alive", "transfer-encoding", "te", "trailer", "upgrade", "proxy-authenticate",
       "proxy-authorization"}


def varint(n):
    out = b""
    while True:
        b, n = n & 0x7F, n >> 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def read_varint(sock):
    n = shift = 0
    while True:
        b = sock.recv(1)
        if not b:
            raise ConnectionError("closed")
        n |= (b[0] & 0x7F) << shift
        shift += 7
        if not b[0] & 0x80:
            return n


def ping(host, port):
    """Minecraft Server List Ping: returns version, players and latency."""
    t0 = time.perf_counter()
    with socket.create_connection((host, port), timeout=3) as s:
        hs = b"\x00" + varint(-1 & 0xFFFFFFFF) + varint(len(host)) + host.encode() + struct.pack(">H", port) + varint(1)
        s.sendall(varint(len(hs)) + hs + b"\x01\x00")
        read_varint(s), read_varint(s)
        size, data = read_varint(s), b""
        while len(data) < size:
            data += s.recv(size - len(data))
    info = json.loads(data)
    return {"online": True, "latency_ms": round((time.perf_counter() - t0) * 1000),
            "players": info.get("players", {}), "version": info.get("version", {}).get("name", "")}


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=ROOT, **kw)

    # ---------------------------------------------------------------- accounts

    def client_ip(self):
        # behind nginx the real address comes in X-Forwarded-For (only trusted from localhost)
        fwd = self.headers.get("X-Forwarded-For", "")
        if fwd and self.client_address[0] in ("127.0.0.1", "::1"):
            return fwd.split(",")[0].strip()
        return self.client_address[0]

    def token(self):
        c = SimpleCookie(self.headers.get("Cookie", ""))
        return c["bz"].value if "bz" in c else None

    def json_reply(self, code, obj, cookie=None):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        if cookie is not None:
            secure = "; Secure" if self.headers.get("X-Forwarded-Proto") == "https" else ""
            age = account.SESSION_LIFE if cookie else 0
            self.send_header("Set-Cookie", f"bz={cookie}; Path=/; Max-Age={age}; HttpOnly; SameSite=Lax{secure}")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        path = self.path.split("?")[0]
        try:
            body = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length") or 0), 4096)) or b"{}")
        except ValueError:
            body = {}
        if path == "/api/login":
            name = str(body.get("name", "")).strip()
            if not name or len(name) > 32 or not all(ch.isalnum() or ch in "_." for ch in name):
                return self.json_reply(400, {"error": "bad_name"})
            code, r = account.login_start(name, self.client_ip())
            return self.json_reply(code, r)
        if path == "/api/logout":
            account.end_session(self.token(), everywhere=bool(body.get("all")))
            return self.json_reply(200, {"ok": True}, cookie="")
        self.send_error(404)

    def account_get(self, path, query):
        if path.startswith("/head/"):
            parts = path[len("/head/"):].split("/")
            size = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 64
            self.send_response(302)
            self.send_header("Location", account.head_url(urllib.parse.unquote(parts[0]), size))
            self.send_header("Cache-Control", "max-age=300")  # a new /skin shows up within minutes
            self.end_headers()
            return True
        if path == "/api/login/status":
            r = account.login_status(query.get("request", ""))
            if r.get("state") == "approved":
                return self.json_reply(200, r, cookie=account.new_session(r["name"], self.client_ip())) or True
            return self.json_reply(200, r) or True
        if path == "/api/me":
            name = account.session_name(self.token())
            if not name:
                return self.json_reply(401, {"error": "login"}) or True
            return self.json_reply(200, account.profile(name)) or True
        if path == "/api/bans":
            try:
                rows = account.bans()
            except Exception as e:  # database down: say so instead of a broken page
                print("bans:", e)
                rows = None
            if rows is None:
                return self.json_reply(503, {"error": "bans_unavailable"}) or True
            return self.json_reply(200, {"updated": int(time.time() * 1000), "items": rows}) or True
        return False

    def do_GET(self):
        path, _, qs = self.path.partition("?")
        if self.account_get(path, dict(urllib.parse.parse_qsl(qs))):
            return
        if self.path.startswith("/api/status"):
            try:
                body = ping(*MC)
            except OSError:
                body = {"online": False}
            data = json.dumps(body).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        elif self.path == "/map":
            self.send_response(301)
            self.send_header("Location", "/map/")
            self.end_headers()
        elif self.path.startswith("/map/"):
            self.proxy_map()
        elif self.path.split("?")[0] == "/api/duels":
            self.send_duels()
        elif self.path.split("?")[0] == "/webtrade/calculator.html":
            self.send_calculator()
        else:
            super().do_GET()

    def end_headers(self):
        # pages change with every update of the site: the browser must re-check them, not use a stale copy
        if self.path.split("?")[0].endswith((".html", "/")) and "Cache-Control" not in "".join(map(str, getattr(self, "_headers_buffer", []))):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def send_calculator(self):
        try:
            data = open(os.path.join(WEBTRADE, "calculator.html"), "rb").read()
        except OSError:
            self.send_error(503, "Calculator is not exported yet")
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")  # prices change when the plugin re-exports
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_duels(self):
        try:
            data = open(DUELS, "rb").read()
        except OSError:
            self.send_error(503, "Duels are not exported yet")
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def proxy_map(self):
        url = f"http://{MAP[0]}:{MAP[1]}/" + self.path[len("/map/"):]
        req = urllib.request.Request(url, headers={"Accept-Encoding": self.headers.get("Accept-Encoding", "")})
        try:
            r = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as e:
            r = e
        except OSError:
            self.send_error(502, "Map is not running")
            return
        if r.headers.get("Content-Type", "").startswith("text/event-stream"):
            # Pl3xMap live updates (players, markers): pass the stream through as it comes;
            # if it goes quiet past the timeout, the browser's EventSource just reconnects
            self.send_response(r.status)
            for k, v in r.headers.items():
                if k.lower() not in HOP and k.lower() != "content-length":
                    self.send_header(k, v)
            self.end_headers()
            try:
                while chunk := r.read1(8192):
                    self.wfile.write(chunk)
                    self.wfile.flush()
            except OSError:
                pass
            self.close_connection = True
            return
        body = r.read()
        html = r.headers.get("Content-Type", "").startswith("text/html")
        self.send_response(r.status if hasattr(r, "status") else r.code)
        for k, v in r.headers.items():
            if k.lower() not in HOP and k.lower() != "content-length" and not (html and k.lower() == "cache-control"):
                self.send_header(k, v)
        if html:
            # the map app's page must always be revalidated: after switching map plugins
            # browsers otherwise keep showing the old app from their cache
            self.send_header("Cache-Control", "no-cache")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0", help="127.0.0.1 behind nginx")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--mc", default="127.0.0.1:25565")
    ap.add_argument("--map", default="127.0.0.1:8100")
    ap.add_argument("--webtrade", default="/opt/mc/survival/plugins/WebTrade")
    ap.add_argument("--duels", default="/opt/mc/survival/plugins/BeezzDuels/duels.json")
    ap.add_argument("--db", default="/opt/mc/survival/plugins/BeezzDuels/zhuzhiki.db", help="BeezzDuels database (read only)")
    ap.add_argument("--auth", default="http://127.0.0.1:8766", help="BeezzAuth local API")
    ap.add_argument("--auth-secret", default="/opt/mc/proxy/plugins/beezzauth/api-secret.txt")
    ap.add_argument("--sessions", default=os.path.join(ROOT, "..", "sessions.json"), help="logged-in sessions (token hashes)")
    ap.add_argument("--bans", default=os.environ.get("BANS_DSN", ""), help="mysql://user:pass@localhost/db of LibertyBans")
    a = ap.parse_args()
    WEBTRADE = a.webtrade
    DUELS = a.duels
    account.setup(a.db, a.auth, a.auth_secret, a.sessions, a.bans)
    MC = (a.mc.split(":")[0], int(a.mc.split(":")[1]))
    MAP = (a.map.split(":")[0], int(a.map.split(":")[1]))
    print(f"BeezzLand site: http://localhost:{a.port}  (minecraft {a.mc}, map {a.map})")
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()
