#!/usr/bin/env bash
# Minecraft server setup for Ubuntu 24.04: Java 25, MariaDB, Velocity, Purpur 26.3,
# plugins, systemd services, daily backups, firewall, website behind nginx (+HTTPS).
#
# Run as root from the mc-server folder:   sudo ./setup.sh
# With a domain (DNS A records for it and www. must already point here):
#   sudo DOMAIN=beezzland.ru EMAIL=you@example.com ./setup.sh
# Phones/consoles too (Geyser + Floodgate, UDP 19132): add BEDROCK=1
# Safe to re-run: existing worlds, secrets and database are kept.
set -euo pipefail

MC_VERSION=26.3
ONLINE_MODE=${ONLINE_MODE:-false}   # false = pirates allowed (needs our auth plugin; whitelist until then)
MOTD=${MOTD:-"<gradient:#facc15:#60a5fa><bold>BeezzLand</bold></gradient> <gray>— ванильное выживание</gray>"}
PURPUR_MEM=${PURPUR_MEM:-3584M}     # R6G: 3.5 GB; R8G: 5120M
PROXY_MEM=${PROXY_MEM:-512M}
DOMAIN=${DOMAIN:-}                  # empty = site on http://<server-ip>/ without HTTPS
EMAIL=${EMAIL:-}                    # for Let's Encrypt expiry notices
BEDROCK=${BEDROCK:-0}               # 1 = Geyser + Floodgate: phones/consoles can join (UDP 19132)
BASE=/opt/mc
SRC=$(cd "$(dirname "$0")" && pwd)

log() { echo -e "\n=== $*"; }
[[ $EUID -eq 0 ]] || { echo "run as root (sudo)"; exit 1; }

log "Packages"
apt-get update -q
apt-get install -yq curl jq zstd python3 mariadb-server ufw ca-certificates gnupg nginx

log "Java 25 (Temurin)"
if ! java -version 2>&1 | grep -q '"25'; then
  install -d /etc/apt/keyrings
  curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo "$VERSION_CODENAME") main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update -q && apt-get install -yq temurin-25-jre
fi
java -version

log "User and folders"
id minecraft &>/dev/null || useradd --system --home "$BASE" --shell /usr/sbin/nologin minecraft
install -d -o minecraft -g minecraft "$BASE" "$BASE/proxy" "$BASE/survival" "$BASE/proxy/plugins" "$BASE/survival/plugins"
install -d -m 700 "$BASE/backups"
cp -r "$SRC/scripts" "$SRC/plugins.json" "$BASE/"

log "Secrets"
if [[ ! -f $BASE/.secrets.json ]]; then
  python3 - "$BASE/.secrets.json" "$ONLINE_MODE" "$MOTD" <<'EOF'
import json, secrets, sys
json.dump({"db_name": "mc", "db_user": "mc", "db_password": secrets.token_urlsafe(24),
           "rcon_password": secrets.token_urlsafe(24),
           "online_mode": sys.argv[2] == "true", "motd": sys.argv[3]}, open(sys.argv[1], "w"), indent=2)
EOF
fi
if [[ -n $DOMAIN ]]; then  # the site address goes into the WebTrade calculator button and map links
  jq --arg u "https://$DOMAIN" '.site_url = $u' "$BASE/.secrets.json" > "$BASE/.secrets.tmp" && mv "$BASE/.secrets.tmp" "$BASE/.secrets.json"
fi
chown minecraft:minecraft "$BASE/.secrets.json"; chmod 600 "$BASE/.secrets.json"
S() { jq -r ".$1" "$BASE/.secrets.json"; }

log "MariaDB (localhost only)"
sed -i 's/^bind-address.*/bind-address = 127.0.0.1/' /etc/mysql/mariadb.conf.d/50-server.cnf
systemctl enable --now mariadb && systemctl restart mariadb
mariadb <<EOF
CREATE DATABASE IF NOT EXISTS $(S db_name) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$(S db_user)'@'localhost' IDENTIFIED BY '$(S db_password)';
ALTER USER '$(S db_user)'@'localhost' IDENTIFIED BY '$(S db_password)';
GRANT ALL PRIVILEGES ON $(S db_name).* TO '$(S db_user)'@'localhost';
FLUSH PRIVILEGES;
EOF

log "Purpur $MC_VERSION"
curl -fsSL -o "$BASE/survival/server.jar" "https://api.purpurmc.org/v2/purpur/$MC_VERSION/latest/download"
echo "eula=true" > "$BASE/survival/eula.txt"
# keep the backend private from the very first start (Velocity is the only way in)
[[ -f $BASE/survival/server.properties ]] ||
  printf 'server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\nwhite-list=true\n' > "$BASE/survival/server.properties"

