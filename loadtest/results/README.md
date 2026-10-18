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

Run 10 lacks the first ~80 s (joining) because its monitor started late.
