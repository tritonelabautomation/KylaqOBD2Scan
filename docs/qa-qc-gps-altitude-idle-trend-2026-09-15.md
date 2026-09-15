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
