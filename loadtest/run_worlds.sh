#!/usr/bin/env bash
# One load-test run over all worlds (world_bots.js: 60 bots in the main world, nether, farm world, farm nether)
# with the test farms working (farms.py). Before: server running, farms built. Prints the summary.
#
# MC_BASE=… loadtest/run_worlds.sh <name> [minutes=15]
#   LT_PROC — command-line pattern of the server JVM for CPU/RAM (monitor.py)
set -euo pipefail
cd "$(dirname "$0")"
name=$1 minutes=${2:-15}
py=python3; "$py" --version >/dev/null 2>&1 || py=python
rc() { "$py" ../scripts/rcon.py "$@" >/dev/null; }

rc "whitelist off"
# night for most of the run: the heaviest hour of a real evening (all mobs out)
rc "execute in minecraft:overworld run time set 12500" "execute in minecraft:farm run time set 12500"
"$py" farms.py clear
secs=$(( 60 * 32 / 10 + minutes * 60 + 30 ))
"$py" monitor.py "$secs" "$name.csv" > "$name.monitor.log" 2>&1 &
mon=$!
sleep 5
node world_bots.js "$minutes" "${LT_PORT:-25565}" > "$name.bots.log" 2>&1 || true
"$py" farms.py count > "$name.farms.json"
"$py" ../scripts/rcon.py "paper entity list * minecraft:overworld" "paper entity list * minecraft:the_nether" "paper entity list * minecraft:farm" "paper entity list * minecraft:farm_nether" > "$name.entities.txt" 2>&1 || true
wait "$mon" || true

echo "== $name: $(grep -c . "$name.csv") samples"
grep -E "kicked:|error:" "$name.bots.log" | sed -E 's/Bee_[0-9]+ //' | sort | uniq -c || true
tail -1 "$name.bots.log"
# while >=90% of the bots were online
awk -F, 'NR > 1 && $2 >= 54 { n++; s += $4; if ($4 > mx) mx = $4; if (min == "" || $3 < min) min = $3; if ($6 != "") { c += $6; cn++ }; if ($7 > rss) rss = $7 }
  END { if (n) printf "online>=54: %d samples, mspt avg %.1f, worst 5s avg %.1f, min tps %s, cpu %.2f cores, rss max %d MB\n", n, s / n, mx, min, (cn ? c / cn : 0), rss }' "$name.csv"
"$py" -c "import json,sys;[print(' ', f['world'].split(':')[1], f['kind'], {k: v for k, v in f['items'].items() if k in ('iron_ingot', 'white_wool', 'poppy')}) for f in json.load(open(sys.argv[1]))]" "$name.farms.json"
