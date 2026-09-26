# QA/QC — recovered trips kept the uptime clock; altitude footnote lied (2026-09-20)

## Owner report

Screenshot of trip **"Kylaq Run 2026-09-20 13:25 (recovered)"** with `-- m` max altitude and
the footnote claiming *"this trip was recorded by an older build whose recording service
was not a location-type foreground service…"* — on a drive that happened **the same
afternoon**, plus: *"Altitude still not logging in"*.

Good news first: the trip existing at all means recovery worked — a drive the crash loop
cut off was rebuilt automatically, named, and complete (6.1 km, 25 min, 15/51 km/h).

## Root cause found (code-proven, not guessed)

1. The OBD transports stamp every row twice: `timestampUtc` = the real IST instant, and
   `timestampMonotonic` = `SystemClock.elapsedRealtime()` — **milliseconds since boot, not
   an instant** (`Elm327Transport.kt:270`, `SimulationTransport.kt:499`).
2. `RecordingManager.finalizeSession` derived the Room trip window from the MONOTONIC
   column (`txList.minOf { it.timestampMonotonic }`), while its comment claimed "every row
   carries its own epoch". For every trip that window is therefore an uptime value —
   an epoch around 1970.
3. `recoverJournalSession` did the same for `startTimeUtc`, and replayed wide rows from
   the uptime-stamped transactions.
4. The altitude footnote (`BackgroundLocationPolicy.altitudeBlankReason`) is era-aware:
   `tripStartMs < FIX_LIVE_SINCE_MS (2026-09-18 IST)` ⇒ "older build" wording. A 1970
   window trips that check on **any** trip whose altitude is blank — hence the lie in the
   screenshot, on a same-day drive.
5. Side effects of the same defect: car-pool↔trip linkage (`TripWindow(startTimestamp,
   endTimestamp)`) could never match a real ride instant; trip ordering fields and any
   date math over the Room window were all uptime garbage.

## Fix shipped

- `SessionRecoveryPolicy.wallEpochMs(stamp, monotonicMs)` — the single honest instant of a
  row: parse the IST stamp; fall back to the monotonic value only for rows written without
  one (very old journals).
- `finalizeSession` (all four callers: STOP, orphaned restart, journal recovery, raw-log
  recovery) now derives start/end from `wallEpochMs`.
- `recoverJournalSession` re-anchors every journaled row to its stamp (`wallTx`) before the
  window, the wide-row replay and the Room insert see it — so replayed samples carry the
  same clock as samples read from the journal's samples CSV (which always parsed stamps).
- GPS belt in `ObdKeepAliveService`: a process that restarts MID-drive resumes the
  unfinished journal **without** passing the START_RECORDING edge — the only place the
  auto-record loop used to start GPS. Every tick now corrects "recording but not tracking"
  via the new `GpsManager.isTrackingNow`, so a restarted drive still gets fixes.

## Regression test

`KilledSessionRecoveryTest.aRecoveredTripKeepsTheWallClockNotTheUptimeClock`: frames whose
monotonic column is 3 days of uptime while their stamps say 2026-09-20 13:25 IST; after a
simulated kill + recovery the Room window must equal the stamp instants exactly, and must
sit after `FIX_LIVE_SINCE_MS` so the footnote can never call a 2026 drive pre-fix again.

## Honest boundary on "altitude still not logging"

The window bug explains the lying footnote and the broken linkage — but for THIS trip the
blank altitude itself is real: no accuracy-gated (≤ 40 m) GPS fix with altitude reached the
recorder during that drive. Whether that was GPS off, cover/pocket attenuation, a permission
level, or the mid-drive restart gap fixed above cannot be proven from this sandbox; the
dashboard shows the live GPS verdict (`OK / NO_FIX / PROVIDER_OFF / PERMISSION_DENIED`)
while recording, and the corrected footnote now names the right modern cause instead of an
older build. Next drive: watch the GPS verdict chip once at the start — if it reads OK and
altitude still ends blank, the per-row altitude column in the journal will show it and the
next fix targets the gate itself.
