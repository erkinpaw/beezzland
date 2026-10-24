"""Fake Discord (REST + Gateway WebSocket) for testing BeezzAuth's Discord bot. Standard library only.

Bot side:  GET /api/gateway/bot, GET guilds/channels/messages (one #регистрация), POST /api/users/@me/channels, POST /api/channels/<id>/messages,
           POST /api/interactions/<id>/<token>/callback, WebSocket on /ws (Hello → Identify → READY).
Test side: POST /test/inject {"t": "MESSAGE_CREATE", "d": {...}}, GET /test/sent, GET /test/identify.
"""
import base64, hashlib, json, queue, socket, struct, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8097
lock = threading.Lock()
sent, identifies, clients = [], [], []
seq = [0]


def ws_send(sock, text):
    data = text.encode()
    head = bytes([0x81])
    head += bytes([len(data)]) if len(data) < 126 else bytes([126]) + struct.pack(">H", len(data))
    sock.sendall(head + data)


def ws_recv(sock):
    """Read one client frame (always masked). Returns (opcode, text) or None when closed."""
    hdr = sock.recv(2)
    if len(hdr) < 2:
        return None
    op, ln = hdr[0] & 0x0F, hdr[1] & 0x7F
    if ln == 126:
        ln = struct.unpack(">H", sock.recv(2))[0]
    elif ln == 127:
        ln = struct.unpack(">Q", sock.recv(8))[0]
    mask = sock.recv(4)
    data = b""
    while len(data) < ln:
        data += sock.recv(ln - len(data))
    return op, bytes(b ^ mask[i % 4] for i, b in enumerate(data)).decode(errors="replace")


def dispatch(t, d):
    with lock:
        seq[0] += 1
        msg = json.dumps({"op": 0, "t": t, "s": seq[0], "d": d}, ensure_ascii=False)
        for q in clients:
            q.put(msg)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _json(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/ws") and self.headers.get("Upgrade", "").lower() == "websocket":
            return self.websocket()
        if self.path == "/api/gateway/bot":
            return self._json({"url": f"ws://127.0.0.1:{PORT}/ws"})
        with lock:
            if self.path == "/test/sent":
                return self._json(sent)
            if self.path == "/test/identify":
                return self._json(identifies)
            # the registration channel lookup: one server with #регистрация; its messages are what the bot posted
            if self.path == "/api/users/@me/guilds":
                return self._json([{"id": "1"}])
            if self.path == "/api/guilds/1/channels":
                return self._json([{"id": "chat", "type": 0, "name": "общий"}, {"id": "reg", "type": 0, "name": "регистрация"}])
            if self.path.startswith("/api/channels/reg/messages"):
                return self._json([{"author": {"id": "999"}, **x["body"]} for x in sent if x["path"] == "/api/channels/reg/messages"])
        self._json({"message": "404"}, 404)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode() or "{}"
        if self.path == "/test/inject":
            e = json.loads(body)
            dispatch(e["t"], e["d"])
            return self._json({"ok": True})
        with lock:
            sent.append({"path": self.path, "auth": self.headers.get("Authorization"), "body": json.loads(body)})
        if self.path == "/api/users/@me/channels":
            return self._json({"id": "dm-" + json.loads(body)["recipient_id"], "type": 1})
        if "/callback" in self.path:
            self.send_response(204)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self._json({"id": str(len(sent))})

    def websocket(self):
        key = self.headers["Sec-WebSocket-Key"]
        accept = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        sock = self.connection
        out = queue.Queue()
        out.put(json.dumps({"op": 10, "d": {"heartbeat_interval": 3000}}))
        with lock:
            clients.append(out)
        alive = [True]

        def writer():
            while alive[0]:
                try:
                    ws_send(sock, out.get(timeout=1))
                except queue.Empty:
                    continue
                except OSError:
                    return

        threading.Thread(target=writer, daemon=True).start()
        try:
            while True:
                frame = ws_recv(sock)
                if frame is None or frame[0] == 8:
                    break
                p = json.loads(frame[1])
                if p["op"] == 2:
                    with lock:
                        identifies.append(p["d"])
                    seq[0] += 1
                    out.put(json.dumps({"op": 0, "t": "READY", "s": seq[0], "d": {"user": {"id": "999", "username": "BeezzTestBot"}}}))
                elif p["op"] == 1:
                    out.put(json.dumps({"op": 11}))  # heartbeat ACK
        except (OSError, ValueError):
            pass
        finally:
            alive[0] = False
            with lock:
                clients.remove(out)
        self.close_connection = True

    def log_message(self, *a):
        pass


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
