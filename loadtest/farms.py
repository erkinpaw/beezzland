"""Test farms for the load test, built with commands over RCON, and their output.

    MC_BASE=… python farms.py build      build every farm in FARMS (sky platforms at y=200, nothing to grief)
    MC_BASE=… python farms.py wake       fresh villagers in the iron farms
    MC_BASE=… python farms.py clear      empty their chests
    MC_BASE=… python farms.py count      iron / wool / poppies in the chests now (JSON)

Iron farm — the classic panic design: 3 villagers in a glass pod scared by a zombie they can see,
golems spawn on a stone platform below (they can't spawn on glass), water carries them into a 2-wide trench
(a golem is 1.4 blocks wide), a lava blade held up by wall signs burns them; drops fall into hoppers → chest.
Wool farm — sheep in 1×1 cells on grass, a dispenser with shears at each; a repeater loop fires them
every 1.6 s; wool is picked up through the grass by hopper minecarts → hoppers → chest.
Spawner room — a zombie spawner in a dark box: shows how spawner mobs behave (AI on or off).
"""
import json, os, re, sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import rcon  # noqa: E402

BASE = os.environ.get("MC_BASE", "/opt/mc")
Y = 200

# world key, kind, x, z — far enough apart to be in different chunks; bots stand next to them
FARMS = [
    ("minecraft:farm", "iron", 300, 300),
    ("minecraft:farm", "iron", -300, 300),
    ("minecraft:farm", "wool", 300, -300),
    ("minecraft:farm", "wool", -300, -300),
    ("minecraft:farm", "spawner", 0, 400),
    ("minecraft:overworld", "iron", 600, 600),
    ("minecraft:overworld", "wool", -600, 600),
]


class Rcon:
    def __init__(self):
        import socket
        s = json.load(open(os.path.join(BASE, ".secrets.json")))
        self.sock = socket.create_connection(("127.0.0.1", int(s.get("rcon_port", 25575))), timeout=60)
        if rcon.packet(self.sock, 1, 3, s["rcon_password"])[0] == -1:
            raise SystemExit("rcon auth failed")

    def __call__(self, cmd):
        out = re.sub(r"§.", "", rcon.packet(self.sock, 2, 2, cmd)[1])
        if re.search(r"Unknown|Incorrect|Expected|Invalid|Can't|not loaded|Нельзя|Неизвест", out) and "Items[" not in cmd:
            print("  !", cmd, "->", out[:160])
        return out


def in_world(world, cmd):
    return f"execute in {world} run {cmd}"


def villager(x, y, z, r):
    # no beds needed: BeezzWorlds (Golems.java) keeps every villager's "last slept" fresh
    return f'summon villager {x} {y} {z} {{PersistenceRequired:1b,Tags:["farm"]}}'


def wake(r, w, x, z):
    """Fresh villagers in the pod (e.g. after the zombie got to them in an earlier design)."""
    px, pz = x + 2, z + 2
    r(in_world(w, f"forceload add {x} {z} {x + 8} {z + 8}"))
    r(in_world(w, f"kill @e[type=villager,x={px},y={Y + 5},z={pz},dx=4,dy=3,dz=3]"))
    for i in range(3):
        r(in_world(w, villager(px + 1.5 + i, Y + 6, pz + 3.5, r)))
    r(in_world(w, f"forceload remove {x} {z} {x + 8} {z + 8}"))


