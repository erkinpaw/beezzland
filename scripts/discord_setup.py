"""Create BeezzLand's Discord structure (roles, categories, channels) from discord/server.json.

Usage:
  DISCORD_TOKEN=… python3 discord_setup.py <guild_id> [--dry-run] [--spec ../discord/server.json]

- Safe to re-run: roles and channels that already exist (same name) are left alone.
- Read-only channels: @everyone can read but not write; staff roles can write.
- staff_only categories are hidden from everyone except staff roles.
- The bot needs "Manage Roles" and "Manage Channels" on the server; its own role must be
  above the roles it creates. The token is read from the environment, never from arguments.
"""
import argparse, json, os, sys, time, urllib.error, urllib.request

API = os.environ.get("DISCORD_API", "https://discord.com/api/v10")
VIEW, SEND, CONNECT = 1 << 10, 1 << 11, 1 << 20
TEXT, VOICE, CATEGORY = 0, 2, 4


class Discord:
    def __init__(self, token, dry):
        self.token, self.dry, self.fake_id = token, dry, 0

    def call(self, method, path, body=None):
        if self.dry and method != "GET":
            self.fake_id += 1
            print(f"  [dry-run] {method} {path} {json.dumps(body, ensure_ascii=False)[:120]}")
            return {"id": f"dry{self.fake_id}", **(body or {})}
        req = urllib.request.Request(API + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Authorization": f"Bot {self.token}", "Content-Type": "application/json",
                                              "User-Agent": "DiscordBot (https://beezzland.ru, 1.0)"})
        for attempt in range(5):
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    data = r.read()
                    return json.loads(data) if data else {}
            except urllib.error.HTTPError as e:
                if e.code == 429:  # rate limited: wait as told and retry
                    time.sleep(float(json.loads(e.read() or b"{}").get("retry_after", 1)) + 0.2)
                    continue
                raise SystemExit(f"Discord {method} {path}: HTTP {e.code} {e.read()[:200]!r}")
        raise SystemExit(f"Discord {method} {path}: still rate limited")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("guild")
    ap.add_argument("--spec", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "discord", "server.json"))
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    token = os.environ.get("DISCORD_TOKEN", "")
    if not token and not a.dry_run:
        raise SystemExit("set DISCORD_TOKEN in the environment")
    spec = json.load(open(a.spec, encoding="utf-8"))
    d = Discord(token, a.dry_run)
    g = a.guild

    print("Roles")
    existing = {r["name"]: r for r in d.call("GET", f"/guilds/{g}/roles")} if not a.dry_run or token else {}
    staff_ids = []
    for r in spec["roles"]:
        if r["name"] in existing:
            role = existing[r["name"]]
            print(f"  = {r['name']}")
        else:
            body = {"name": r["name"], "color": int(r["color"].lstrip("#"), 16), "hoist": r.get("hoist", False),
                    "mentionable": False}
            # no permissions on created roles: Discord refuses (403) to let a bot grant rights it doesn't have,
            # and the server owner has every right anyway; give mods their rights in Discord's role settings
            role = d.call("POST", f"/guilds/{g}/roles", body)
            print(f"  + {r['name']}")
        if r.get("staff"):
            staff_ids.append(role["id"])

    print("Channels")
    chans = {(c["name"], c.get("parent_id")): c for c in d.call("GET", f"/guilds/{g}/channels")} if not a.dry_run or token else {}
    everyone = g  # the @everyone role id equals the guild id

    def ensure(name, body):
        key = (name, body.get("parent_id"))
        if key in chans:
            print(f"  = {name}")
            return chans[key]
        c = d.call("POST", f"/guilds/{g}/channels", body)
        print(f"  + {name}")
        return c

    for cat in spec["categories"]:
        overwrites = []
        if cat.get("staff_only"):
            overwrites = [{"id": everyone, "type": 0, "deny": str(VIEW), "allow": "0"}] + \
                         [{"id": s, "type": 0, "allow": str(VIEW | SEND | CONNECT), "deny": "0"} for s in staff_ids]
        category = ensure(cat["name"], {"name": cat["name"], "type": CATEGORY, "permission_overwrites": overwrites})
        for ch in cat["channels"]:
            body = {"name": ch["name"], "type": VOICE if ch.get("voice") else TEXT, "parent_id": category["id"]}
            if ch.get("topic"):
                body["topic"] = ch["topic"]
            if ch.get("readonly"):
                body["permission_overwrites"] = [{"id": everyone, "type": 0, "deny": str(SEND), "allow": "0"}] + \
                    [{"id": s, "type": 0, "allow": str(SEND), "deny": "0"} for s in staff_ids]
            elif overwrites:
                # channels created over the API don't always inherit the category's permissions: copy them
                body["permission_overwrites"] = overwrites
            ensure(ch["name"], body)
    print("Done.")


if __name__ == "__main__":
    main()
