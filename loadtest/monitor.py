"""Poll TPS / MSPT / players over RCON every 10 s and log them to loadtest.csv.

Usage: MC_BASE=/opt/mc python3 monitor.py [seconds=600] [out=loadtest.csv]
"""
import csv, os, re, sys, time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import rcon  # noqa: E402

BASE = os.environ.get("MC_BASE", "/opt/mc")
dur = int(sys.argv[1]) if len(sys.argv) > 1 else 600
out_name = sys.argv[2] if len(sys.argv) > 2 else "loadtest.csv"
strip = lambda s: re.sub(r"§.", "", s)


def query():
    import json, socket
    s = json.load(open(os.path.join(BASE, ".secrets.json")))
    with socket.create_connection(("127.0.0.1", 25575), timeout=10) as sock:
        rcon.packet(sock, 1, 3, s["rcon_password"])
        tps = strip(rcon.packet(sock, 2, 2, "tps")[1])
        mspt = strip(rcon.packet(sock, 2, 2, "mspt")[1])
        lst = strip(rcon.packet(sock, 2, 2, "list")[1])
    t = [float(x) for x in re.findall(r"\*?(\d+(?:[.,]\d+)?)", tps.split(":", 1)[-1].replace(",", "."))[:3]]
    m = re.findall(r"(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)", mspt.replace(",", "."))
    players = int(re.search(r"(\d+)", lst).group(1))
    return t[0] if t else None, float(m[0][0]) if m else None, float(m[0][2]) if m else None, players


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    out = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), out_name), "w", newline="")
    w = csv.writer(out)
    w.writerow(["time_s", "players", "tps_1m", "mspt_avg_5s", "mspt_max_5s"])
    t0 = time.time()
    while time.time() - t0 < dur:
        try:
            tps, avg, mx, players = query()
            line = [round(time.time() - t0), players, tps, avg, mx]
            w.writerow(line); out.flush()
            print(f"[server] t={line[0]:>4}s players={players:>3} tps={tps} mspt avg={avg} max={mx}", flush=True)
        except Exception as e:  # server busy or restarting: keep polling
            print(f"[server] poll failed: {e}", flush=True)
        time.sleep(10)


if __name__ == "__main__":
    main()