def iron(r, w, x, z):
    """Stone platform x..x+8 × z..z+6 at Y; one row of water sources along z pushes golems (+z) into a 2-deep
    trench z+7..z+8 (a golem is 1.4 blocks wide). Signs over the trench stop the water, not the golems; below,
    a lava blade on signs burns them at head height; drops land in hoppers → chest at (x+9, Y-4, z+8).
    Only one row of sources: two sources next to one cell make it a source too, and still water pushes nothing."""
    c = lambda cmd: r(in_world(w, cmd))
    c(f"forceload add {x - 2} {z - 2} {x + 12} {z + 12}")  # build in unloaded chunks; removed after
    c(f"kill @e[type=!player,x={x - 2},y={Y - 6},z={z - 2},dx=14,dy=18,dz=14]")  # rebuild: no duplicate mobs
    c(f"fill {x - 2} {Y - 6} {z - 2} {x + 11} {Y + 10} {z + 11} air")
    c(f"fill {x - 1} {Y} {z - 1} {x + 9} {Y + 3} {z + 9} glass")                  # shell
    c(f"fill {x} {Y} {z} {x + 8} {Y} {z + 6} glass")
    # spawning floor: stone, but not next to the walls — a golem (1.4 wide) spawned there sticks in the wall
    c(f"fill {x + 1} {Y} {z + 1} {x + 7} {Y} {z + 6} stone")
    c(f"fill {x} {Y + 1} {z} {x + 8} {Y + 3} {z + 8} air")
    # trench z+7..z+8 under the platform level: glass box, hoppers at Y-4, signs at Y-2, lava at Y-1, signs at Y
    c(f"fill {x - 1} {Y - 5} {z + 6} {x + 9} {Y - 1} {z + 9} glass")
    c(f"fill {x} {Y - 3} {z + 7} {x + 8} {Y} {z + 8} air")
    c(f"fill {x} {Y - 4} {z + 7} {x + 8} {Y - 4} {z + 7} hopper[facing=south]")
    c(f"fill {x} {Y - 4} {z + 8} {x + 8} {Y - 4} {z + 8} hopper[facing=east]")
    c(f"setblock {x + 9} {Y - 4} {z + 8} chest")
    for level in (Y - 2, Y):
        c(f"fill {x} {level} {z + 7} {x + 8} {level} {z + 7} oak_wall_sign[facing=south]")  # on the block at z+6
        c(f"fill {x} {level} {z + 8} {x + 8} {level} {z + 8} oak_wall_sign[facing=north]")  # on the wall at z+9
    c(f"fill {x} {Y - 1} {z + 7} {x + 8} {Y - 1} {z + 8} lava")
    c(f"fill {x} {Y + 1} {z} {x + 8} {Y + 1} {z} water")
    # villager pod above the platform: glass floor Y+5, 3 beds + 3 villagers inside, zombie nook next to it
    px, pz = x + 2, z + 2
    c(f"fill {px} {Y + 5} {pz} {px + 4} {Y + 8} {pz + 4} glass")
    c(f"fill {px + 1} {Y + 6} {pz + 1} {px + 3} {Y + 7} {pz + 3} air")
    for i in range(3):
        c(f"setblock {px + 1 + i} {Y + 6} {pz + 2} red_bed[facing=north,part=foot]")
        c(f"setblock {px + 1 + i} {Y + 6} {pz + 1} red_bed[facing=north,part=head]")
        c(villager(px + 1.5 + i, Y + 6, pz + 3.5, r))
    # the zombie stands in a nook open to the villagers: they must see it (glass blocks a mob's line of sight).
    # Without AI it can't walk in or attack; real farms use a zombie in a minecart or behind a trapdoor
    c(f"fill {px + 1} {Y + 5} {pz + 5} {px + 3} {Y + 8} {pz + 6} glass")
    c(f"fill {px + 2} {Y + 6} {pz + 4} {px + 2} {Y + 7} {pz + 5} air")
    # an unbreakable helmet instead of a roof: golems could spawn on an opaque roof and never die
    c(f"summon zombie {px + 2.5} {Y + 6} {pz + 5.5} {{PersistenceRequired:1b,CanPickUpLoot:0b,NoAI:1b,"
      'equipment:{head:{id:"minecraft:leather_helmet",count:1,components:{"minecraft:unbreakable":{}}}}}')
    c(f"forceload remove {x - 2} {z - 2} {x + 12} {z + 12}")


def wool(r, w, x, z, sheep=8):
    """Row of cells along z: sheep at (x, Y+1, z+i) on grass, dispenser at x+1, minecart and hopper below."""
    c = lambda cmd: r(in_world(w, cmd))
    c(f"forceload add {x - 2} {z - 4} {x + 6} {z + sheep + 4}")
    c(f"kill @e[type=!player,x={x - 2},y={Y - 4},z={z - 4},dx=8,dy=10,dz={sheep + 8}]")
    c(f"fill {x - 2} {Y - 4} {z - 4} {x + 6} {Y + 6} {z + sheep + 4} air")
    c(f"fill {x - 1} {Y - 3} {z - 1} {x + 2} {Y + 2} {z + sheep} glass")
    c(f"fill {x} {Y - 2} {z} {x} {Y - 2} {z + sheep - 1} hopper[facing=south]")  # chain → chest at z+sheep
    c(f"setblock {x} {Y - 2} {z + sheep} chest")
    c(f"fill {x} {Y - 1} {z} {x} {Y - 1} {z + sheep - 1} rail[shape=north_south]")
    c(f"fill {x} {Y} {z} {x} {Y} {z + sheep - 1} grass_block")
    # a strip of grass the sheep can't reach, under the glass wall: eaten grass (dirt) regrows from it
    c(f"fill {x - 1} {Y} {z} {x - 1} {Y} {z + sheep - 1} grass_block")
    c(f"fill {x} {Y + 1} {z} {x} {Y + 2} {z + sheep - 1} air")
    for i in range(sheep):
        c(f"setblock {x} {Y + 1} {z + i} air")
        c(f"summon hopper_minecart {x + 0.5} {Y - 1} {z + i + 0.5}")
        c(f"summon sheep {x + 0.5} {Y + 1} {z + i + 0.5} {{PersistenceRequired:1b}}")
        c(f"setblock {x + 1} {Y + 1} {z + i} dispenser[facing=west]")
        c(f"item replace block {x + 1} {Y + 1} {z + i} container.0 with shears")
        c(f"setblock {x - 1} {Y + 1} {z + i} glass")
    # cells: glass between sheep is not needed (sheep don't push out of a 1-wide row walled on both sides)
    c(f"fill {x} {Y + 3} {z - 1} {x + 1} {Y + 3} {z + sheep} glass")
    c(f"fill {x + 1} {Y + 2} {z} {x + 1} {Y + 2} {z + sheep - 1} redstone_wire")  # dust on the dispensers
    # clock: two observers watching each other fire the dispensers 5 times a second. It starts by itself
    # and survives chunk unloads (a repeater loop loses its pulse). Shears with no wool on the sheep just click
    c(f"setblock {x + 1} {Y + 2} {z + sheep} observer[facing=south]")
    c(f"setblock {x + 1} {Y + 2} {z + sheep + 1} observer[facing=north]")
    c(f"forceload remove {x - 2} {z - 4} {x + 6} {z + sheep + 4}")


