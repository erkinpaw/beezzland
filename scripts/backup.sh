#!/usr/bin/env bash
# Hourly backup with restic: worlds, players, plugin data, databases, configs.
#
# restic stores every piece of a file once, so an hour's snapshot only adds what changed (tens to hundreds of MB),
# not the whole pre-generated world. Two repositories:
#   - local:  $BASE/backups/restic           — fast rollbacks (scripts/rollback.sh)
#   - remote: "restic_remote" in .secrets.json, e.g. sftp:backup@hetzner-host:/srv/beezzland — a copy off this
#     machine, if the hosting itself dies. Optional; encrypted with the same password.
# Kept: 24 hourly, 7 daily, 4 weekly snapshots (the season archive is the wipe's own folder).
# The password is in $BASE/.restic-password (setup.sh makes it). Lose it and the backups can't be read:
# keep a copy somewhere safe (a password manager).
#
#   scripts/backup.sh            one snapshot now (the systemd timer runs this every hour)
#   NOSYSTEMD=1 MC_BASE=…         for a test server: same, without root paths
set -euo pipefail
BASE=${MC_BASE:-/opt/mc}
REPO=${RESTIC_REPOSITORY:-$BASE/backups/restic}
STAGE=$BASE/backups/stage
export RESTIC_PASSWORD_FILE=${RESTIC_PASSWORD_FILE:-$BASE/.restic-password}
RESTIC=${RESTIC:-restic}
py=python3; "$py" --version >/dev/null 2>&1 || py=python
secret() { "$py" -c "import json,sys;print(json.load(open(sys.argv[1])).get(sys.argv[2],''))" "$BASE/.secrets.json" "$1"; }
rcon() { "$py" "$BASE/scripts/rcon.py" "$@" >/dev/null 2>&1 || true; }

mkdir -p "$STAGE"
[[ -f $RESTIC_PASSWORD_FILE ]] || { echo "no $RESTIC_PASSWORD_FILE (setup.sh creates it)"; exit 1; }
"$RESTIC" -r "$REPO" cat config >/dev/null 2>&1 || "$RESTIC" -r "$REPO" init

# Databases first, as consistent copies (the live files may be mid-write):
# MariaDB — ranks, bans, chat; SQLite — жужики and duels, the shop's orders
DB=$(secret db_name)
if [[ -n $DB ]] && command -v mariadb-dump >/dev/null; then
  mariadb-dump --single-transaction --quick "$DB" > "$STAGE/mariadb.sql"
fi
"$py" - "$BASE" "$STAGE" <<'PY'
import os, sqlite3, sys
base, stage = sys.argv[1], sys.argv[2]
for src, name in [("survival/plugins/BeezzDuels/zhuzhiki.db", "zhuzhiki.db"), ("shop.db", "shop.db")]:
    p = os.path.join(base, src)
    if os.path.exists(p):
        with sqlite3.connect(p) as a, sqlite3.connect(os.path.join(stage, name)) as b:
            a.backup(b)
PY

# Worlds: autosave off only while restic reads the files (10–30 s once the first snapshot exists)
rcon "save-off" "save-all flush"
sleep 3
paths=(survival/world survival/plugins proxy/plugins survival/config survival/server.properties
       survival/spigot.yml survival/bukkit.yml proxy/velocity.toml backups/stage)
for f in survival/purpur.yml shop.json sessions.json; do [[ -e $BASE/$f ]] && paths+=("$f"); done
set +e
(cd "$BASE" && "$RESTIC" -r "$REPO" backup --tag hourly --host beezzland --quiet \
    --exclude '*.jar' --exclude 'survival/plugins/*/libraries' --exclude 'survival/plugins/.paper-remapped' \
    --exclude 'survival/plugins/spark/tmp*' --exclude 'survival/plugins/Pl3xMap/web/tiles' \
    --exclude 'survival/plugins/squaremap/web/tiles' --exclude '*.lock' --exclude 'session.lock' \
    "${paths[@]}")
code=$?
set -e
rcon "save-on"
# 3 = some files changed while being read (the world kept saving chunks of other players): still a good snapshot
[[ $code == 0 || $code == 3 ]] || { echo "restic backup failed ($code)"; exit $code; }

"$RESTIC" -r "$REPO" forget --host beezzland --keep-hourly 24 --keep-daily 7 --keep-weekly 4 --quiet
# pruning rewrites packs: once a day is enough
[[ $(date +%H) == 05 || ${PRUNE:-0} == 1 ]] && "$RESTIC" -r "$REPO" prune --quiet

REMOTE=$(secret restic_remote)
if [[ -n $REMOTE ]]; then
  "$RESTIC" -r "$REMOTE" cat config >/dev/null 2>&1 || "$RESTIC" -r "$REMOTE" init --from-repo "$REPO" --copy-chunker-params \
      --from-password-file "$RESTIC_PASSWORD_FILE"
  "$RESTIC" -r "$REMOTE" copy --from-repo "$REPO" --from-password-file "$RESTIC_PASSWORD_FILE" --host beezzland --quiet \
    && "$RESTIC" -r "$REMOTE" forget --host beezzland --keep-hourly 24 --keep-daily 7 --keep-weekly 4 --quiet \
    || echo "remote copy failed — the local snapshot is fine, the next hour retries"
  [[ $(date +%H) == 05 ]] && "$RESTIC" -r "$REMOTE" prune --quiet || true
fi
echo "backup ok: $(date +%F_%H-%M), repo $(du -sh "$REPO" 2>/dev/null | cut -f1)"
