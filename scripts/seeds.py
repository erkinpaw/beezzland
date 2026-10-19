"""New secret seeds for a new season (run by wipe.sh while the server is stopped, before the new world is made).

Usage: python3 seeds.py /opt/mc/survival

Why: tools like SeedCracker recover a world seed from structure positions, slime chunks, dungeons,
ores and the bedrock pattern; with the seed anyone can see every diamond and structure on the map.
- level-seed: 64 bits from the OS random (vanilla's own pick is based on the clock, easier to guess);
- spigot.yml seed-*: every structure type and slime chunks get their own random salt, so structure
  positions say nothing about the world seed;
- paper feature seeds: every ore / tree / dungeon feature gets its own random seed;
- the bedrock pattern is re-rolled by BeezzWorlds in every new chunk.
The values are never printed: nobody, including staff, needs to know them.
"""
import os, re, secrets, sys

SURV = sys.argv[1]


def edit(path, fn):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(fn(text))


def server_properties(t):
    seed = secrets.randbits(64) - (1 << 63)  # a signed long like Minecraft's
    return re.sub(r"^level-seed=.*$", f"level-seed={seed}", t, flags=re.M)


def spigot(t):
    # every seed-xxx key (structures, slime chunks), including the ones left at «default»
    n = [0]

    def repl(m):
        n[0] += 1
        return f"{m.group(1)}{secrets.randbelow(2**31 - 1) + 1}"
    t = re.sub(r"^(\s+seed-[a-z]+: ).*$", repl, t, flags=re.M)
    if n[0] < 15:
        raise SystemExit(f"spigot.yml: only {n[0]} seed-* keys found, expected ~22 — check the file")
    return t


def paper(t):
    # turn on random feature seeds and drop the old list so Paper makes a new one
    t = re.sub(r"^(\s+generate-random-seeds-for-all: ).*$", r"\1true", t, flags=re.M)
    return re.sub(r"(^feature-seeds:\n(?:\s+generate-random-seeds-for-all:.*\n))(?:\s+.*\n)*?(?=^\S)", r"\1", t, flags=re.M)


edit(os.path.join(SURV, "server.properties"), server_properties)
edit(os.path.join(SURV, "spigot.yml"), spigot)
edit(os.path.join(SURV, "config", "paper-world-defaults.yml"), paper)
print("New secret seeds set: level-seed, structure salts, feature seeds.")
