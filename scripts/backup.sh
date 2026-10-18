#!/usr/bin/env bash
# Backup: MariaDB dump + worlds + configs. Keeps the last KEEP_DAYS days.
set -euo pipefail
BASE=/opt/mc
DEST=$BASE/backups
KEEP_DAYS=${KEEP_DAYS:-7}
STAMP=$(date +%F_%H-%M)
DB=$(python3 -c "import json;print(json.load(open('$BASE/.secrets.json'))['db_name'])")
mkdir -p "$DEST"

rcon() { python3 "$BASE/scripts/rcon.py" "$@" >/dev/null 2>&1 || true; }

# Database (consistent snapshot without locking tables)
mariadb-dump --single-transaction --quick "$DB" | zstd -q -o "$DEST/db_$STAMP.sql.zst"

# Worlds: pause autosave so files are not written mid-copy
rcon "save-off" "save-all flush"
sleep 5
tar --zstd -cf "$DEST/files_$STAMP.tar.zst" -C "$BASE" \
    survival/world survival/world_nether survival/world_the_end \
    survival/plugins proxy/plugins proxy/velocity.toml \
    survival/server.properties survival/config survival/purpur.yml \
    --warning=no-file-changed --ignore-failed-read
rcon "save-on"

find "$DEST" -name '*.zst' -mtime +"$KEEP_DAYS" -delete
echo "backup ok: $STAMP ($(du -sh "$DEST" | cut -f1) total)"
