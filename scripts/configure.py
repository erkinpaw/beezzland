"""Patch generated configs: Velocity <-> Purpur forwarding, MariaDB for plugins, server.properties.

Usage: python3 configure.py /opt/mc
Run after the first start of both servers (they generate the default files).
Only touches the specific keys below, so comments and other settings stay intact.
"""
import json, os, re, sys

BASE = sys.argv[1]
S = json.load(open(os.path.join(BASE, ".secrets.json")))
PROXY = os.path.join(BASE, "proxy")
DB_PORT = int(S.get("db_port", 3306))  # MariaDB on the same machine; tests use another port
# ports default to production; a local test environment overrides them in .secrets.json
PROXY_PORT = int(S.get("proxy_port", 25565))
BACKEND_PORT = int(S.get("backend_port", 25566))
RCON_PORT = int(S.get("rcon_port", 25575))
MAP_PORT = int(S.get("map_port", 8100))
SURV = os.path.join(BASE, "survival")


def read(p):
    return open(p, encoding="utf-8").read()


def write(p, text):
    open(p, "w", encoding="utf-8").write(text)


def yaml_set(text, path, value):
    """Set a scalar in block-style YAML by key path, keeping everything else as is."""
    lines = text.split("\n")
    start, end, indent = 0, len(lines), -1
    for depth, key in enumerate(path):
        pat = re.compile(r"^(\s*)" + re.escape(key) + r":(\s.*)?$")
        for i in range(start, end):
            m = pat.match(lines[i])
            if m and len(m.group(1)) > indent and (depth or not m.group(1)):
                indent = len(m.group(1))
                if depth == len(path) - 1:
                    lines[i] = f"{m.group(1)}{key}: {value}"
                    # drop continuation lines of an old multi-line scalar
                    j = i + 1
                    while j < len(lines) and lines[j].strip() and len(lines[j]) - len(lines[j].lstrip()) > indent:
                        del lines[j]
                    return "\n".join(lines)
                # child block runs until the next line with indent <= this key's
                start = i + 1
                for j in range(i + 1, end):
                    s = lines[j]
                    if s.strip() and not s.lstrip().startswith("#") and len(s) - len(s.lstrip()) <= indent:
                        end = j
                        break
                break
        else:
            raise KeyError(f"{'.'.join(path)} not found")
    raise KeyError(".".join(path))


def toml_set(text, section, key, value):
    """Set `key = value` in a TOML [section] (None = top level, before the first section)."""
    lines = text.split("\n")
    inside = section is None
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("[") and not stripped.startswith("[["):
            if inside and section is None:
                break
            inside = stripped == f"[{section}]"
            continue
        if inside and re.match(rf"^{re.escape(key)}\s*=", stripped):
            lines[i] = f"{key} = {value}"
            return "\n".join(lines)
    raise KeyError(f"{section}.{key}")


def q(s):
    return "'" + str(s).replace("'", "''") + "'"


def props(path, values):
    lines = read(path).splitlines()
    seen = set()
    for i, line in enumerate(lines):
        k = line.split("=", 1)[0]
        if k in values:
            lines[i] = f"{k}={values[k]}"
            seen.add(k)
    lines += [f"{k}={v}" for k, v in values.items() if k not in seen]
    write(path, "\n".join(lines) + "\n")


# The farm world is for farms, so it may differ from the main one (PLAN.md, load test):
# - monsters: a higher cap (70 → 100) — mob farms there work faster;
# - mobs from spawners have no AI (Spigot's nerf-spawner-mobs): they stand, don't chase or attack,
#   only fall and get pushed by water, like on Vanilla Squad — much less work for the server;
# - iron golems spawned by villagers there have no AI either (BeezzWorlds Golems.java).
FARM_PAPER = """# BeezzLand: the farm world (scripts/configure.py writes this file)
_version: 31
entities:
  spawning:
    spawn-limits:
      monster: 100
"""


NL = chr(10)

# The main world: fewer mobs and a shorter entity activation range (load test: −21% MSPT in the worst case).
# Not for the farm world — iron farms, villager breeders and AFK farms at 24–32 blocks would break there.
MAIN_PAPER = """# BeezzLand: the main world (scripts/configure.py writes this file)
_version: 31
entities:
  spawning:
    spawn-limits:
      monster: 50
      ambient: 5
      water_ambient: 5
    ticks-per-spawn:
      monster: 5
"""
MAIN_SPIGOT = ("  world:" + NL + "    entity-activation-range:" + NL + "      animals: 16" + NL + "      monsters: 24" + NL
               + "      villagers: 16" + NL + "      misc: 8" + NL + "      water: 8" + NL)


