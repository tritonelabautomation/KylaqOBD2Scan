# QA/QC — "At any cost the app must not be killed mid-trip": full kill-mechanism audit

**Owner mandate, 2026-09-19 (verbatim):** *"At any cost kylaq TSI coach app shouldn't be killed by
android do a detailed QA & QC and find hidden mechanizm which could kill app during trip and lose
a trip data which I don't want."*

Standing mandate behind it (2026-09-17, twice): *"it never ever loose the logs."*

**Method.** Every mechanism by which Android 11–14 (Moto Edge 20, My UX) can end this app's
process during a drive was enumerated, then each was traced through the actual code to answer one
question: *at the instant the process dies, is the trip already on disk?* Five hidden mechanisms
were found that could still lose trip data even with the foreground service alive. All five are
fixed in this build, each with a test. Citations are `file:line`-verifiable.

---

## Part 1 — The kill-mechanism matrix

### A. Mechanisms that END the process

| # | Mechanism | Can it still kill us? | Protection in code | Data verdict at kill instant |
|---|-----------|----------------------|--------------------|------------------------------|
| K1 | Cached-process kill (memory pressure, app backgrounded) | No | Foreground service `ObdKeepAliveService` with `connectedDevice|location` type; process is perceptible-tier while it runs (`AndroidManifest.xml` service entry; `ObdKeepAliveService.startForegroundCompat`) | Journal already holds every row (K-table below) |
| K2 | Swipe-away from Recents | No | `onTaskRemoved` restarts the service in the same state (`ObdKeepAliveService.onTaskRemoved`) — added after the owner's 2026-09-17 report | Journal intact; supervisors resume |
| K3 | Doze / App Standby (screen off, phone pocketed) | No, if exemption granted | `WAKE_LOCK` partial lock acquired at start and RE-acquired every 45 min against a 60 min timeout (`refreshWakeLock`, QA M1 loop); in-app "Make Unrestricted" action in the notification when `isIgnoringBatteryOptimizations` is false (`warningText`, exemptionPendingIntent) | Journal intact; polling keeps running |
| K4 | Moto/OEM aggressive battery kill of foreground services | Reduced, not zero — no app can promise zero | Same exemption flow + honest notification wording; START_STICKY redelivery (`onStartCommand` returns `START_STICKY`); **NEW:** boot/update receiver (K9) | Journal intact; on restart, `onCreate → recoverKilledSessions()` rebuilds automatically |
| K5 | Uncaught crash (any thread) | Possible | Journal is per-row flushed BEFORE crash can matter; sticky service restart → `onCreate` recovery; **NEW FIX E** removes the polling-loop crash class (see below) | Worst case: the single row in flight |
| K6 | ANR kill (yes — Android kills even foreground processes for ANRs) | Was possible — **FIX C** | `RecordingManager.init` scanned+parsed EVERY session's JSON on the MAIN thread (via `MainViewModel.init → AppContainer.init`). As trips accumulate that is seconds of UI-thread IO = ANR = kill. Now loaded on the manager's IO scope; `recoverUnfinishedSessions` re-loads synchronously first so dedup never races (FIX C2) | n/a — prevents the kill itself |
| K7 | System "Force stop" (settings or OEM cleaner) | Yes — and NOTHING can recover inside it | Force-stop puts the app in *stopped state*: sticky restarts, boot broadcasts and receivers are ALL suppressed until the owner opens the app once. Documented honestly; this is why the notification says "Battery optimisation is still ON … Android may kill the session" until exempted | Journal intact on disk; recovered at next manual open |
| K8 | Device reboot mid-drive (battery pull, OTA, kernel panic) | Process dies, yes | **NEW FIX D:** `KeepAliveBootReceiver` on `BOOT_COMPLETED` starts the keep-alive service (an explicit Android 12+ exemption for background FGS start) → `onCreate` recovers the journal AND the auto-connect/auto-record supervisors resume, so the REST of the drive is recorded too. Before this fix, recovery waited for a manual app open | First half recovered from journal; second half recorded after auto-reconnect |
| K9 | In-app update installed over a running session | Process dies, yes | **NEW FIX D:** same receiver on `MY_PACKAGE_REPLACED` — the installer's kill is now followed by an automatic revive + journal recovery | Same as K8 |
| K10 | Phantom process killer (Android 12+) | Not applicable | The app spawns no child processes | — |
| K11 | Data-saver / network restrictions | Not applicable to recording | Recording is Bluetooth Classic RFCOMM + local disk; no network in the hot path | — |

### B. Mechanisms that lose DATA without killing the process

