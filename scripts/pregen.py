"""World borders and pre-generation for every world of a season (first start and after each wipe).

    python3 scripts/pregen.py            set the borders and start Chunky in all worlds (one after another)
    python3 scripts/pregen.py borders    only set the borders

Sizes (PLAN.md): the main world and the farm world 10k×10k (honeycomb portals link them 1:1), both nethers
3k×3k — wider than their overworld ÷ 8 (room for fortresses
and bastions); portals there only open within the overworld ÷ 8 (BeezzWorlds).
Pre-generated to the border: players never wait for new chunks, and nether hyperloops
(boats on blue ice, ~70 blocks/s) don't make the server generate on the fly.
Reads host/port/password like rcon.py (MC_BASE, default /opt/mc).
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

# dimension key, Bukkit world names (the data pack dimensions are "world_minecraft_…"), border size
WORLDS = [
    ("minecraft:overworld", ["world"], 10000),
    ("minecraft:the_nether", ["world_nether"], 3000),
    ("minecraft:the_end", ["world_the_end"], 8000),
    ("minecraft:farm", ["world_minecraft_farm", "farm"], 10000),
    ("minecraft:farm_nether", ["world_minecraft_farm_nether", "farm_nether"], 3000),
]


def main():
    import json, socket
    base = os.environ.get("MC_BASE", "/opt/mc")
    s = json.load(open(os.path.join(base, ".secrets.json")))
    sock = socket.create_connection(("127.0.0.1", int(s.get("rcon_port", 25575))), timeout=30)
    rcon.packet(sock, 1, 3, s["rcon_password"])
    cmd = lambda c: re.sub(r"§.", "", rcon.packet(sock, 2, 2, c)[1]).strip()
    for key, names, size in WORLDS:
        print(key, "border", size, "→", cmd(f"execute in {key} run worldborder center 0 0") or "",
              cmd(f"execute in {key} run worldborder set {size}"))
    if len(sys.argv) > 1 and sys.argv[1] == "borders":
        return
    for key, names, size in WORLDS:
        name = next((n for n in names if "World changed" in cmd(f"chunky world {n}")), None)
        if not name:
            print(key, "— Chunky doesn't know this world, skipped")
            continue
        cmd("chunky center 0 0")
        cmd(f"chunky radius {size // 2}")
        print(key, cmd("chunky start"))
        # one world at a time: all at once would fight for the disk and memory
        time.sleep(10)
        while "no tasks" not in cmd("chunky progress").lower():
            time.sleep(30)
    print("done")


if __name__ == "__main__":
    main()
