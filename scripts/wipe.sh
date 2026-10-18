#!/usr/bin/env bash
# Season wipe: a fresh world for everyone. Nothing is deleted — the old world and plugin data
# are MOVED to /opt/mc/backups/wipe_<date>/ (after a normal backup), so a wipe can be undone.
#
#   sudo bash /opt/mc/scripts/wipe.sh --yes            # 60 s countdown in chat, then wipe
#   sudo bash /opt/mc/scripts/wipe.sh --yes --now      # no countdown (server empty)
#
# Reset: all worlds (overworld, nether, end, farm, farm_nether — in 26.x they all live in
#        survival/world), inventories/advancements/stats (inside world), WebTrade balances and
#        orders, BeezzWorlds stages (all closed again) and honeycomb portals, map tiles.
# Kept:  BeezzAuth links, LuckPerms ranks, bans (LibertyBans), Carbon chat settings, Plan history,
#        all configs.
set -euo pipefail

BASE=${MC_BASE:-/opt/mc}
SURV=$BASE/survival
NOSYSTEMD=${NOSYSTEMD:-0}   # 1 = test mode on a copy: no services, no RCON
YES=0; NOW=0
for a in "$@"; do
  case $a in
    --yes) YES=1 ;;
    --now) NOW=1 ;;
    *) echo "unknown option $a"; exit 2 ;;
  esac
done
[[ $YES == 1 ]] || { sed -n '2,13p' "$0"; echo; echo "Add --yes to really wipe."; exit 1; }

STAMP=$(date +%F_%H-%M)
DEST=$BASE/backups/wipe_$STAMP
rcon() { [[ $NOSYSTEMD == 1 ]] || python3 "$BASE/scripts/rcon.py" "$@" >/dev/null 2>&1 || true; }

if [[ $NOW == 0 && $NOSYSTEMD == 0 ]]; then
  for left in 60 30 10 5; do
    rcon "say Вайп сервера через $left секунд! Новый мир — новый сезон." "title @a title {\"text\":\"Вайп через $left с\",\"color\":\"gold\"}"
    case $left in 60) sleep 30 ;; 30) sleep 20 ;; 10) sleep 5 ;; 5) sleep 5 ;; esac
  done
fi

echo "=== Backup before wipe"
if [[ $NOSYSTEMD == 0 ]]; then
  bash "$BASE/scripts/backup.sh"   # stops nothing; uses save-off/save-all; aborts the wipe if it fails
  systemctl stop mc-survival
fi

echo "=== Moving old season to $DEST"
mkdir -p "$DEST/plugins"
move() {  # move a path into the season folder, keeping its relative layout
  local rel=$1
  if [[ -e $SURV/$rel ]]; then
    mkdir -p "$DEST/$(dirname "$rel")"
    mv "$SURV/$rel" "$DEST/$rel"
    echo "  moved $rel"
  fi
}
move world
move bluemap                                     # BlueMap tiles of the old world
move plugins/Pl3xMap/web/tiles                   # Pl3xMap tiles of the old world
for f in accounts.yml containers.yml warehouse.yml; do move "plugins/WebTrade/$f"; done
move plugins/BeezzWorlds/state.yml
move plugins/BeezzWorlds/portals.yml
move plugins/CoreProtect/database.db             # block log of the old world (if SQLite)

if [[ $NOSYSTEMD == 0 ]]; then
  chown -R minecraft:minecraft "$BASE/backups"
  echo "=== Starting the new season"
  systemctl start mc-survival
fi

cat <<EOF

Wipe done. The old season is in $DEST (delete it when you're sure).
Next, before players arrive:
  1. Pre-generate the new world:  python3 $BASE/scripts/rcon.py "chunky world world" "chunky radius 5000" "chunky start"
  2. Set the border:              python3 $BASE/scripts/rcon.py "worldborder set 10000"
  3. Nether / farm world / End are closed again: open them with /beezz open … on schedule.
  4. If CoreProtect uses MariaDB, its old-world log is still in the database (clear with /co purge t:0s or keep for reference).
EOF
