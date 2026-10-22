"""Minimal RCON client. Usage: python3 rcon.py "say hello" ["save-all flush" ...]

Reads host/port/password from /opt/mc/.secrets.json (or MC_BASE env).
"""
import json, os, socket, struct, sys

BASE = os.environ.get("MC_BASE", "/opt/mc")


def packet(sock, rid, kind, body):
    data = struct.pack("<ii", rid, kind) + body.encode() + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)
    size = struct.unpack("<i", sock.recv(4))[0]
    buf = b""
    while len(buf) < size:
        buf += sock.recv(size - len(buf))
    return struct.unpack("<i", buf[:4])[0], buf[8:-2].decode(errors="replace")


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    s = json.load(open(os.path.join(BASE, ".secrets.json")))
    with socket.create_connection(("127.0.0.1", int(s.get("rcon_port", 25575))), timeout=30) as sock:
        if packet(sock, 1, 3, s["rcon_password"])[0] == -1:
            raise SystemExit("rcon auth failed")
        for cmd in sys.argv[1:]:
            print(packet(sock, 2, 2, cmd)[1])


if __name__ == "__main__":
    main()