log "Velocity (latest stable)"
VEL_VER=$(curl -fsSL https://fill.papermc.io/v3/projects/velocity | jq -r '[.versions[][] | select(test("SNAPSHOT")|not)] | first')
VEL_URL=$(curl -fsSL "https://fill.papermc.io/v3/projects/velocity/versions/$VEL_VER/builds/latest" | jq -r '.downloads["server:default"].url')
curl -fsSL -o "$BASE/proxy/velocity.jar" "$VEL_URL"
echo "Velocity $VEL_VER"

log "Plugins"
python3 "$BASE/scripts/fetch_plugins.py" backend "$BASE/survival/plugins"
python3 "$BASE/scripts/fetch_plugins.py" proxy "$BASE/proxy/plugins"
if [[ $BEDROCK == 1 ]]; then python3 "$BASE/scripts/fetch_geyser.py" "$BASE/proxy/plugins"; fi
cp "$SRC"/custom-plugins/*.jar "$BASE/survival/plugins/"   # our own plugins (WebTrade, BeezzWorlds)
cp "$SRC"/custom-plugins/proxy/*.jar "$BASE/proxy/plugins/"  # our proxy plugins (BeezzAuth)
cp "$SRC/site/assets/server-icon.png" "$BASE/proxy/server-icon.png"
chown -R minecraft:minecraft "$BASE"

log "Services"
sed -e "s/@MEM@/$PURPUR_MEM/g" "$SRC/systemd/mc-survival.service" > /etc/systemd/system/mc-survival.service
sed -e "s/@MEM@/$PROXY_MEM/g"  "$SRC/systemd/mc-proxy.service"    > /etc/systemd/system/mc-proxy.service
cp "$SRC/systemd/mc-backup.service" "$SRC/systemd/mc-backup.timer" /etc/systemd/system/
systemctl daemon-reload

# First start generates default configs; then we patch them.
if [[ ! -f $BASE/survival/config/paper-global.yml || ! -f $BASE/proxy/forwarding.secret ]]; then
  log "First start (generating configs, takes 1-3 min)"
  systemctl start mc-proxy mc-survival
  for i in $(seq 1 180); do
    journalctl -u mc-survival --since "-10min" | grep -q 'Done (' && break; sleep 2
  done
  sleep 5
  systemctl stop mc-survival mc-proxy
fi

log "Configure"
# our own plugin configs (TAB look, etc.) on top of the generated defaults
cp -r "$SRC/configs/." "$BASE/" && chown -R minecraft:minecraft "$BASE"
sudo -u minecraft python3 "$BASE/scripts/configure.py" "$BASE"

log "Website"
rm -rf "$BASE/site" && cp -r "$SRC/site" "$BASE/site"
SHOWN_IP=${DOMAIN:+play.$DOMAIN}
SHOWN_IP=${SHOWN_IP:-$(curl -fsS4 https://ifconfig.me || hostname -I | cut -d' ' -f1)}
sed -i "s|ip: \"localhost\"|ip: \"$SHOWN_IP\"|" "$BASE/site/config.js"
chown -R minecraft:minecraft "$BASE/site"
cp "$SRC/systemd/mc-site.service" /etc/systemd/system/
sed -e "s/@DOMAIN@/${DOMAIN:-_}/g" "$SRC/deploy/nginx-site.conf" > /etc/nginx/sites-available/beezzland
ln -sf /etc/nginx/sites-available/beezzland /etc/nginx/sites-enabled/beezzland
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
systemctl daemon-reload && systemctl enable --now mc-site

log "Firewall"
ufw allow OpenSSH
ufw allow 'Nginx Full' comment 'Website (80/443)'
ufw allow 25565/tcp comment 'Minecraft (Velocity)'
ufw allow 24454/udp comment 'Simple Voice Chat'
if [[ $BEDROCK == 1 ]]; then ufw allow 19132/udp comment "Bedrock (Geyser)"; fi
ufw --force enable

log "Start"
systemctl enable --now mariadb mc-survival mc-proxy mc-backup.timer
sleep 3
systemctl --no-pager --lines=0 status mc-proxy mc-survival | grep -E 'Loaded|Active'

log "Ranks and game rules"
for i in $(seq 1 120); do journalctl -u mc-survival --since "-5min" | grep -q 'Done (' && break; sleep 2; done
mapfile -t LP_CMDS < <(grep -v '^#' "$SRC/scripts/init_commands.txt" | grep -v '^$')
sudo -u minecraft python3 "$BASE/scripts/rcon.py" "${LP_CMDS[@]}" "tab reload" >/dev/null && echo "ranks created"

if [[ -n $DOMAIN && -n $EMAIL ]]; then
  log "HTTPS (Let's Encrypt)"
  apt-get install -yq certbot python3-certbot-nginx
  certbot --nginx --non-interactive --agree-tos -m "$EMAIL" --redirect -d "$DOMAIN" -d "www.$DOMAIN" \
    || echo "certbot failed: check that $DOMAIN and www.$DOMAIN point to this server, then re-run setup.sh"
fi

echo
echo "Site: ${DOMAIN:+https://$DOMAIN}${DOMAIN:-http://$SHOWN_IP/}"
echo "Done. Connect: $SHOWN_IP:25565  (whitelist is ON: add players with"
echo "  python3 $BASE/scripts/rcon.py 'whitelist add NICK')"
