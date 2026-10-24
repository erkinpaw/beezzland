"""Read a .sparkprofile (spark --save-to-file) without spark's viewer, nothing is uploaded.

    python sparkprofile.py <file.sparkprofile> [top=40]

Prints, for the main server thread, the share of time spent inside (inclusive) a few well-known parts
of a tick, and the methods with the most time of their own (self). Generic protobuf decoding:
SamplerData.threads(2) → ThreadNode {name 1, children 3, times 4, children_refs 5};
StackTraceNode {time 1, children 2, class 3, method 4, times 8, children_refs 9}.
"""
import struct, sys
from collections import defaultdict


def fields(buf):
    i, n = 0, len(buf)
    while i < n:
        key, i = varint(buf, i)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(buf, i)
        elif wt == 1:
            v = struct.unpack_from("<d", buf, i)[0]; i += 8
        elif wt == 2:
            ln, i = varint(buf, i); v = buf[i:i + ln]; i += ln
        elif wt == 5:
            v = struct.unpack_from("<f", buf, i)[0]; i += 4
        else:
            raise ValueError(f"wire type {wt}")
        yield num, wt, v


def varint(buf, i):
    r = s = 0
    while True:
        b = buf[i]; i += 1
        r |= (b & 0x7F) << s; s += 7
        if not b & 0x80:
            return r, i


def packed(v, wt, kind):
    if wt != 2:
        return [v]
    if kind == "d":
        return list(struct.unpack(f"<{len(v) // 8}d", v))
    out, i = [], 0
    while i < len(v):
        x, i = varint(v, i); out.append(x)
    return out


def node(buf):
    d = {"time": 0.0, "times": [], "children": [], "refs": [], "cls": "", "meth": ""}
    for num, wt, v in fields(buf):
        if num == 1 and wt == 1: d["time"] = v
        elif num == 2: d["children"].append(node(v))
        elif num == 3: d["cls"] = v.decode(errors="replace")
        elif num == 4: d["meth"] = v.decode(errors="replace")
        elif num == 8: d["times"] += packed(v, wt, "d")
        elif num == 9: d["refs"] += packed(v, wt, "i")
    d["t"] = sum(d["times"]) if d["times"] else d["time"]
    return d


def thread(buf):
    t = {"name": "", "nodes": [], "times": [], "refs": []}
    for num, wt, v in fields(buf):
        if num == 1: t["name"] = v.decode(errors="replace")
        elif num == 3: t["nodes"].append(node(v))
        elif num == 4: t["times"] += packed(v, wt, "d")
        elif num == 5: t["refs"] += packed(v, wt, "i")
    return t


GROUPS = [  # (label, substring of "class.method") — inclusive time
    ("entities (tickNonPassenger)", "ServerLevel.tickNonPassenger"),
    ("chunks (tickChunks)", "ServerLevel.tickChunks"),
    ("  mob spawning", "NaturalSpawner.spawnForChunk"),
    ("  random ticks", "ServerLevel.tickChunk"),
    ("block ticks (scheduled)", "LevelTicks.tick"),
    ("block entities (hoppers…)", "Level.tickBlockEntities"),
    ("players (connections)", "ServerConnectionListener.tick"),
    ("chunk loading/sending", "ChunkMap"),
    ("villager brains", "Villager.customServerAiStep"),
    ("mob AI (goals)", "GoalSelector.tick"),
    ("pathfinding", "PathFinder.findPath"),
    ("locator bar", "WaypointManager"),
    ("redstone", "RedStoneWireBlock"),
    ("item merge/tick", "ItemEntity.tick"),
    ("GC/wait (sleep)", "MinecraftServer.waitUntilNextTick"),
]


def main():
    data = open(sys.argv[1], "rb").read()
    top = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    threads = [thread(v) for num, wt, v in fields(data) if num == 2]
    main_t = [t for t in threads if t["name"].startswith("Server thread")]
    th = main_t[0] if main_t else max(threads, key=lambda t: sum(n["t"] for n in t["nodes"]))
    nodes = th["nodes"]
    # flat format: nodes hold children_refs; nested (old) format: children inline — flatten it
    if not any(n["refs"] for n in nodes) and any(n["children"] for n in nodes):
        flat, roots = [], []
        def add(n):
            idx = len(flat); flat.append(n)
            n["refs"] = [add(c) for c in n["children"]]
            return idx
        roots = [add(n) for n in nodes]
        nodes = flat
    else:
        roots = th["refs"] or list(range(len(nodes)))
    total = sum(th["times"]) if th["times"] else sum(nodes[r]["t"] for r in roots)
    print(f"thread {th['name']!r}, total {total / 1000:.1f} s sampled, {len(nodes)} nodes")

    incl = defaultdict(float)
    selfs = defaultdict(float)

    def walk(i, stack):
        n = nodes[i]
        name = f"{n['cls'].split('.')[-1]}.{n['meth']}"
        child_t = sum(nodes[c]["t"] for c in n["refs"])
        selfs[name] += max(0.0, n["t"] - child_t)
        new = set()
        for label, sub in GROUPS:
            if sub in f"{n['cls']}.{n['meth']}" or sub in name:
                if label not in stack:
                    incl[label] += n["t"]; new.add(label)
        for c in n["refs"]:
            walk(c, stack | new)

    sys.setrecursionlimit(100000)
    for r in roots:
        walk(r, frozenset())
    print("\ninclusive share of the main thread:")
    for label, _ in GROUPS:
        if incl[label]:
            print(f"  {label:32s} {incl[label] / total * 100:5.1f}%")
    print(f"\ntop {top} self time:")
    for name, t in sorted(selfs.items(), key=lambda x: -x[1])[:top]:
        print(f"  {t / total * 100:5.1f}%  {name}")


if __name__ == "__main__":
    main()


def callers(path, needle, depth=6, top=12):
    """Who spends time in methods matching `needle`: the call chains above them, heaviest first."""
    data = open(path, "rb").read()
    threads = [thread(v) for num, wt, v in fields(data) if num == 2]
    th = next(t for t in threads if t["name"].startswith("Server thread"))
    nodes, total = th["nodes"], sum(th["times"])
    chains = defaultdict(float)
    sys.setrecursionlimit(100000)

    def walk(i, stack):
        n = nodes[i]
        name = f"{n['cls'].split('.')[-1]}.{n['meth']}"
        if needle in name:
            chains[" < ".join(reversed(stack[-depth:]))] += n["t"]
            return
        for c in n["refs"]:
            walk(c, stack + [name])
    for r in th["refs"]:
        walk(r, [])
    for chain, t in sorted(chains.items(), key=lambda x: -x[1])[:top]:
        print(f"{t / total * 100:5.1f}%  {chain}")
