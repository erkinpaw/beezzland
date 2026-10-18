"""Fake VK API (methods + Bots Long Poll) for testing BeezzAuth's VK bot locally.

Bot side: POST /method/<name> (form), GET /lp?act=a_check&key=&ts=&wait=
Test side: POST /test/inject {"type": ..., "object": {...}}, GET /test/sent -> all method calls so far.
"""
import json, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = 8098
lock = threading.Condition()
events, sent = [], []


class H(BaseHTTPRequestHandler):
    def _json(self, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/test/sent":
            with lock:
                return self._json(sent)
        if u.path == "/lp":
            q = parse_qs(u.query)
            ts = int(q["ts"][0])
            end = time.time() + min(int(q.get("wait", ["0"])[0]), 25)
            with lock:
                while len(events) <= ts and time.time() < end:
                    lock.wait(end - time.time())
                return self._json({"ts": str(len(events)), "updates": events[ts:]})
        self._json({"error": {"error_msg": "not found"}})

    def do_POST(self):
        u = urlparse(self.path)
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode()
        if u.path == "/test/inject":
            with lock:
                events.append(json.loads(raw))
                lock.notify_all()
            return self._json({"ok": True})
        method = u.path.rsplit("/", 1)[-1]
        params = {k: v[0] for k, v in parse_qs(raw).items()}
        params.pop("access_token", None)
        with lock:
            sent.append({"method": method, "params": params})
        if method == "groups.getLongPollServer":
            with lock:
                ts = len(events)
            return self._json({"response": {"server": f"http://127.0.0.1:{PORT}/lp", "key": "k", "ts": str(ts)}})
        self._json({"response": 1})

    def log_message(self, *a):
        pass


ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
