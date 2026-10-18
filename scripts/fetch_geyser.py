"""Download Geyser + Floodgate for Velocity from the official GeyserMC site, checking sha256.

Usage: python3 fetch_geyser.py <proxy_plugins_dir>
Lets Bedrock players (phones, consoles, Windows Bedrock) join the Java server; BeezzAuth trusts
Floodgate's Xbox Live check, so they don't need to link a messenger.
"""
import hashlib, json, os, sys, urllib.request

API = "https://download.geysermc.org/v2/projects"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "beezzland-setup"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def main():
    dest = sys.argv[1]
    os.makedirs(dest, exist_ok=True)
    for project in ("geyser", "floodgate"):
        meta = json.loads(get(f"{API}/{project}/versions/latest/builds/latest"))
        d = meta["downloads"]["velocity"]
        data = get(f"{API}/{project}/versions/{meta['version']}/builds/{meta['build']}/downloads/velocity")
        if hashlib.sha256(data).hexdigest() != d["sha256"]:
            raise SystemExit(f"{project}: sha256 mismatch, aborting")
        with open(os.path.join(dest, d["name"]), "wb") as f:
            f.write(data)
        print(f"{project:10} {meta['version']} build {meta['build']} ({meta['time'][:10]})")


if __name__ == "__main__":
    main()
