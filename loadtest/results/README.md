# Load-test CSVs (18.10.2026, home PC Ryzen 7 5700X, Windows)

Columns: `time_s, players, tps_1m, mspt_avg_5s, mspt_max_5s` (sampled every 10 s over RCON by `monitor.py`).
50 bots at 5 blocks/s unless noted. "mixed" = 17 explorers + 33 near spawn; "allexplore" = all 50 explore.
"pregen" = world pre-generated with Chunky, radius 3000; explorers stay inside radius 2900.
Heap 3584M unless noted; "g1" = plain `-XX:+UseG1GC`, "aikar" = flags from `systemd/mc-survival.service`.

| File | Note |
|---|---|
| 00 | 19 bots walking at spawn (3 GB heap) |
| 01 | 19 bots flying 10 b/s into ungenerated terrain |
| 02 | 50 mixed, no pregen, 3 GB: OOM after ~4 min |
| 03 | same with BlueMap rendering stopped: crashed again (data until the hang) |
| 10 | pregen, mixed: baseline |
| 11 | same with spark sampling all threads (the dip is profiler overhead) |
| 12 | same with `gamerule locator_bar false` |
| 13 / 14 | all explore, view/sim 8/6 vs 6/4 |
| 15 / 16 | mixed with Pl3xMap rendering (its System.gc() not blocked) vs no map |
| 20–22 | mixed with prod flags: Pl3xMap rendering / no map / Pl3xMap paused |
| 23 / 24 | all explore with prod flags, map paused: default vs entity tuning on the main world |
| 25 | mixed with prod flags, squaremap 1.4.3 rendering |
| map-*.jpg | the same spot rendered by Pl3xMap (vintage_story) and squaremap |

Run 10 lacks the first ~80 s (joining) because its monitor started late.

## 24.10.2026: all worlds, working farms (`w*`)

60 bots by role over the main world, nether, farm world and farm nether (`world_bots.js`), test farms built by
`farms.py` (iron, wool, spawner). 15 min at night after all bots joined. Heap 6 GB unless noted, view 8 / sim 6
unless noted. Each variant adds to the one before. Columns now also have `cpu_cores, rss_mb` (`monitor.py`).
`*.farms.json` — what the farm chests got during the run; `*.entities.txt` — `paper entity list` per world at the end
(main world, nether, farm world, farm nether).

| Run | Note |
|---|---|
| w1 | baseline, heap 3584M: out of memory after ~6 min of full load |
| w2 | baseline, 6 GB |
| w3 | + farm world: monster cap 100, spawner mobs and villager golems without AI |
| w4 | + main world: fewer mobs, shorter entity activation range |
| w5 | + mob-spawn-range 4, main nether monsters every 5 ticks — best |
| w6 / w7 | w5 with view/sim 10/6 and 12/8 |
| w8 | w5 + `locator_bar false` |

spark profiles of w4 and w5 were read with `sparkprofile.py` (not kept: 2 MB each).
| w9–w11 | w5 + locator off + the small extras; same config three times without restoring the world: 68–74 ms (the world gets heavier) |
| w12–w15 | from one world snapshot: extras off / on / off / on = 72.0 / 70.8 / 70.8 / 72.1 ms — no measurable difference |

## 24.10.2026: Folia 26.3 vs Purpur (`f*`, `p5k`, `h*`)

Folia rows have three more columns: `folia_median_tps, folia_util_pct, folia_regions`; `tps_1m` is the slowest
region, `mspt_avg_5s` = utilisation / 2 (all tick work in ms per 50 ms), `mspt_max_5s` = the busiest region's MSPT.
Farm chests on Folia are read by the LtProbe plugin (`loadtest/probe-plugin`).

| Run | Note |
|---|---|
| f1, f2 | Folia, 4 threads, bases within 1500 (same snapshot as w12–w15); f2 with all our plugins |
| p5k | Purpur, main world pre-generated to 5000, bases 300–5000 apart (`LT_SPREAD=5000`) |
| f5k-* | Folia on the same: 4 threads / grid 4 and 8 threads / grid 3 |
| h1, h2 | as p5k / f5k-8t-g3 plus 5 nether hyperloop riders at 70 b/s (`LT_HYPERLOOP=1`), nether 3k pre-generated |