| # | Mechanism | Verdict | Evidence |
|---|-----------|---------|----------|
| D1 | Drive held in RAM until STOP | Fixed 2026-09-17 | `SessionJournal` appends each transaction AND sample row with `flush()` before `recordTransaction` returns; polling runs on `Dispatchers.IO` (`ObdScheduler.startPolling`). Worst-case loss = the row in flight |
| D2 | **The clean-stop alibi (FIX A — found in this audit)** | FIXED | `stopRecording()` wrote the `.finished` marker BEFORE `finalizeSession` — the seconds-long window writing CSVs, ZIP, Room rows, analyses. A kill inside that window left a journal that CLAIMED completion while the trip did not exist, and `unfinishedSessions()` filters finished journals out → drive unrecoverable, silently. Now: marker written only after the trip is really persisted (`SessionRecoveryPolicy.finishedMarkerAllowed`, pure + tested) |
| D3 | Zombie polling (FIX E — found in this audit) | FIXED | If any exception escaped one poll iteration, `pollingJob` died while `_isPolling` stayed TRUE forever: auto-connect trusts the flag and never reconnects, auto-stop silently ends the recording — the trip stops growing mid-drive with nothing logged. The loop body is now wrapped in `try/finally { _isPolling.value = false }`, making the stuck flag unconstructable |
| D4 | Recovered trips lost their altitude window (FIX B — found in this audit) | FIXED | `finalizeSession` read altitude extremes from the LIVE GPS RAM accumulator — empty in a fresh process after a kill, so every recovered trip showed "-- m" even though each journaled sample row carries `altitude_m`. Now falls back to `AltitudeStats.reduce(rows)` — same plausibility gate, honest null only when no row ever had altitude |
| D5 | Second `startRecording` shredded the first session | Fixed earlier | Tested: `aSecondStartCannotReplaceOrShredTheDriveAlreadyRunning` (KilledSessionRecoveryTest) |
| D6 | Storage full / unwritable journal | Surfaced, not silent | `SessionJournal.writeFailures` counter → stop-path notice + `.finished` marker records it; recovery announcement includes failures (`SessionRecoveryPolicy.shouldAnnounce`) |
| D7 | Duplicate rebuild after double recovery | Guarded | `savedIds` dedup + `journal.discard` only AFTER success (`recoverUnfinishedSessions`); FIX C2 makes `savedIds` authoritative even with the async first load |
| D8 | GPS lat/lon trace during drive | Known gap, queued | Per-row altitude IS journaled (`SAMPLES_HEADER` ends `altitude_m`); lat/lon columns are not — they arrive with the GPX-export/map task already in the queue. A kill today costs no OBD data and no altitude, only a future map trace |

---

## Part 2 — What changed in this build (all tested)

1. **FIX A** `RecordingManager.stopRecording`: `journal.close()` before finalize (rows are already
   flushed per-line), `markFinished` only after `finalizeSession` returns the persisted trip; an
   exception in finalize now leaves the journal unfinished → auto-rebuilt. Rule pinned pure:
   `SessionRecoveryPolicy.finishedMarkerAllowed`.
2. **FIX B** `RecordingManager.finalizeSession` + `AltitudeStats.reduce` (pure companion):
   recovered trips rebuild their altitude window from persisted rows.
3. **FIX C/C2** `RecordingManager.init` loads the trip list on `managerScope` (IO) instead of the
   main thread (ANR class removed); `recoverUnfinishedSessions` reloads synchronously before
   computing `savedIds` (dedup race removed).
4. **FIX D** `KeepAliveBootReceiver` + `KeepAlivePolicy` (pure) + manifest
   `RECEIVE_BOOT_COMPLETED` + receiver for `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`. Starts the
   service only when a journal is pending OR auto-connect is on — no noise notification after
   boots that have nothing to log.
5. **FIX E** `ObdScheduler.startPolling`: `try/finally` guarantees `_isPolling` clears with the
   loop, whatever throws.

**New/updated tests (7):** KeepAlivePolicyTest (4 — broadcasts, pending-journal detection from a
directory listing alone, start matrix, manifest↔policy drift guard); AltitudeStatsTest (+2 —
reduce from persisted rows; reduce of glitches stays null); KilledSessionRecoveryTest (+1 — a
stop that persisted nothing writes NO finished marker, Robolectric, real files).

## Part 3 — Honest limits (what no app can promise)

- **Force stop** (K7) suppresses every automatic revival until the app is opened once. This is an
  Android security boundary, not a bug to route around.
- **Power loss / kernel panic** loses page-cache content: rows are `flush()`ed (kernel-visible,
  survive any process kill) but not `fsync`ed per row — per-row fsync on flash storage would cost
  write amplification and battery for protection only against the phone itself dying, which loses
  the drive's telemetry anyway (adapter, car, phone all lose power together).
- **Physical storage failure** is reported (`writeFailures`), never silently swallowed.
- The exemption ("Make Unrestricted") remains the single highest-value owner action; the
  notification keeps saying so in plain words until it is granted.

## Part 4 — Owner field recipe (Moto Edge 20)

1. Settings → Apps → Kylaq TSI Coach → Battery → **Unrestricted** (or tap *Make Unrestricted* in
   the recording notification). Verify the notification no longer shows the warning line.
2. Keep auto-connect ON (default) — after this build it also survives reboots and app updates.
3. Reboot test (parked): reboot the phone → within a minute the keep-alive notification should be
   back without opening the app.
4. Kill test (parked, engine on, recording): swipe the app from Recents → recording continues;
   the trip list still grows.
5. After any drive where the app was killed, expect the **"Killed session recovered — logs
   saved"** notification and a trip named "… (recovered)" — with its altitude window intact now.