# Natural mob spawning was ~32% of the main thread with 60 players (spark, load test): every tick the server
# tries every chunk within mob-spawn-range of each player. 4 chunks (64 blocks) instead of the default 8
# (limited by simulation-distance 6) cuts those chunks by more than half; mobs still spawn 24+ blocks away
# and the mob cap is the same, so there are as many mobs around a player, just closer.
# The main nether tries monsters every 5 ticks instead of every tick.
NETHER_PAPER = """# BeezzLand: the main nether (scripts/configure.py writes this file)
_version: 31
entities:
  spawning:
    ticks-per-spawn:
      monster: 5
"""


def main_world():
    d = os.path.join(SURV, "world", "dimensions", "minecraft", "overworld")
    os.makedirs(d, exist_ok=True)
    write(os.path.join(d, "paper-world.yml"), MAIN_PAPER)
    d = os.path.join(SURV, "world", "dimensions", "minecraft", "the_nether")
    os.makedirs(d, exist_ok=True)
    write(os.path.join(d, "paper-world.yml"), NETHER_PAPER)
    p = os.path.join(SURV, "spigot.yml")
    t = read(p)
    t = yaml_set(t, ["world-settings", "default", "mob-spawn-range"], "4")
    t = re.sub(rf"(?m)^  world:{NL}(    .*{NL})*", "", t)
    t = t.replace("world-settings:" + NL, "world-settings:" + NL + MAIN_SPIGOT, 1)
    write(p, t)


def farm_world():
    for world in ("farm", "farm_nether"):
        d = os.path.join(SURV, "world", "dimensions", "minecraft", world)
        os.makedirs(d, exist_ok=True)
        if world == "farm":
            write(os.path.join(d, "paper-world.yml"), FARM_PAPER)
    p = os.path.join(SURV, "spigot.yml")
    t = read(p)
    # Spigot names worlds by folder: "farm" when BeezzWorlds created it (old Purpur installs),
    # "world_minecraft_farm" for the data pack dimension (now, and the only way on Folia)
    for world in ("farm", "farm_nether", "world_minecraft_farm", "world_minecraft_farm_nether"):
        block = f"  {world}:{NL}    nerf-spawner-mobs: true{NL}"
        t = re.sub(rf"(?m)^  {world}:{NL}(    .*{NL})*", "", t)  # replace our earlier block
        t = t.replace("world-settings:" + NL, "world-settings:" + NL + block, 1)
    write(p, t)


def extras():
    """Small things players hardly notice. In the 60-bot test they changed nothing measurable (70.8–72.1 ms
    with or without, PLAN.md); they are kept as insurance against spikes the test doesn't have: TNT,
    piles of dropped items, arrow-heavy fights, dolphins. Alternate Current redstone and fewer entity
    collisions were tried too and dropped: no gain here, and they can change how farms behave.
    - explosions: Paper's faster calculation, same result;
    - mobs don't re-plan their path on every nearby block change (they do when the path breaks);
    - markers don't tick (armor stands do: players build mechanisms with them);
    - items merge from 2.5 blocks, XP orbs from 3: fewer item entities;
    - dropped cobblestone / netherrack despawn after 15 s, eggs after 1 min (thousands of eggs lie
      in chunks no one visits: chickens lay them all the time);
    - arrows shot by skeletons and by players in creative vanish after 3 s;
    - villagers re-check their bed / job block every 3 s instead of every tick;
    - dolphins don't search for treasure (a structure search, a known lag spike)."""
    p = os.path.join(SURV, "config", "paper-world-defaults.yml")
    t = read(p)
    for path, value in [
        (["environment", "optimize-explosions"], "true"),
        (["misc", "update-pathfinding-on-block-update"], "false"),
        (["entities", "armor-stands", "tick"], "true"),  # explicit: an earlier version turned it off
        (["entities", "armor-stands", "do-collision-entity-lookups"], "true"),
        (["entities", "markers", "tick"], "false"),
        (["entities", "spawning", "alt-item-despawn-rate", "enabled"], "true"),
        (["entities", "spawning", "non-player-arrow-despawn-rate"], "60"),
        (["entities", "spawning", "creative-arrow-despawn-rate"], "60"),
        (["tick-rates", "behavior", "villager", "validatenearbypoi"], "60"),
    ]:
        t = yaml_set(t, path, value)
    if "        egg:" not in t:
        t = t.replace("        cobblestone: 300" + NL, "        cobblestone: 300" + NL + "        netherrack: 300" + NL
                      + "        egg: 1200" + NL, 1)
    write(p, t)
    p = os.path.join(SURV, "spigot.yml")
    t = read(p)
    t = yaml_set(t, ["world-settings", "default", "merge-radius", "item"], "2.5")
    t = yaml_set(t, ["world-settings", "default", "merge-radius", "exp"], "3.0")
    write(p, t)
    p = os.path.join(SURV, "purpur.yml")
    if os.path.exists(p):  # Folia has no purpur.yml
        write(p, yaml_set(read(p), ["world-settings", "default", "mobs", "dolphin", "disable-treasure-searching"], "true"))


