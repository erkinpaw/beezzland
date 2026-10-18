"""Patch generated configs: Velocity <-> Purpur forwarding, MariaDB for plugins, server.properties.

Usage: python3 configure.py /opt/mc
Run after the first start of both servers (they generate the default files).
Only touches the specific keys below, so comments and other settings stay intact.
"""
import json, os, re, sys

BASE = sys.argv[1]
S = json.load(open(os.path.join(BASE, ".secrets.json")))
PROXY = os.path.join(BASE, "proxy")
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


def main():
    online = S["online_mode"]
    secret = read(os.path.join(PROXY, "forwarding.secret")).strip()

    # --- Velocity ---
    p = os.path.join(PROXY, "velocity.toml")
    t = read(p)
    t = re.sub(r'(?m)^bind = .*$', 'bind = "0.0.0.0:25565"', t)
    t = re.sub(r'(?m)^online-mode = .*$', f'online-mode = {str(online).lower()}', t)
    t = re.sub(r'(?m)^player-info-forwarding-mode = .*$', 'player-info-forwarding-mode = "modern"', t)
    t = re.sub(r'(?m)^motd = .*$', f'motd = "{S["motd"]}"', t)
    t = re.sub(r'(?m)^show-max-players = .*$', 'show-max-players = 100', t)
    t = re.sub(r'(?ms)^\[servers\].*?(?=^\[forced-hosts\])',
               '[servers]\nsurvival = "127.0.0.1:25566"\ntry = ["survival"]\n\n', t)
    t = re.sub(r'(?ms)^\[forced-hosts\].*?(?=^\[advanced\])', '[forced-hosts]\n\n', t)
    write(p, t)

    # --- Purpur / Paper ---
    props(os.path.join(SURV, "server.properties"), {
        "server-ip": "127.0.0.1",
        "server-port": "25566",
        "online-mode": "false",          # Velocity does the auth
        "enforce-secure-profile": str(online).lower(),  # chat signing needs Mojang accounts
        "enable-rcon": "true",
        "rcon.port": "25575",
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

    # --- LuckPerms (backend + proxy share one database) ---
    for p in (os.path.join(SURV, "plugins", "LuckPerms", "config.yml"),
              os.path.join(PROXY, "plugins", "luckperms", "config.yml")):
        t = read(p)
        t = yaml_set(t, ["storage-method"], "mariadb")
        t = yaml_set(t, ["data", "address"], "localhost")
        t = yaml_set(t, ["data", "database"], S["db_name"])
        t = yaml_set(t, ["data", "username"], S["db_user"])
        t = yaml_set(t, ["data", "password"], q(S["db_password"]))
        write(p, t)

    # --- LibertyBans (bans/mutes on the proxy) ---
    p = os.path.join(PROXY, "plugins", "libertybans", "sql.yml")
    t = read(p)
    t = yaml_set(t, ["rdms-vendor"], "'MARIADB'")
    t = yaml_set(t, ["auth-details", "host"], "'localhost'")
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
    head, sep, rest = t.partition("database-settings {")
    db, close, tail = rest.partition("connection-pool {")  # url/username/password sit before the pool block
    db = re.sub(r'(?m)^(\s*)url=.*$', lambda m: f'{m.group(1)}url="jdbc:mysql://localhost:3306/{S["db_name"]}"', db, count=1)
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

    # --- Simple Voice Chat: Purpur listens on localhost only, voice UDP must be public ---
    p = os.path.join(SURV, "plugins", "voicechat", "voicechat-server.properties")
    if os.path.exists(p):
        props(p, {"port": "24454", "bind_address": "*"})

    # --- CoreProtect (only writes its config once it supports the server version) ---
    p = os.path.join(SURV, "plugins", "CoreProtect", "config.yml")
    if os.path.exists(p):
        t = read(p)
        for k, v in {"use-mysql": "true", "mysql-host": "127.0.0.1", "mysql-port": "3306",
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
        t = yaml_set(t, ["settings", "internal-webserver", "port"], "8100")
        t = yaml_set(t, ["settings", "web-address"], q(S.get("site_url", "https://beezzland.ru") + "/map/"))
        t = yaml_set(t, ["settings", "performance", "render-threads"], "2")  # default -1 = half the cores
        write(p, t)
    print("configs patched")


if __name__ == "__main__":
    main()
