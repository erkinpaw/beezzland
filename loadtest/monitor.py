"""Poll TPS / MSPT / players over RCON every 10 s and log them to loadtest.csv,
plus what the server process really uses: CPU (in cores: 1.0 = one core busy) and resident memory.

Usage: MC_BASE=/opt/mc python3 monitor.py [seconds=600] [out=loadtest.csv]
The server process is found by its command line (LT_PROC, a -like pattern, default "*server.jar*").
"""
import csv, os, re, sys, time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import rcon  # noqa: E402

BASE = os.environ.get("MC_BASE", "/opt/mc")
dur = int(sys.argv[1]) if len(sys.argv) > 1 else 600
out_name = sys.argv[2] if len(sys.argv) > 2 else "loadtest.csv"
strip = lambda s: re.sub(r"§.", "", s)


FOLIA = {}


def query():
    import json, socket
    s = json.load(open(os.path.join(BASE, ".secrets.json")))
    with socket.create_connection(("127.0.0.1", int(s.get("rcon_port", 25575))), timeout=10) as sock:
        rcon.packet(sock, 1, 3, s["rcon_password"])
        tps = strip(rcon.packet(sock, 2, 2, "tps")[1])
        mspt = strip(rcon.packet(sock, 2, 2, "mspt")[1])
        lst = strip(rcon.packet(sock, 2, 2, "list")[1])
    players = int(re.search(r"(\d+)", lst).group(1))
    if "Health Report" in tps:
        # Folia: no single main thread. tps = the slowest region; "mspt" = all tick work in ms per 50 ms
        # (utilisation 124% → 62 ms, the same total work a Purpur MSPT of 62 would be); max = busiest region
        num = lambda key: float(re.search(key + r"[^\d]*(\d+(?:[.,]\d+)?)", tps).group(1).replace(",", "."))
        util = num("Utilisation:")
        FOLIA.update(median=num("Median Region TPS:"), util=util, regions=int(num("Total regions:")))
        top = [float(x.replace(",", ".")) for x in re.findall(r"util at (\d+(?:[.,]\d+)?) MSPT", tps)]
        return num("Lowest Region TPS:"), round(util / 2, 1), max(top) if top else None, players
    t = [float(x) for x in re.findall(r"\*?(\d+(?:[.,]\d+)?)", tps.split(":", 1)[-1].replace(",", "."))[:3]]
    m = re.findall(r"(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)/(\d+(?:[.,]\d+)?)", mspt.replace(",", "."))
    return t[0] if t else None, float(m[0][0]) if m else None, float(m[0][2]) if m else None, players


def proc_usage():
    """(cpu seconds so far, resident MB) of the server JVM."""
    import subprocess
    marker = os.environ.get("LT_PROC", "*server.jar*")
    if os.name == "nt":
        ps = ("$p = Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '%s' } | Select-Object -First 1;"
              "$g = Get-Process -Id $p.ProcessId; Write-Output ($g.TotalProcessorTime.TotalSeconds.ToString([cultureinfo]::InvariantCulture) + ' ' + $g.WorkingSet64)") % marker
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True, timeout=20).stdout.split()
    else:
        pid = subprocess.run(["pgrep", "-f", marker.strip("*")], capture_output=True, text=True).stdout.split()[0]
        st = open(f"/proc/{pid}/stat").read().split(")")[-1].split()
        tck = os.sysconf("SC_CLK_TCK")
        out = [(int(st[11]) + int(st[12])) / tck, int(open(f"/proc/{pid}/statm").read().split()[1]) * os.sysconf("SC_PAGE_SIZE")]
    return float(out[0]), int(out[1]) / 2**20


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    out = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), out_name), "w", newline="")
    w = csv.writer(out)
    w.writerow(["time_s", "players", "tps_1m", "mspt_avg_5s", "mspt_max_5s", "cpu_cores", "rss_mb",
                "folia_median_tps", "folia_util_pct", "folia_regions"])
    t0 = time.time()
    last = None
    while time.time() - t0 < dur:
        try:
            tps, avg, mx, players = query()
            cpu, rss = proc_usage()
            now = time.time()
            cores = round((cpu - last[0]) / (now - last[1]), 2) if last else ""
            last = (cpu, now)
            line = [round(now - t0), players, tps, avg, mx, cores, round(rss),
                    FOLIA.get("median", ""), FOLIA.get("util", ""), FOLIA.get("regions", "")]
            w.writerow(line); out.flush()
            print(f"[server] t={line[0]:>4}s players={players:>3} tps={tps} mspt avg={avg} max={mx} cpu={cores} cores rss={round(rss)} MB", flush=True)
        except Exception as e:  # server busy or restarting: keep polling
            print(f"[server] poll failed: {e}", flush=True)
        time.sleep(10)


if __name__ == "__main__":
    main()
