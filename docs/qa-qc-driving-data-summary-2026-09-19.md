# QA/QC — Driving Data summary: the cluster's three tabs, in the app (2026-09-19)

Owner sent three photos of the Kylaq infotainment "Driving Data" screen (SINCE START / SINCE
REFUEL / LONG-TERM) and asked: *"Like this any fuel summary?"* and *"Are you able to read
current ODO?"*

## Answers

**ODO: yes.** PID 01A6 (J1979 odometer, 0.1 km/bit — the car answered 3453.9 km on 2026-09-16)
is in the default poll set (`DefaultPidDefinitions.getDefaults()` references
`ProvenChannels.ODOMETER`), so every recording stores it per sample. It already feeds trip
distance and the ±3 km receipt match. What was missing was *showing* it: the Driving Data card
now has an **Odo now** tile — newest 01A6 row of the live session while recording, newest stored
row otherwise (`TelemetrySampleDao.latestNumericFor`).

**Fuel summary: SINCE REFUEL existed; SINCE START and LONG-TERM did not.** The Fuel & Costs
"Since refuel" card is now a three-tab Driving Data card mirroring the cluster:

| Tab | Source | Refresh |
|---|---|---|
| SINCE START | live session rows from RAM while recording; last COMPLETED trip's stored samples otherwise (the cluster resets at ignition — the app keeps showing the finished drive until the next starts) | 5 s ticker while the tab is open |
| SINCE REFUEL | rows since the newest detected refuel event (unchanged logic) | on data refresh / new event |
| LONG-TERM | `samplesSince(0)` — everything the app has ever recorded | on data refresh / tab open |

Tiles per tab: **Consumption (km/l), Time** (cluster wording: "1 Days" above a day, else
"17:54 h"), **Trip (km), Avg speed (km/h)** — plus **Odo now** and **Range (est)**.

**One integrator, three windows.** All tabs run `SinceRefuelStats.summarize` — distance from the
monotonic odometer max−min (ledger 8.5: raw 01A6 frames regress on CAN transients, an odometer
cannot unwind), fuel from the rate PIDs integrated over their own timeline. The tabs therefore
can never disagree about a litre, and LONG-TERM is not a sum of per-trip guesses.

**Range is labelled est, always.** `SinceRefuelStats.rangeKm(levelPct, capacityL, kmPerL)` =
level % × calibrated capacity × the selected tab's km/L. Level % is a sender curve, not a
dipstick, and capacity is calibration-derived — so the tile says "Range (est)" and shows "--"
when any input is missing (`SinceRefuelRangeTest` pins both behaviours). No fabricated distance.

## Verification

- `SinceRefuelRangeTest` (2 tests): 50 % × 50 L × 10 km/L = 250 km; null level, null km/L and
  zero km/L all yield null.
- CI census at commit: see PR digest.
- Field check for the owner: Fuel & Costs now opens on the SINCE REFUEL tab with his real
  numbers; LONG-TERM should read close to the cluster's long-term (his photo: 632 km, 10.2
  km/l, 1 day, 22 km/h) for the same window — small differences are expected and honest: the
  cluster integrates injector models, the app integrates measured rate PIDs over recorded time.

## What is NOT claimed

- LONG-TERM covers **what the app recorded**, not the car's lifetime: history starts at first
  install. The cluster's long-term resets only manually; the two windows are not the same span.
- SINCE START when not recording shows the LAST COMPLETED trip, not "this ignition" — labelled
  by the tab's own meaning (since this start), and the live ticker resumes the moment a new
  session records.
- The range tile is an estimate by construction; pump litres remain the only measured fuel.
