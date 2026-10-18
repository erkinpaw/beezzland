"""Download plugins listed in plugins.json from Modrinth.

Usage: python3 fetch_plugins.py <backend|proxy> <dest_dir>

Picks the newest release built for Minecraft from plugins.json; if a plugin has no
build tagged for that version, falls back to its newest build and says so.
Writes <dest_dir>/plugins.lock.json with exact versions and hashes.
"""
import hashlib, json, os, sys, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
API = "https://api.modrinth.com/v2"
LOADERS = {
    "backend": ["purpur", "paper", "bukkit", "spigot"],
    "proxy": ["velocity"],
}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "mc-server-setup (claude)"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def versions(slug, loaders, mc=None):
    q = {"loaders": json.dumps(loaders)}
    if mc:
        q["game_versions"] = json.dumps([mc])
    return json.loads(get(f"{API}/project/{slug}/version?{urllib.parse.urlencode(q)}"))


def pick(vs, loaders):
    """Newest build of the most stable type; if a plugin ships one jar per platform
    (BlueMap: -spigot / -paper), take the one for the best loader in `loaders`."""
    rank = lambda v: min((loaders.index(l) for l in v["loaders"] if l in loaders), default=99)
    day = lambda v: v["date_published"][:10]
    for kind in ("release", "beta", "alpha"):
        same = [v for v in vs if v["version_type"] == kind]
        if same:
            newest = [v for v in same if day(v) == day(same[0])]
            return min(newest, key=rank)
    return None


def main():
    kind, dest = sys.argv[1], sys.argv[2]
    cfg = json.load(open(os.path.join(HERE, "..", "plugins.json"), encoding="utf-8"))
    mc = cfg["minecraft"]
    os.makedirs(dest, exist_ok=True)
    lock, problems = [], []
    for p in cfg[kind]:
        slug = p["slug"]
        v = pick(versions(slug, LOADERS[kind], mc), LOADERS[kind])
        tagged = v is not None
        if not tagged:
            v = pick(versions(slug, LOADERS[kind]), LOADERS[kind])
        if v is None:
            problems.append(f"{slug}: no build for {LOADERS[kind]}")
            continue
        f = next((f for f in v["files"] if f["primary"]), v["files"][0])
        data = get(f["url"])
        if hashlib.sha512(data).hexdigest() != f["hashes"]["sha512"]:
            raise SystemExit(f"{slug}: sha512 mismatch, aborting")
        with open(os.path.join(dest, f["filename"]), "wb") as out:
            out.write(data)
        note = "" if tagged else f"  (!) no {mc} tag, newest is for {v['game_versions'][-1]}"
        print(f"{slug:16} {v['version_number']:28} {v['version_type']}{note}")
        if not tagged:
            problems.append(f"{slug}: not tagged for {mc}, verify it loads")
        lock.append({"slug": slug, "version": v["version_number"], "type": v["version_type"],
                     "file": f["filename"], "sha512": f["hashes"]["sha512"], "tagged_for_mc": tagged})
    with open(os.path.join(dest, "plugins.lock.json"), "w", encoding="utf-8") as out:
        json.dump(lock, out, indent=2, ensure_ascii=False)
    for line in problems:
        print("WARN", line)


if __name__ == "__main__":
    main()
