#!/usr/bin/env bash
# Rollbacks from the hourly restic snapshots (scripts/backup.sh). From smallest to biggest:
#
#   rollback.sh list                                   snapshots with their time (newest last)
#   rollback.sh area   <snapshot> <world> x1 z1 x2 z2  one area back as it was: terrain, entities, beds/workstations.
#                                                      The server stops for about a minute (players are warned),
#                                                      everything outside the area stays as it is now.
#   rollback.sh player <snapshot> <nick>               one player's inventory, ender chest, XP, position, effects.
#                                                      The player is kicked first; the server keeps running.
#   rollback.sh full   <snapshot> [--with-db]          the whole server: every world, players, WebTrade, BeezzWorlds.
#                                                      The current state goes to backups/before-rollback_<time>.
#                                                      Ranks, bans and shop orders (MariaDB, shop.db) stay unless
#                                                      --with-db: you don't want to take back what was paid for.
#
# <snapshot> — an ID from «list», or «latest». <world>: main, nether, end, farm, farm_nether.
# A test server: NOSYSTEMD=1 MC_BASE=… STOP_CMD=… START_CMD=… (how to stop / start it there).
set -euo pipefail
BASE=${MC_BASE:-/opt/mc}
REPO=${RESTIC_REPOSITORY:-$BASE/backups/restic}
export RESTIC_PASSWORD_FILE=${RESTIC_PASSWORD_FILE:-$BASE/.restic-password}
RESTIC=${RESTIC:-restic}
STOP_CMD=${STOP_CMD:-systemctl stop mc-survival}
START_CMD=${START_CMD:-systemctl start mc-survival}
py=python3; "$py" --version >/dev/null 2>&1 || py=python
WORLD=$BASE/survival/world
WORK=$BASE/backups/rollback-work  # not TMP: that one is the system temp dir, and the server we start would inherit it
rcon() { "$py" "$BASE/scripts/rcon.py" "$@" 2>/dev/null || true; }
r() { "$RESTIC" -r "$REPO" "$@"; }

dim() {
  case $1 in
    main) echo overworld ;; nether) echo the_nether ;; end) echo the_end ;;
    farm) echo farm ;; farm_nether) echo farm_nether ;;
    *) echo "unknown world «$1»: main, nether, end, farm, farm_nether" >&2; exit 1 ;;
  esac
}

warn_and_stop() {
  for left in 30 10 5; do
    rcon "say Откат участка мира через $left с — сервер перезапустится на минуту."
    sleep $(( left == 30 ? 20 : 5 ))
  done
  rcon "save-all flush"
  $STOP_CMD
}

case ${1:-} in
list)
  r snapshots --host beezzland --compact
  ;;

