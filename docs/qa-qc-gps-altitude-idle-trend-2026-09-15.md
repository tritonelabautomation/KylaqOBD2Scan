# QA/QC — GPS altitude in trip summary + idle-burn trend (2026-09-15, sub-task T)

Owner question (screenshots, trip "Kylaq Run 2026-09-15 07:26"):
**"Why altitude is not taken from GPS why it's empty in trip summary?"**
and the trends card reading **"idle 0.00 → 0.00 L/h (model 1.05) · latest -100% vs model"**
while the same trip's fuel card shows real idle burn (0.068 L over 347 s ≈ 0.71 L/h).

## Finding 1 — altitude was captured live but never persisted

- `GpsManager` has always published `GpsData.altitudeMeters` from every accuracy-gated fix.
- `RecordingManager` never aggregated or stored it; `trips` had no altitude columns;
  `TrackerDetailCards` therefore rendered hardcoded `"-- m"` cells with the honest-blank
  footnote (Batch-17 doctrine: never invent metres).
- So the trip summary was not "ignoring GPS" — the persistence layer simply did not exist.

### Fix (end-to-end)

| Layer | Change |
| --- | --- |
| `analysis/AltitudeStats.kt` (new) | Pure-JVM per-trip min/max accumulator with plausibility gate (−500…9500 m). |
| `data/GpsManager.kt` | `tripAltitude` fed only fixes passing the 40 m accuracy gate **and** `hasAltitude()`; reset per `startTracking()` (also resets distance/last fix). |
| `data/db/entities/TripEntities.kt` | `maxAltitudeM` / `minAltitudeM` nullable columns on `trips`. |
| `data/db/Migrations.kt` | `MIGRATION_9_10` (ALTER TABLE, nullable — existing trips keep NULL). |
| `data/db/AppDatabase.kt` | version 10 + migration registered. |
| `di/AppContainer.kt` | `tripAltitudeStats()` accessor (null-safe before init). |
| `data/RecordingManager.kt` | persists min/max into the trip row at `stopRecording`. |
| `ui/screens/TrackerDetailCards.kt` | renders real `"612 m"`-style values + "Altitude dif. (GPS)" = max − min; footnote switches: persisted trips → "accuracy-gated GPS fixes (≤ 40 m)"; NULL → honest blank explaining why (pre-2026-09-15 trip or no GPS fix). |
| `ui/screens/TripDetailScreen.kt` | passes the persisted values from the loaded `TripEntity`. |

Honesty rule preserved: trips recorded before this change (including the 07:26 run the
owner screenshotted) keep `-- m` with the explanatory footnote — altitude is retro-
fitted from measurement only, never estimated. New recordings show real metres.

## Finding 2 — idle-burn trend starved of fuel samples (pid-form gap)

- The recorder stores sample pids in 2-hex form (`"9D"`), and `computeTripTrends`
  normalizes them to 4-hex — but the Room projection `trendSamples()` only carried
  2-hex fallbacks for `"0C"`/`"0D"`. Fuel rows (`"9D"`/`"5E"`) never reached
  `TripTrendAnalyzer`, so `idleFuelL` stayed 0 → `idleActualLh = 0.00` and
  "latest −100% vs model", contradicting the trip fuel card (≈0.71 L/h).

### Fix

- `TripTrendAnalyzer.TREND_PROJECTION_PIDS` now lists every analyzed pid in **both**
  stored forms (`010C/0C, 010D/0D, 0104/04, 0162/62, 015E/5E, 019D/9D`);
  `TripRepository.trendSamples` uses that single source of truth.
- After the fix the same trips recompute to their real idle rate (~0.7 L/h, ≈ −33 % vs
  the 1.05 L/h model) with no UI change needed.

## Tests

- `AltitudeStatsTest` (new, 5): null-when-empty, realistic profile min/max/range,
  single-fix zero range, glitch rejection, per-trip reset.
- `TripTrendAnalyzerTest` (+2): projection carries both hex forms for every pid;
  idle window aggregates real L/h from normalized 2-hex mass-flow samples.

---

## Finding 3 — "I logs my logs it didn't save" (unsaved recordings are recoverable)

A recording accumulates in RAM (`RecordingManager.activeTransactionList`) and is
finalized only when STOP fires — the manual tap or the auto-record engine-off timer.
If Android kills the process mid-drive (swipe-away, battery optimization, crash), the
trip never reaches Room and never appears in Trips & Recordings.