def folia():
    """Folia only: how many threads tick regions. The load test had 4 regions (one per dimension, the main
    world with all its players in one), so more threads than that gain nothing; the rest of the cores go
    to chunk loading, GC and the proxy. FOLIA_THREADS overrides."""
    p = os.path.join(SURV, "config", "paper-global.yml")
    t = read(p)
    if re.search(r"(?m)^threaded-regions:", t):
        write(p, yaml_set(t, ["threaded-regions", "threads"], str(os.environ.get("FOLIA_THREADS", "4"))))


def main():
    online = S["online_mode"]
    secret = read(os.path.join(PROXY, "forwarding.secret")).strip()

    # --- Velocity ---
    p = os.path.join(PROXY, "velocity.toml")
    t = read(p)
    t = re.sub(r'(?m)^bind = .*$', f'bind = "0.0.0.0:{PROXY_PORT}"', t)
    t = re.sub(r'(?m)^online-mode = .*$', f'online-mode = {str(online).lower()}', t)
    t = re.sub(r'(?m)^player-info-forwarding-mode = .*$', 'player-info-forwarding-mode = "modern"', t)
    t = re.sub(r'(?m)^motd = .*$', f'motd = "{S["motd"]}"', t)
    t = re.sub(r'(?m)^show-max-players = .*$', 'show-max-players = 100', t)
    t = re.sub(r'(?ms)^\[servers\].*?(?=^\[forced-hosts\])',
               f'[servers]\nsurvival = "127.0.0.1:{BACKEND_PORT}"\ntry = ["survival"]\n\n', t)
    t = re.sub(r'(?ms)^\[forced-hosts\].*?(?=^\[advanced\])', '[forced-hosts]\n\n', t)
    write(p, t)

    # --- Purpur / Paper ---
    props(os.path.join(SURV, "server.properties"), {
        "server-ip": "127.0.0.1",
        "server-port": str(BACKEND_PORT),
        "online-mode": "false",          # Velocity does the auth
        "enforce-secure-profile": str(online).lower(),  # chat signing needs Mojang accounts
        "enable-rcon": "true",
        "rcon.port": str(RCON_PORT),
        "rcon.password": S["rcon_password"],
        "max-players": "100",            # default is 20; Velocity shows 100 slots
        "view-distance": "8",
        "simulation-distance": "6",
        "spawn-protection": "0",
        "white-list": "true",
        "enforce-whitelist": "true",
        "motd": S["motd"],
    })
    p = os.path.join(SURV, "config", "paper-global.yml")
    t = read(p)
    t = yaml_set(t, ["proxies", "velocity", "enabled"], "true")
    t = yaml_set(t, ["proxies", "velocity", "online-mode"], str(online).lower())
    t = yaml_set(t, ["proxies", "velocity", "secret"], q(secret))
    write(p, t)

    # Paper's built-in anti-xray: ores fully hidden inside stone are sent to clients as stone
    p = os.path.join(SURV, "config", "paper-world-defaults.yml")
    write(p, yaml_set(read(p), ["anticheat", "anti-xray", "enabled"], "true"))
    extras()
    folia()

    # --- Farm world: more mobs, spawner mobs without AI (see farm_world()) ---
    farm_world()
    main_world()

    # --- LuckPerms (backend + proxy share one database) ---
    for p in (os.path.join(SURV, "plugins", "LuckPerms", "config.yml"),
              os.path.join(PROXY, "plugins", "luckperms", "config.yml")):
        t = read(p)
        t = yaml_set(t, ["storage-method"], "mariadb")
        t = yaml_set(t, ["data", "address"], f"localhost:{DB_PORT}")
        t = yaml_set(t, ["data", "database"], S["db_name"])
        t = yaml_set(t, ["data", "username"], S["db_user"])
        t = yaml_set(t, ["data", "password"], q(S["db_password"]))
        write(p, t)

    # --- LibertyBans (bans/mutes on the proxy) ---
    p = os.path.join(PROXY, "plugins", "libertybans", "sql.yml")
    t = read(p)
    t = yaml_set(t, ["rdms-vendor"], "'MARIADB'")
    t = yaml_set(t, ["auth-details", "host"], "'localhost'")
    t = yaml_set(t, ["auth-details", "port"], str(DB_PORT))
    t = yaml_set(t, ["auth-details", "database"], q(S["db_name"]))
    t = yaml_set(t, ["auth-details", "user"], q(S["db_user"]))
    t = yaml_set(t, ["auth-details", "password"], q(S["db_password"]))
    write(p, t)

    # --- Russian texts (the server is Russian-speaking) ---
    p = os.path.join(PROXY, "plugins", "libertybans", "config.yml")
    write(p, yaml_set(read(p), ["lang-file"], "'ru'"))
    p = os.path.join(SURV, "spigot.yml")
    t = read(p)
    for k, v in {"whitelist": "Тебя нет в вайтлисте. Привяжи аккаунт на beezzland.ru",
                 "unknown-command": "Неизвестная команда.",
                 "server-full": "Сервер заполнен, попробуй чуть позже!",
                 "outdated-client": "Устаревший клиент! Используй {0}",
                 "outdated-server": "Сервер пока на версии {0}",
                 "restart": "Сервер перезапускается, заходи через минуту!"}.items():
        t = yaml_set(t, ["messages", k], q(v))
    write(p, t)
    p = os.path.join(SURV, "config", "paper-global.yml")
    t = read(p)
    t = yaml_set(t, ["messages", "no-permission"], q("<red>Недостаточно прав для этой команды."))
    t = yaml_set(t, ["messages", "kick", "connection-throttle"], q("Слишком частые подключения, подожди пару секунд."))
    write(p, t)
    p = os.path.join(SURV, "plugins", "Plan", "config.yml")
    t = read(p)
    t = yaml_set(t, ["Plugin", "Logging", "Locale"], "RU")
    t = yaml_set(t, ["Server", "ServerName"], "BeezzLand")
    write(p, t)

    # --- Carbon chat: Russian, local chat by default (channels come from configs/), MariaDB ---
    p = os.path.join(SURV, "plugins", "CarbonChat", "config.conf")
    t = read(p)
    t = re.sub(r'(?m)^default-locale=.*$', 'default-locale="ru_RU"', t)
    t = re.sub(r'(?m)^default-channel=.*$', 'default-channel="carbon:local"', t)
    t = re.sub(r'(?m)^storage-type=.*$', 'storage-type=MYSQL', t)
    # BeezzCore owns the display name (badge, colour, hover card); Carbon must not overwrite it
    t = re.sub(r'(?m)^(\s*)use-carbon-nicknames=.*$', r'\1use-carbon-nicknames=false', t)
    head, sep, rest = t.partition("database-settings {")
    db, close, tail = rest.partition("connection-pool {")  # url/username/password sit before the pool block
    db = re.sub(r'(?m)^(\s*)url=.*$', lambda m: f'{m.group(1)}url="jdbc:mysql://localhost:{DB_PORT}/{S["db_name"]}"', db, count=1)
    db = re.sub(r'(?m)^(\s*)username=.*$', lambda m: f'{m.group(1)}username="{S["db_user"]}"', db, count=1)
    db = re.sub(r'(?m)^(\s*)password=.*$', lambda m: f'{m.group(1)}password="{S["db_password"]}"', db, count=1)
    write(p, head + sep + db + close + tail)

    # --- WebTrade: the in-game "open calculator" button leads to our site's calculator tab ---
    p = os.path.join(SURV, "plugins", "WebTrade", "config.yml")
    if os.path.exists(p):
        site = S.get("site_url", "https://beezzland.ru")
        write(p, yaml_set(read(p), ["calculator", "url"], q(site + "/calculator.html")))

    # --- BeezzCore: links in the welcome, /rules, /links and tips point to our site ---
    p = os.path.join(SURV, "plugins", "BeezzCore", "config.yml")
    if os.path.exists(p):
        write(p, yaml_set(read(p), ["site"], q(S.get("site_url", "https://beezzland.ru"))))

    # --- BeezzCore: the player card reads LibertyBans history from MariaDB ---
    p = os.path.join(SURV, "plugins", "BeezzCore", "config.yml")
    if os.path.exists(p):
        t = read(p)
        if not re.search(r"(?m)^database:", t):  # config from an older BeezzCore: add the section
            t = t.rstrip("\n") + '\ndatabase:\n  url: ""\n  user: ""\n  password: ""\n'
        t = yaml_set(t, ["database", "url"], q(f"jdbc:mysql://localhost:{DB_PORT}/{S['db_name']}?useSSL=false&allowPublicKeyRetrieval=true"))
        t = yaml_set(t, ["database", "user"], q(S["db_user"]))
        t = yaml_set(t, ["database", "password"], q(S["db_password"]))
        write(p, t)

    # --- Required resource pack (logo + rank badges), served by our site ---
    pack = os.path.join(BASE, "site", "pack", "beezzland-pack.zip")
    if os.path.exists(pack):
        import hashlib
        props(os.path.join(SURV, "server.properties"), {
            "resource-pack": S.get("site_url", "https://beezzland.com") + "/pack/beezzland-pack.zip",
            "resource-pack-sha1": hashlib.sha1(open(pack, "rb").read()).hexdigest(),
            "require-resource-pack": "true",
            "resource-pack-prompt": '{"text":"Ресурспак BeezzLand: логотип и значки рангов (несколько КБ). Без него зайти нельзя.","color":"yellow"}',
        })

    # --- Plasmo Voice: players' voice goes to the proxy (UDP on the game port), the proxy talks
    # to the server's voice port on localhost; Russian texts for clients without a translation ---
    p = os.path.join(SURV, "plugins", "PlasmoVoice", "config.toml")
    if os.path.exists(p):
        t = toml_set(read(p), None, "default_language", '"ru_ru"')
        write(p, toml_set(t, "host", "ip", '"127.0.0.1"'))
    p = os.path.join(PROXY, "plugins", "plasmovoice", "config.toml")
    if os.path.exists(p):
        write(p, toml_set(read(p), None, "default_language", '"ru_ru"'))

    # --- CoreProtect (only writes its config once it supports the server version) ---
    p = os.path.join(SURV, "plugins", "CoreProtect", "config.yml")
    if os.path.exists(p):
        t = read(p)
        for k, v in {"use-mysql": "true", "mysql-host": "127.0.0.1", "mysql-port": str(DB_PORT),
                     "mysql-database": S["db_name"], "mysql-username": S["db_user"],
                     "mysql-password": q(S["db_password"])}.items():
            t = yaml_set(t, [k], v)
        write(p, t)
    else:
        print("CoreProtect config not found (plugin inactive on this version), skipped")

    # --- Pl3xMap: web server only on localhost:8100 (the site proxies /map/), 2 render threads ---
    p = os.path.join(SURV, "plugins", "Pl3xMap", "config.yml")
    if os.path.exists(p):
        t = read(p)
        t = yaml_set(t, ["settings", "internal-webserver", "bind"], "127.0.0.1")
        t = yaml_set(t, ["settings", "internal-webserver", "port"], str(MAP_PORT))
        t = yaml_set(t, ["settings", "web-address"], q(S.get("site_url", "https://beezzland.ru") + "/map/"))
        t = yaml_set(t, ["settings", "performance", "render-threads"], "2")  # default -1 = half the cores
        # by default it calls System.gc() while/after rendering: 300-450 ms full-GC pauses under load
        # (the service's -XX:+DisableExplicitGC also blocks it; this covers runs without Aikar's flags)
        t = yaml_set(t, ["settings", "performance", "gc", "when-running"], "false")
        t = yaml_set(t, ["settings", "performance", "gc", "when-finished"], "false")
        # Russian world names; the main world first (otherwise "farm" sorts before "world" and opens by default)
        for world, name, order in (("world", "Основной мир", 0), ("world_nether", "Незер", 1), ("world_the_end", "Энд", 2)):
            t = yaml_set(t, ["world-settings", world, "ui", "display-name"], q(name))
            t = yaml_set(t, ["world-settings", world, "ui", "order"], str(order))
        if "\n  farm:\n" not in t:  # world-settings is the last block of the file
            t = t.rstrip("\n") + "\n" + "".join(
                f"  {world}:\n" + (f"    render:\n      renderers:\n        basic: nether_basic\n      skylight: 0\n" if nether else "")
                + f"    ui:\n      display-name: '{name}'\n      order: {order}\n"
                for world, name, order, nether in (("farm", "Мир ферм", 3, False), ("farm_nether", "Незер мира ферм", 4, True)))
        write(p, t)
    print("configs patched")


if __name__ == "__main__":
    main()
