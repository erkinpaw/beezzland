#!/usr/bin/env bash
# One load-test run: monitor.py logs TPS/MSPT to <name>.csv while bots.js runs, then the summary is printed.
#
# MC_BASE=/opt/mc loadtest/run.sh <name> [count=50] [minutes=5] [speed=5] [explorers=0.3] [radius=0]
set -euo pipefail
cd "$(dirname "$0")"
name=$1 count=${2:-50} minutes=${3:-5} speed=${4:-5} explorers=${5:-0.3} radius=${6:-0}
py=python3; "$py" --version >/dev/null 2>&1 || py=python   # on Windows python3 is a Store stub
secs=$(( count * 32 / 10 + minutes * 60 + 40 ))

"$py" monitor.py "$secs" "$name.csv" > "$name.monitor.log" 2>&1 &
mon=$!
sleep 10
node bots.js "$count" "$minutes" "$speed" "$explorers" "$radius" > "$name.bots.log" 2>&1 || true
wait "$mon" || true

echo "== $name: $count bots, $minutes min, speed $speed, explorers $explorers, radius $radius"
grep -E "kicked:|error:" "$name.bots.log" | sed -E 's/Bee_[0-9]+ //' | sort | uniq -c || true
tail -1 "$name.bots.log"
# min TPS and worst average MSPT while all bots were online
awk -F, -v n="$count" 'NR > 1 && $2 >= n * 0.9 { if (min == "" || $3 < min) min = $3; if ($4 > mx) mx = $4; k++ }
  END { printf "at >=90%% online (%d samples): min tps %s, max avg mspt %s\n", k, min, mx }' "$name.csv"