**But the drive is still on the phone.** `RawLogManager` flushes every TX/RX line to
`files/raw_logs/raw_log_<sessionId>.txt` as it happens (`FileWriter.write` + `flush`
per entry), independent of the RAM buffer.

### Fix — rebuild the trip from the raw log, on-device

| Layer | Change |
| --- | --- |
| `analysis/RawLogRecovery.kt` (new) | Pure-JVM parser: session id from file name; both stored line shapes (`RX < 7E804410C0F28` and `RX < 7E8 04410C0F28`); time-of-day re-anchored to the file's last-modified day with a midnight-wrap rule; keeps RX Mode-01 single frames only (TX requests, `7F` negatives, multi-frame/flow-control PCI, and ELM noise lines such as `SEARCHING...` are skipped). |
| `data/RecordingManager.kt` | `findUnsavedRawLogs()` (raw logs with no saved session) and `recoverFromRawLog()` — re-decodes every frame through `StandardPidCatalog` + `PidDecoder` and reproduces exactly what `stopRecording()` writes: session dir, transactions/samples CSV, JSON, ZIP bundle, Room trip + samples, AI Doctor analysis. |
| `ui/viewmodel/MainViewModel.kt` | `unsavedRawLogs` / `isRecovering` state, `refreshUnsavedRawLogs()` (off-main), `recoverRawLog()`; refreshed after every stop. |
| `ui/screens/RecordingsScreen.kt` | Amber recovery banner in Trips & Recordings listing each unsaved session (id, date, size) with a **Recover** button. |

Recovered trips keep `maxAltitudeM`/`minAltitudeM` NULL — GPS altitude is not in a raw
OBD log, so the trip summary shows its honest blank rather than inventing metres.

Tests: `RawLogRecoveryTest` (11 new) — session-id extraction, both body shapes, frame
accept/reject matrix, 970 rpm decode parity with the reference trace, whole-file
filtering and ordering, day anchoring, midnight wrap, empty/noise logs.

## 2026-09-15 follow-up — altitude reached the database but not the trip log

Owner question: *"did you add altitude info from GPS into trip log?"* Auditing the answer
found a real gap, fixed in the same pass.

**What was already true:** `GpsManager.tripAltitude` aggregates accuracy-gated fixes that
report altitude, `RecordingManager.stopRecording()` persists them to `trips.maxAltitudeM` /
`trips.minAltitudeM` (MIGRATION_9_10), and the trip summary shows **Max altitude** /
**Altitude dif.** with an honest `-- m` blank when there was no fix.

**What was missing:** the trip *log files* never carried it.

| File | Before | Now |
| --- | --- | --- |
| `<id>.json` (`sessionMetadata`) | no altitude keys | `maxAltitudeM` / `minAltitudeM`, explicit JSON `null` when never captured |
| `<id>_samples.csv` | no elevation column | `altitude_m` appended as the last column (blank = no fix), existing column order untouched |
| imported trip row | altitude dropped; every restored trip stamped `now` and `durationSeconds = 60` | altitude restored from the JSON; `startTimestamp` / `endTimestamp` / `durationSeconds` parsed from the recorded `startTimeUtc` / `endTimeUtc` via new pure `data/SessionTime.kt` |
| live dashboard `Altitude` row | printed the `0.0` default as `0 m` when a fix had no altitude | prints `-- m` unless `GpsData.hasAltitude` |

Why it mattered now: the owner is about to do the one-time migration for in-app updates
(backup → uninstall → install the stable-signed build → import). Altitude that was not in
the backup would have been stripped from **every historical trip** by that import, and the
hard-coded 60 s duration made all restored trips look like one-minute drives that had just
ended — which also corrupts everything derived from duration (average speed, idle share,
L/h trends).

Honesty rules kept: no altitude is stored as `null` / an empty cell, never as `0.0`; a
`0 m` reading and a missing reading stay distinguishable end to end; recovered trips
(rebuilt from raw OBD logs) keep `NULL` because a raw log contains no GPS.

Tests: `TripLogAltitudeTest` (10 new) — JSON round trip, explicit-null encoding, legacy
files without the keys, CSV column position and blank cells, `GpsData` default honesty,
timestamp parsing with and without milliseconds, garbage rejection, inverted/unknown
windows, and metadata copies not back-filling a default.