area)
  [[ $# == 7 ]] || { echo "rollback.sh area <snapshot> <world> x1 z1 x2 z2"; exit 1; }
  snap=$2 d=$(dim "$3") x1=$4 z1=$5 x2=$6 z2=$7
  rel=survival/world/dimensions/minecraft/$d
  # only the region files the area touches (32×32 chunks = 512×512 blocks each), from all three folders
  mapfile -t files < <("$py" - "$x1" "$z1" "$x2" "$z2" <<'PY'
import sys
x1, z1, x2, z2 = map(int, sys.argv[1:5])
for rx in range(min(x1, x2) >> 9, (max(x1, x2) >> 9) + 1):
    for rz in range(min(z1, z2) >> 9, (max(z1, z2) >> 9) + 1):
        print(f"r.{rx}.{rz}.mca")
PY
)
  cr=$'\r'
  files=("${files[@]%"$cr"}")  # Python on Windows (test servers) ends lines with \r
  rm -rf "$WORK" && mkdir -p "$WORK"
  inc=()
  for f in "${files[@]}"; do for k in region entities poi; do inc+=(--include "/$rel/$k/$f"); done; done
  r restore "$snap" --target "$WORK" "${inc[@]}" --quiet
  [[ -d $WORK/$rel ]] || { echo "the snapshot has nothing there"; exit 1; }
  warn_and_stop
  "$py" "$BASE/scripts/chunks.py" "$WORK/$rel" "$WORLD/dimensions/minecraft/$d" "$x1" "$z1" "$x2" "$z2"
  $START_CMD
  rm -rf "$WORK"
  echo "area $3 $x1 $z1 → $x2 $z2 restored from $snap"
  ;;

player)
  [[ $# == 3 ]] || { echo "rollback.sh player <snapshot> <nick>"; exit 1; }
  snap=$2 nick=$3
  uuid=$("$py" -c "import json,sys;print(next((u['uuid'] for u in json.load(open(sys.argv[1])) if u['name'].lower()==sys.argv[2].lower()),''))" \
    "$BASE/survival/usercache.json" "$nick")
  [[ -n $uuid ]] || { echo "no player $nick in usercache.json"; exit 1; }
  rm -rf "$WORK" && mkdir -p "$WORK"
  r restore "$snap" --target "$WORK" --include "/survival/world/players/data/$uuid.dat" --quiet
  src=$WORK/survival/world/players/data/$uuid.dat
  [[ -f $src ]] || { echo "no saved data of $nick in $snap"; exit 1; }
  # the server writes the player's file when they leave: kick first, then put the old one in
  rcon "kick $nick Откат твоих вещей — зайди через минуту."
  sleep 3
  cp "$WORLD/players/data/$uuid.dat" "$WORLD/players/data/$uuid.dat.before-rollback" 2>/dev/null || true
  cp "$src" "$WORLD/players/data/$uuid.dat"
  rm -rf "$WORK"
  echo "$nick ($uuid) restored from $snap; the previous file is $uuid.dat.before-rollback"
  ;;

full)
  [[ $# -ge 2 ]] || { echo "rollback.sh full <snapshot> [--with-db]"; exit 1; }
  snap=$2
  stamp=$(date +%F_%H-%M)
  rm -rf "$WORK" && mkdir -p "$WORK"
  r restore "$snap" --target "$WORK" --include /survival/world --include /survival/plugins/WebTrade \
    --include /survival/plugins/BeezzWorlds --include /backups/stage --quiet
  [[ -d $WORK/survival/world ]] || { echo "the snapshot has no world"; exit 1; }
  warn_and_stop
  keep=$BASE/backups/before-rollback_$stamp
  mkdir -p "$keep/plugins"
  mv "$WORLD" "$keep/world"
  for p in WebTrade BeezzWorlds; do
    [[ -d $BASE/survival/plugins/$p ]] && cp -r "$BASE/survival/plugins/$p" "$keep/plugins/"
    [[ -d $WORK/survival/plugins/$p ]] && cp -r "$WORK/survival/plugins/$p/." "$BASE/survival/plugins/$p/"
  done
  mv "$WORK/survival/world" "$WORLD"
  if [[ ${3:-} == --with-db ]]; then
    db=$("$py" -c "import json;print(json.load(open('$BASE/.secrets.json'))['db_name'])")
    mariadb-dump --single-transaction "$db" > "$keep/mariadb.sql"
    mariadb "$db" < "$WORK/backups/stage/mariadb.sql"
    cp "$BASE/shop.db" "$keep/" 2>/dev/null || true
    cp "$WORK/backups/stage/shop.db" "$BASE/shop.db" 2>/dev/null || true
    echo "databases restored too (the current ones are in $keep)"
  fi
  if [[ -z ${NOSYSTEMD:-} ]]; then chown -R minecraft:minecraft "$WORLD" "$BASE/survival/plugins"; fi
  $START_CMD
  rm -rf "$WORK"
  echo "the whole server is back at $snap; what was there before is in $keep"
  ;;

*)
  sed -n '2,16p' "$0"
  ;;
esac