def spawner(r, w, x, z):
    c = lambda cmd: r(in_world(w, cmd))
    c(f"forceload add {x - 6} {z - 6} {x + 6} {z + 6}")
    c(f"fill {x - 5} {Y - 1} {z - 5} {x + 5} {Y + 6} {z + 5} stone")
    c(f"fill {x - 4} {Y} {z - 4} {x + 4} {Y + 5} {z + 4} air")
    c(f'setblock {x} {Y + 2} {z} spawner{{SpawnData:{{entity:{{id:"minecraft:zombie"}}}}}}')
    # a glass window and a ledge outside it for the bot that keeps the spawner active
    c(f"fill {x + 5} {Y + 1} {z - 1} {x + 5} {Y + 2} {z + 1} glass")
    c(f"fill {x + 6} {Y - 1} {z - 2} {x + 8} {Y - 1} {z + 2} stone")
    c(f"forceload remove {x - 6} {z - 6} {x + 6} {z + 6}")


def chest_of(kind, x, z):
    if kind == "iron":
        return x + 9, Y - 4, z + 8
    if kind == "wool":
        return x, Y - 2, z + 8
    return None


def stand_spot(kind, x, z):
    """Where a bot stands to keep the farm loaded (like an AFK player)."""
    return {"iron": (x + 4, Y + 1, z + 12), "wool": (x + 4, Y + 1, z + 4), "spawner": (x + 7, Y, z)}[kind]


def count(r):
    out = []
    for w, kind, x, z in FARMS:
        pos = chest_of(kind, x, z)
        if not pos:
            continue
        r(in_world(w, f"forceload add {pos[0]} {pos[2]}"))
        got = {}
        # one slot at a time: the game cuts a long answer short with «...»
        for slot in range(27):
            one = r(in_world(w, f"data get block {pos[0]} {pos[1]} {pos[2]} Items[{slot}]"))
            m = re.search(r'id: "minecraft:([a-z_]+)"(?:, count: (\d+))?', one)
            if not m:
                break
            got[m.group(1)] = got.get(m.group(1), 0) + int(m.group(2) or 1)
        r(in_world(w, f"forceload remove {pos[0]} {pos[2]}"))
        # chests next to hoppers also hold what's still in the hopper chain — that's fine, it was produced
        out.append({"world": w, "kind": kind, "x": x, "z": z, "items": got})
    return out


def main():
    r = Rcon()
    what = sys.argv[1] if len(sys.argv) > 1 else "count"
    if what == "build":
        for w, kind, x, z in FARMS:
            print("build", kind, w, x, z)
            {"iron": iron, "wool": wool, "spawner": spawner}[kind](r, w, x, z)
    elif what == "wake":
        for w, kind, x, z in FARMS:
            if kind == "iron":
                wake(r, w, x, z)
    elif what == "clear":
        for w, kind, x, z in FARMS:
            pos = chest_of(kind, x, z)
            if pos:
                r(in_world(w, f"forceload add {pos[0]} {pos[2]}"))
                r(in_world(w, f"data merge block {pos[0]} {pos[1]} {pos[2]} {{Items:[]}}"))
                r(in_world(w, f"forceload remove {pos[0]} {pos[2]}"))
    else:
        print(json.dumps(count(r), ensure_ascii=False))


if __name__ == "__main__":
    main()
