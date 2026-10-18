"""Fake Telegram Bot API for testing BeezzAuth locally.

Bot side (what the plugin calls): /bot<token>/getUpdates|sendMessage|answerCallbackQuery|editMessageText
Test side: POST /test/inject {update without update_id}, GET /test/sent -> all bot calls so far.
"""
import json, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

lock = threading.Condition()
updates, sent = [], []
next_id = [1]


class H(BaseHTTPRequestHandler):
    def _json(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/test/sent":
            with lock:
                return self._json(sent)
        if u.path.endswith("/getUpdates"):
            q = parse_qs(u.query)
            offset = int(q.get("offset", ["0"])[0])
            timeout = min(int(q.get("timeout", ["0"])[0]), 25)
            end = time.time() + timeout
            with lock:
                while True:
                    res = [x for x in updates if x["update_id"] >= offset]
                    if res or time.time() >= end:
                        return self._json({"ok": True, "result": res})
                    lock.wait(end - time.time())
        self._json({"ok": False}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        body = self._body()
        with lock:
            if u.path == "/test/inject":
                body["update_id"] = next_id[0]
                next_id[0] += 1
                updates.append(body)
                lock.notify_all()
                return self._json({"ok": True})
            method = u.path.rsplit("/", 1)[-1]
            sent.append({"method": method, "body": body})
        result = {"message_id": len(sent)} if method in ("sendMessage", "editMessageText") else True
        self._json({"ok": True, "result": result})

    def log_message(self, *a):
        pass


ThreadingHTTPServer(("127.0.0.1", 8099), H).serve_forever()
