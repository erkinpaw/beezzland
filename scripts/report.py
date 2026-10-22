"""Daily BeezzLand report in Markdown: what Claude reads every morning (and can post to the team).

Usage: python3 report.py [--hours 24]          (MC_BASE defaults to /opt/mc)
Everything is read-only: RCON queries, systemd/journal, files on disk. Sections whose source
is missing (e.g. on a dev PC without systemd) say so instead of failing.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

BASE = os.environ.get("MC_BASE", "/opt/mc")
SERVICES = ["mc-proxy", "mc-survival", "mc-site", "mariadb"]


def sh(*cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=30).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None


def strip(s):
    return re.sub(r"§.", "", s).strip()


def server_now():
    s = json.load(open(os.path.join(BASE, ".secrets.json")))
    with socket.create_connection(("127.0.0.1", int(s.get("rcon_port", 25575))), timeout=10) as sock:
        rcon.packet(sock, 1, 3, s["rcon_password"])
        out = {c: strip(rcon.packet(sock, 2, 2, c)[1]) for c in ("list", "tps", "mspt", "beezz status")}
    online = re.search(r"(\d+) of a max of (\d+)", out["list"])
    tps = [float(x.replace(",", ".")) for x in re.findall(r"(\d+[.,]\d+)", out["tps"].split(":", 1)[-1])][:4]
    mspt = re.findall(r"(\d+[.,]\d+)/(\d+[.,]\d+)/(\d+[.,]\d+)", out["mspt"])
    worlds = re.findall(r"(Незер|Мир ферм|Энд): (открыт|закрыт)", out["beezz status"])
    return online, tps, mspt, worlds


def services(hours):
    rows = []
    for svc in SERVICES:
        state = sh("systemctl", "is-active", svc)
        if state is None:
            return None
        restarts = (sh("systemctl", "show", svc, "-p", "NRestarts", "--value") or "?").strip()
        rows.append((svc, state.strip(), restarts))
    since = f"-{hours}h"
    log = sh("journalctl", "-u", "mc-survival", "-u", "mc-proxy", "--since", since, "--no-pager", "-o", "cat") or ""
    crashes = len(re.findall(r"has not responded for|OutOfMemoryError|This crash report has been saved", log))
    lagged = len(re.findall(r"Can't keep up!", log))
    return rows, crashes, lagged


def backups():
    d = os.path.join(BASE, "backups")
    if not os.path.isdir(d):
        return None
    files = sorted((os.path.getmtime(os.path.join(d, f)), f) for f in os.listdir(d) if f.endswith(".zst"))
    total = sum(os.path.getsize(os.path.join(d, f)) for _, f in files)
    return files[-1] if files else None, total


def auth(hours):
    p = os.path.join(BASE, "proxy", "plugins", "beezzauth", "accounts.json")
    if not os.path.exists(p):
        return None
    accs = json.load(open(p, encoding="utf-8"))
    cutoff = (time.time() - hours * 3600) * 1000
    new = [a["name"] for a in accs.values() if a.get("created", 0) >= cutoff]
    by = {}
    for a in accs.values():
        for prov in a.get("links", {}):
            by[prov] = by.get(prov, 0) + 1
    return len(accs), new, by


def gb(n):
    return f"{n / 1024 ** 3:.1f} ГБ"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hours", type=int, default=24)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    now = datetime.now()
    print(f"# BeezzLand — отчёт за {a.hours} ч ({now:%d.%m.%Y %H:%M})\n")

    print("## Сервер сейчас")
    try:
        online, tps, mspt, worlds = server_now()
        if online:
            print(f"- Онлайн: **{online.group(1)}** из {online.group(2)}")
        if tps:
            flag = "✅" if tps[0] >= 19 else ("⚠️" if tps[0] >= 15 else "🔴")
            print(f"- TPS {flag} {tps[0]:.1f} сейчас · {tps[1]:.1f} за минуту" + (f" · {tps[3]:.1f} за 15 мин" if len(tps) > 3 else ""))
        if mspt:
            print(f"- MSPT (среднее/мин/макс за 5 с): {'/'.join(mspt[0])} мс")
        if worlds:
            print("- Миры: " + ", ".join(f"{w} — {s}" for w, s in worlds))
    except (OSError, KeyError, ValueError) as e:
        print(f"- 🔴 Сервер не отвечает по RCON: {e}")

    print("\n## Стабильность")
    sv = services(a.hours)
    if sv is None:
        print("- systemd недоступен (не VPS?) — пропущено")
    else:
        rows, crashes, lagged = sv
        for svc, state, restarts in rows:
            print(f"- `{svc}`: {'✅' if state == 'active' else '🔴'} {state}, перезапусков: {restarts}")
        print(f"- Зависаний/крашей в логах: **{crashes}**, предупреждений «Can't keep up»: {lagged}")

    print("\n## Ресурсы")
    du = shutil.disk_usage(BASE if os.path.exists(BASE) else "/")
    print(f"- Диск: свободно {gb(du.free)} из {gb(du.total)}" + (" ⚠️ мало места" if du.free < 5 * 1024 ** 3 else ""))
    mem = sh("free", "-b")
    if mem:
        total, used = map(int, mem.splitlines()[1].split()[1:3])
        print(f"- Память: занято {gb(used)} из {gb(total)}")
    bk = backups()
    if bk is None:
        print("- Бэкапы: папки нет")
    else:
        last, total = bk
        if last:
            age = timedelta(seconds=time.time() - last[0])
            fresh = "✅" if age < timedelta(hours=26) else "🔴 старый!"
            print(f"- Последний бэкап: {fresh} `{last[1]}`, {age.seconds // 3600 + age.days * 24} ч назад; всего {gb(total)}")
        else:
            print("- Бэкапы: 🔴 ни одного")

    print("\n## Игроки")
    au = auth(a.hours)
    if au is None:
        print("- BeezzAuth ещё не установлен")
    else:
        total, new, by = au
        print(f"- Привязано ников всего: **{total}** ({', '.join(f'{k}: {v}' for k, v in sorted(by.items()))})")
        print(f"- Новых за {a.hours} ч: **{len(new)}**" + (f" — {', '.join(new[:15])}" + (" …" if len(new) > 15 else "") if new else ""))


if __name__ == "__main__":
    main()
