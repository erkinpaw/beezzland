"""BeezzLand site: static files + /api/status (live server ping) + /map/ (proxy to Pl3xMap)
+ /webtrade/calculator.html (the price calculator WebTrade exports with current prices).

Usage: python server.py [--port 8080] [--mc 127.0.0.1:25565] [--map 127.0.0.1:8100]
                        [--webtrade /opt/mc/survival/plugins/WebTrade]
On the VPS nginx can take over static files and /map/; this script is enough to start.
"""
import argparse, json, os, socket, struct, time, urllib.error, urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

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

    def do_GET(self):
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
        elif self.path.split("?")[0] == "/webtrade/calculator.html":
            self.send_calculator()
        else:
            super().do_GET()

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
        self.send_response(r.status if hasattr(r, "status") else r.code)
        for k, v in r.headers.items():
            if k.lower() not in HOP and k.lower() != "content-length":
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--mc", default="127.0.0.1:25565")
    ap.add_argument("--map", default="127.0.0.1:8100")
    ap.add_argument("--webtrade", default="/opt/mc/survival/plugins/WebTrade")
    a = ap.parse_args()
    WEBTRADE = a.webtrade
    MC = (a.mc.split(":")[0], int(a.mc.split(":")[1]))
    MAP = (a.map.split(":")[0], int(a.map.split(":")[1]))
    print(f"BeezzLand site: http://localhost:{a.port}  (minecraft {a.mc}, map {a.map})")
    ThreadingHTTPServer(("0.0.0.0", a.port), Handler).serve_forever()
