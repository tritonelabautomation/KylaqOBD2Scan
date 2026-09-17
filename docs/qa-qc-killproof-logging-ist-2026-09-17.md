# QA/QC — Never lose a drive again, and every record in IST

**Date:** 2026-09-17 (IST)
**Branch:** `arena/01a07c37-kylaqobd2scan`
**Trigger:** two owner messages the same day, the second one angry and both of them about losing data.

> *"For all records use IST time only no UTC"*

> *"again today logs not saved unable to recover it why the hell kylaq TSI coach app is killed in
> background it supposed to be service right even in background all ways it should run and record it
> never ever loose the logs"*

**Verdict up front.** He is right on both counts, and the first one is not bad luck — it is the
design. The recorder kept the entire drive in two RAM lists and wrote its first byte to disk inside
`stopRecording()`. A foreground service lowers the chance of a kill; it does not make the process
unkillable, and no Android app can. A design that *needs* the process to survive in order to keep
its data is a design that loses data. That is fixed: the trip is now written while it happens, and
recovered by itself on the next start. Separately, three writers were stamping **IST wall time and
labelling it `Z`** — lying about their own zone by five and a half hours. All record time now goes
through one clock, in IST, with the offset printed.

---

## 1. What actually happened to today's logs

Six independent defects, all of which had to be present for a whole day to vanish. Evidence is the
pre-fix source at commit `0096655`.

| # | Defect | Where | Effect |
|---|--------|-------|--------|
| 1 | Trip accumulated in RAM only; **all** files (both CSVs, session JSON, ZIP, Room trip row, Room telemetry rows, AI analysis) written inside `stopRecording()` | `RecordingManager.kt`, `activeTransactionList` / `activeSampleList`, single write site in `stopRecording()` | Any kill before STOP = **total loss** of the drive |
| 2 | `startRecording()` called `clear()` on both lists unconditionally | `RecordingManager.kt:122-125` (pre-fix) | A second START — auto-reconnect, screen re-entry, retry after a dropped link — **silently deleted the drive in progress** |
| 3 | Raw log written only while a session file was open, and every write failure swallowed by `catch (_: Exception) {}` | `RawLogManager.onRawLog` | Frames between CONNECT and RECORD existed only in a 1000-entry RAM ring buffer; a full disk produced a log that simply stopped growing, with nothing anywhere saying so |
| 4 | Raw log lines carried time-of-day only (`19:33:16.009`), no date | `RawLogManager.timeFormatter` | Recovery had to **guess the calendar day** from the file's `lastModified` with a ±12 h midnight rule — wrong for any log recovered days later, copied off the phone, or touched by the OS |
| 5 | Recovery was **manual**, and the banner lived on one screen | `RecordingsScreen.kt:185`, `refreshUnsavedRawLogs()` called only there and in `MainViewModel` init | The owner never saw it. A rescue that has to be noticed is not a rescue |
| 6 | `onTaskRemoved` not overridden; battery-exemption prompt was one-shot and gated on a live session | `ObdKeepAliveService.kt`, `BatteryOptimizationPolicy.shouldPrompt` | A swipe-away left the service's fate to the OEM; one dismissed system dialog silenced the exemption request **forever** while Motorola kept killing it |

Defect 6 is why the service did not save him. The class doc claimed *"START_STICKY plus the existing
auto-connect loop recover everything even in the rare kill case"* — with defect 1 in place that
sentence was false: the loop could reconnect the socket, but there was nothing on disk to recover.
The doc is corrected rather than left to overpromise.

---

## 2. The fix

### 2.1 Write the trip while it happens — `SessionJournal` (new, 220 lines)

Append-only, flushed per row, in `files/recordings/journal/`:

```
<sessionId>_transactions.csv   live, append-only, byte-identical to the export schema
<sessionId>_samples.csv        live, append-only, byte-identical to the export schema
<sessionId>.meta               what/when, in IST, plus the epoch so no clock has to be guessed
<sessionId>.finished           written ONLY by a clean stopRecording()
```

`RecordingManager.recordTransaction()` now appends the transaction **and** its wide sample row and
flushes before returning (`RecordingManager.kt:298-305`). The write happens outside the list lock —
file I/O must never hold the lock the UI reads through.

**Worst-case loss is the row in flight, not the trip.** The journal is opened *before* the first OBD
line can arrive (`startRecording`, DATA-LOSS FIX 2), so even the first frame of a drive is on disk.

The schema is not a second format. `CsvExporter` now owns `TRANSACTIONS_HEADER`, `SAMPLES_HEADER`,
`transactionRow()` and `sampleRow()`; the batch exporter and the live journal both call them, and
`readTransactionsFromCsv()` / `readSamplesFromCsv()` read back what either wrote. Two copies of a
header string is how a journal ends up unreadable by the importer that reads the finalized file —
there is now exactly one. A test asserts the two files are byte-for-byte equal
(`theJournalIsByteForByteTheExportSchema`).

The `.finished` marker is the whole detection mechanism: its **absence** means the process died. No
flag to forget to set, no state to lose along with the process.

### 2.2 A restart no longer eats the session in progress

`startRecording()` snapshots both lists under the same lock it clears them in, and persists the
orphan on the manager scope before the new session opens (DATA-LOSS FIX 1). Belt and braces: at
STOP the recorder compares RAM against the journal and keeps the **longer** list
(`SessionRecoveryPolicy.preferLonger`). They differ when auto-reconnect restarted the process
mid-drive — the new process only ever saw the rows recorded since it came up, while the journal
holds the whole drive. Equal lengths keep RAM, because RAM carries what the CSV round trip drops
(per-line GPS altitude, ELM command text).

### 2.3 Recovery runs itself

`RecordingManager.recoverUnfinishedSessions()` is called from two places that need no human:

- `MainViewModel.init` — every app start;
- `ObdKeepAliveService.onCreate` **and** `onStartCommand` — which covers the case that matters most:
  Android restarting a `START_STICKY` service in a **fresh process** after a kill, with no UI open
  at all.

It rebuilds each cut-off session from the journal (decoded rows — parameter, value, unit, status,
per-line altitude — so nothing is re-parsed or re-guessed), falls back to the raw log for sessions
recorded by an older build or when the journal could not be opened, discards the journal once the
trip exists so it is never rebuilt twice, skips the session this process is recording right now, and
archives corpses so a lost session is reported once instead of on every launch.

The rebuilt trip is a **normal** trip: Room row `COMPLETED`, telemetry rows, both CSVs, JSON, ZIP,
AI analysis — and, unlike the old raw-log path, a **populated** samples CSV. That path used to write
`exportSynchronizedSamplesToCsv(file, emptyList())`, so a recovered trip came back with no curves,
no fuel trend and no X-ray wide rows. All four callers (STOP, orphan save, journal recovery, raw-log
recovery) now share one `finalizeSession()` + `persistTripToRoom()`, because they had already
drifted and drift between copies of the same job is how a trip ends up half saved.

Results are announced, not buried: a notification from the service ("Killed session recovered — logs
saved"), a line on the Dashboard, and a status card in Settings. Silence is reserved for "nothing
was pending" (`SessionRecoveryPolicy.shouldAnnounce`) — a notice on every launch would train the
owner to ignore the one that matters.

### 2.4 Raw log lines carry their own date

`RawLogManager` writes `yyyy-MM-dd HH:mm:ss.SSS` in IST, and `RawLogRecovery` gained a `DATED_LINE`
pattern that uses the date the line states. The undated shape still parses forever, so the owner's
existing logs are not orphaned; a mixed log reads each line by its own shape. The midnight heuristic
survives only as the fallback for legacy lines.

Two further defects in the same file were fixed while in there:

- The stamp printed was `Date()` — the moment the *listener ran*, not the moment the frame went on
  the wire. Under load those differ, which quietly corrupts every duration measured from the log. It
  now uses the transport's own record stamp.
- The transports pass `SystemClock.elapsedRealtime()` as `timestampMonotonic` here, while
  `TransactionRecord.timestampMonotonic` elsewhere is epoch millis. **Same field name, two different
  clocks.** The raw log therefore takes its wall time from the record stamp and never from the
  monotonic argument, which would otherwise print 1970.

While testing this, a third defect turned up in the recovery parser itself: a compact log body
whose length is odd (`7E804410D50`) cannot be byte-aligned hex, so it must still be carrying its
3-character CAN id. The old splitter handed all nine characters to the hex check, which rejects odd
lengths, so the **fused** form of every single-byte answer was dropped - 0D vehicle speed, 04 load,
0F intake air temp, 11 throttle, 46 ambient. A log recovered from a reference trace or an owner
paste came back with RPM and no km/h. `RawLogManager` writes the *separated* form
(`7E8 04410D50`), which always split correctly, so the app's own logs were not losing speed; both
shapes work now, and stripping is gated on a `7xx` id so junk is never mangled into a plausible
frame.

Write failures are counted and exposed (`writeFailureCount`, `lastWriteFailure`) and surfaced at STOP
and on the Settings card, instead of being swallowed.

### 2.5 Adapter traffic is logged from CONNECT, not only from RECORD

`startConnectionLogging()` writes `conn_log_<yyyy-MM-dd>.txt` from the moment the link is up
(`MainViewModel`'s connection collector). The prefix is deliberately **not** `raw_log_`, so
`RawLogRecovery.sessionIdOf` never reads it as a session and recovery never invents a trip out of an
idle connection. Seven-day retention, pruned on open; session logs are never pruned because those are
trips.

### 2.6 Surviving the swipe, and saying when it is exposed

- `ObdKeepAliveService.onTaskRemoved` restarts the service with the same recording state. The
  inherited implementation did nothing, so removing the app from Recents left the session's fate to
  the OEM.
- The keep-alive notification now states plainly when the battery exemption is missing and carries a
  **"Make Unrestricted"** action (standard dialog, falling back to app details on OEM firmware that
  lacks it). `warningText(exempted)` is pure and tested: exempted → no warning, not exempted → say so
  and offer the fix.
- `BatteryOptimizationPolicy.shouldPrompt` no longer goes silent forever. Granted exemption is the
  only terminal state; otherwise it asks again after 24 h, and it asks even with no session live,
  because the exemption protects the *next* drive too. `MainActivity` records
  `battery_exempt_prompted_at` for the interval. The old behaviour — one prompt, only mid-session,
  then silence for the life of the install — is precisely how the owner came to be running
  unprotected.
- Settings → DATA & BACKUP → **"Never lose a drive"** card: three honest status rows (battery
  optimisation, keep-alive service, disk writes) plus the exemption button and a *Recover killed
  sessions now* button. Green only where protection is actually in place.

---

## 3. IST everywhere — and three stamps that were lying

`RecordTime` (new, 133 lines) is the single clock. `stamp()` → `2026-09-17T14:27:05.123+05:30`,
`logStamp()` → `2026-09-17 14:27:05.123`, `display()` → `2026-09-17 14:27`, `zoneLabel()`/
`offsetLabel()` → `IST` / `+05:30`. Zone is `Asia/Kolkata` explicitly, not "whatever the device
says", and **the offset is printed with every stamp** so a trip file copied elsewhere cannot be
misread.

Writers converted (14):

| Area | File | Was | Now |
|------|------|-----|-----|
| Every OBD transaction | `ObdScheduler.getNowStamp()` (was `getNowUtc`) | UTC `…Z` | IST `+05:30` |
| Session start/end, recovery stamps | `RecordingManager.iso()` (was `isoUtc`) | UTC `…Z` | IST `+05:30` |
| Raw log record stamp | `Elm327Transport.logRaw`, `SimulationTransport.logRaw` | UTC `…Z` | IST `+05:30` |
| Raw log line prefix | `RawLogManager` | `HH:mm:ss.SSS`, no date | `yyyy-MM-dd HH:mm:ss.SSS` IST |
| Discovery run stamp | `EcuDiscoveryManager` | UTC `…Z` | IST `+05:30` |
| Discovery log lines | `PidDiscoveryService.appendLog` | `HH:mm:ss.SSS`, no date | dated IST |
| Discovery export `"timestamp"` | `PidDiscoveryService` | **local time labelled `Z`** | IST `+05:30` |
| Fleet export `"generated_utc"` | `FleetExporter` | UTC `…Z` | IST `+05:30` |
| Backup snapshot stamp | `PrefsSnapshotter` | UTC `…Z` | IST `+05:30` |
| AI analysis row stamp | `TripRepository.runAiCarDoctorAnalysis` | **local time labelled `Z`** | IST `+05:30` |
| ZIP import fallback stamp | `ZipImporter` | **local time labelled `Z`** | IST `+05:30` |
| Fuel log / expense / service log / trip expense | `FuelCostsScreen`, `ExpensesScreen`, `MaintenanceScreen`, `TripsScreen` | UTC `…Z` | IST `+05:30` |
| Coast & ride insight logs | `MainViewModel.persistDriveInsights` | UTC `…Z` | IST `+05:30` |
| Fuelio import date parsing + re-stamp | `FuelioImporter` | naive dates parsed **as UTC** | parsed as IST |

Three of those were worse than "wrong zone": `SimpleDateFormat("…'Z'")` with **no** `timeZone` set
formats in the device zone and then prints a literal `Z`. The stamp said UTC and held IST. That is
the exact disagreement the owner has been staring at — the discovery export's
`"timestamp": "…T08:58:05.839Z"` against a raw log line reading `[08:57:10.520]`. Two clocks in one
investigation, one of them mislabelled. It is fixed, not explained away.

Two date-only bugs came out of the same audit:

- `DocumentsScreen` parsed an insurance/RC **expiry date** as UTC midnight, i.e. 05:30 the previous
  evening in IST — a document shown as expiring a day early. Now parsed in the record zone.
- `FuelLogCodec`/`FleetExporter` group and display on `dateUtc.take(10)`, so a fill-up made before
  05:30 IST was filed under the **previous date** in the fuel log, km/L trends and fleet sheet.

**Reading is tolerant forever** (`RecordTime.parseMillis`): explicit offset honoured, trailing `Z`
read as UTC (every file written before this change), naive stamp read as IST. Unparsable → `null`,
never `0`, because a zero silently becomes 1970 and corrupts every duration computed from it.

The parser recognises the stamp's **shape** and then parses exactly that shape, rather than trying
patterns in order until one takes. That distinction is not pedantry: `SimpleDateFormat.parse` reads a
*prefix* and ignores the rest, so a shortest-patterns-last ordering let `yyyy-MM-dd HH:mm` swallow
`2026-09-17 14:27:05.123`, stop at the minutes, and return the instant of `14:27:00.000` — 65 s and
123 ms off every naive stamp in a recovered trip, invisible in the file. CI caught it
(`RecordTimeIstTest.naiveStampsAreReadAsIstBecauseThatIsWhatTheyAlwaysWere`); it is now pinned by
`noShapeLosesItsFractionOrItsSecondsToAGreedyShorterPattern`, including 1- and 2-digit fractions,
which are tenths and hundredths and must be padded, never truncated.
`SessionTime.parseMillis` now delegates to it, so an imported trip keeps the window it was recorded
in — the bug that helper was originally created to kill. A history that spans the upgrade has no
five-hour tear in it; a test mixes a legacy `…Z` start with an IST end and asserts the duration.

**Display forms are cut from the instant, not from the string.** Four screens rendered a stamp with
`timestampUtc.takeLast(12).removeSuffix("Z")` (`RawMonitorScreen`, `PidDetailScreen` ×3,
`DashboardScreen`) and one with `startTimeUtc.take(19).replace("T", " ")` (`RecordingsScreen`). That
is arithmetic on a string whose length and suffix the mandate just changed: against
`2026-09-17T14:27:05.123+05:30`, `takeLast(12)` prints `05.123+05:30`. `RecordTime.timeOfDay()` and
`.dateTime()` now parse and re-format, so every shape this app has ever written renders correctly —
and a legacy `…Z` stamp renders as IST wall time, which is what the owner reads.

**Schema keys are unchanged.** `timestampUtc`, `startTimeUtc`, `endTimeUtc`, `dateUtc`,
`generated_utc` keep their historical names: session JSON, both CSV headers, the ZIP bundle, the Room
`telemetry_samples.timestampUtc` column and every backup the owner already holds use them, and
renaming would orphan his whole trip history for a cosmetic gain. The name is now a documented
artefact; the value is IST. Documented at the field (`TransactionRecord`, `SynchronizedSample`,
`RecordingMetadata`), at the writer (`JsonExporter`) and in `RecordTime`.

Enforcement is a build rule, not a code-review comment: `IstOnlyRecordTimeTest` scans every
production source file and fails on `TimeZone.getTimeZone("UTC"|"GMT")` or a `SimpleDateFormat`
pattern containing a literal `'Z'`, with `RecordTime.kt` allow-listed (it must still *read* legacy
`…Z` stamps — reading old UTC is compatibility, writing new UTC is the bug). A second test fails if
an allow-list entry stops pointing at a real file, so the allow-list cannot rot into a blind spot.
Residual UTC writers in `app/src/main`: **0** outside `RecordTime`.

---

## 4. What I cannot promise, and what only the owner can do

Honest limits, because the last thing this app needs is another confident claim that turns out false:

1. **No Android app can guarantee its process is never killed.** A `connectedDevice` foreground
   service plus a partial wake lock is the strongest protection available to a sideloaded app. Under
   memory pressure, or against OEM battery management, the process can still die. The difference now
   is that a kill costs at most one OBD row instead of a day of driving.
2. **The battery exemption is the owner's to grant.** On the Moto Edge 20:
   Settings → Apps → Kylaq TSI Coach → Battery → **Unrestricted**; and
   Settings → Battery → Battery optimisation → Kylaq TSI Coach → **Don't optimise**.
   The app now asks again if it is still missing (once a day, never a nag), the notification says so
   while a session is live, and the Settings card shows the verdict — but it cannot flip the switch
   itself.
3. **Do not swipe the app away mid-drive if you can avoid it.** It now restarts the service, but a
   restart is a gap in polling, not a continuous record.
4. **A journal write failure means the storage is full or unwritable.** That is reported at STOP and
   on the Settings card rather than hidden; it cannot be engineered away from inside the app.

One thing that is *not* lost and should be said plainly: today's drive was not recoverable because
nothing had been written to disk. No code change can retrieve it. What changes is that it cannot
happen again silently.

---

## 4b. Defect 7: the supervisors lived in the UI, so "background" meant "not recording"

Found from the owner's exact words: *"it supposed to be service right even in background all ways it
should run and record."* He was describing what a service does, and the code contradicted him.
`MainViewModel.startSessionAutomation()` owned both loops - the 10 s auto-connect and the 2 s
auto-record watchdog - and its own KDoc admitted the consequence: *"Both loops live in
viewModelScope, so they stop with the app UI."*

So `ObdKeepAliveService` was keeping a process alive that was **doing nothing**. After a kill, or
with the phone in a pocket and the UI never opened, nothing reconnected, nothing polled, nothing
recorded. The journal fixes defects 1-6 - the drive up to the kill is now on disk - but the drive
*after* the kill still went unrecorded. That is the second half of "never lose the logs".

**Fix.** Both loops now also run inside the service, for as long as the process lives.

  * `AutoRecordPolicy` (new, `com.example.service`) holds the whole rule as pure functions:
    `ENGINE_RUNNING_RPM = 200.0`, `START_STOP_GRACE_MS = 300_000`, `ENGINE_OFF_GRACE_MS = 60_000`,
    `decide(...)` and `nextEngineOffSince(...)`. One definition, two callers, so the supervisors can
    never drift apart. The Idle Start-Stop rule the owner reported on 2026-09-15 lives here now with
    its reason written down: a FRESH `rpm <= 200` is a traffic light and gets five minutes; a
    MISSING reading means the link went stale and gets one.
  * `ObdKeepAliveService.startSupervisors()` runs both loops on its own scope from `onCreate`, so a
    START_STICKY restart after a kill reconnects, polls and records again with no UI and no user
    action. Each action is guarded by the state it would change, and every tick re-reads the owner's
    settings, so switching auto-record off takes effect within seconds.
  * `MainViewModel.startSessionAutomation()` calls the same policy instead of inlining the timing,
    and keeps only the UI-side work a service cannot do.

**Two supervisors, one trip.** With both loops running, both can see "engine on, nothing recording"
on the same tick. Two guards stop that becoming two trips: `RecordingManager.startRecording()` now
returns the live session instead of opening a second one (its return type became
`RecordingMetadata?`), and `MainViewModel.startRecording()` returns early when a session is already
open, so it cannot wipe the ride X-ray or restart the duration timer mid-drive.

**Closing a drive from the background still has to do the closing work.** Everything that must
happen after a STOP lives in `MainViewModel.stopRecording()`: the ride X-ray (`persistDriveInsights`),
the OAuth-free Drive mirror, the cloud backup and the unsaved-raw-log banner. Once the service can
stop a drive, a background stop would have saved the trip and silently skipped all four. So
`MainViewModel` now watches `isRecording` for the falling edge and runs that aftermath, guarded by
`stopInitiatedHere` so its own STOP cannot trigger it twice - `persistDriveInsights()` has no dedup
key, so running it twice appends the same ride X-ray twice, which is the 2026-09-15 duplicate-ride
bug in a new hat. The flag is raised *before* the launch, because `stopRecording()` is asynchronous
and the watcher would otherwise see the fall while the flag was still false. The service supervisor
also calls `rideRecorder.reset()` when IT opens a drive, so a background drive does not inherit the
previous one's X-ray.

## 4c. Defect 8: recovery rejected frames the live parser accepted - and it was speed that went

The symptom behind this whole task, and the one it kept circling without naming: **a recovered log
had rpm in it and no km/h.**

This car's adapter writes a single-byte answer as `04 41 0D 50` - four bytes carrying a count byte
of 04 - where strict ISO 15765-2 single-frame would declare `03`. The live path never minded:
`CanFrameParser` reads the declared length, and when it does not fit falls back to
`dataBytes.drop(1)`, decoding the PID from the bytes actually present. `RawLogRecovery` minded a
great deal. It had two guards that both trusted the count byte to state the number of FOLLOWING
bytes:

    if (bytes.size < pci + 1) return null     // "truncated line"  -> 4 < 5, rejected
    if (bytes.size < 3 + dataLen) return null //                   -> 4 < 5, rejected

So `04 41 0D 50` was thrown away as truncated while the dashboard was displaying 80 km/h from the
identical bytes. Every single-byte answer went the same way: 010D speed, 0105 coolant, 010F intake
air temp, 0111 throttle, 0106/0107 fuel trims, 0146 ambient. Two-byte answers such as 010C rpm
satisfy both guards, which is exactly why the pattern was "rpm yes, km/h no".

**Fix.** Both guards now require only what is needed to identify a row - `bytes.size >= 3`, i.e.
`41 + pid` - and the payload length is `min(declaredDataLen, bytes.size - 3)`. Recovery and the live
parser now produce the same PID and the same payload for every frame shape checked
(`recoveryDecodesEverySingleBytePidTheLiveParserAccepts`, nine real frames including the odometer).
Nothing is padded out to the length the count byte claims, and nothing unreadable is invented:
`framesWithoutAPidToReadAreStillRejectedRatherThanGuessedAt` pins that a pid-less body, an
odd-length body, the ISO 9141 shape with no PCI byte, an ISO-TP first frame, a 7F negative response
and a flow-control frame all still come back as nothing.

**Retraction.** While diagnosing this I first "fixed" only the second guard and wrote in a comment
that the first one was correct - `4 >= 3 is right`. That was my own arithmetic, and it was wrong: the
first guard is the one that fired. I also twice built a Python model of the parser that disagreed
with the Kotlin, once because I passed a flag where the frame belonged. The version above was
re-derived line by line against `CanFrameParser` and checked against real frames before it went in.

**Honest limits of defects 7 and 8.**

  * **A kill still splits a drive in two.** The killed process's journal is recovered as
    `<id> (recovered)`; the new session gets a new id. Both halves are saved - nothing is lost - but
    one drive can appear as two trips. Resuming the unfinished journal as the live session would
    merge them, and is the obvious next step; it is not done here because resurrecting the wrong
    session (yesterday's drive) is a worse failure than a split trip.
  * **GPS may not update in the background.** The manifest declares only `ACCESS_FINE_LOCATION` and
    `ACCESS_COARSE_LOCATION`, with no `ACCESS_BACKGROUND_LOCATION`, so a service-started recording
    can be denied location updates on Android 10+. This does not cost the trip: distance is
    integrated from OBD vehicle speed (`EconomyEngine.accumulatedDistanceKm`, from 010D), not from
    GPS - and 010D is precisely the PID defect 8 was dropping. Distance, economy, CO2/km, the
    plateau table and every curve survive; what can be sparse is the map trace and altitude. Adding
    background location means a second "Allow all the time" prompt and Play Store scrutiny for a
    sensitive permission, so that is the owner's decision, not something to slip into a logging fix.
  * **The service can only run inside a live process.** Android itself can stop a foreground service
    - aggressive OEM battery management, "force stop", a swipe-away on some skins. Nothing in an app
    overrides that; the battery exemption in §4 is what buys the process its life.

## 5. Tests added / changed

| File | What it pins |
|------|--------------|
| `KilledSessionRecoveryTest` (Robolectric, real files + real Room, 7 tests) | The owner's exact failure end to end: record live, **drop the manager on the floor**, construct a fresh one over the same directory (what a restarted process sees), recover with no tap → trip `COMPLETED`, 20 telemetry rows, all four files present and non-empty, samples CSV populated, max RPM/speed/coolant and both ECUs (`7E8`, `7E9`) reduced correctly, duration from the real frames. Plus: never recovered twice; **a second START cannot replace or shred the drive already running** (it hands back the live session, and a STOP followed by a START opens a genuinely new one); a session interrupted in RAM is persisted by the same finalizer rather than cleared; a clean STOP leaves nothing to find; an empty session produces no invented trip; every recovered row round-trips to the instant it was recorded |
| `CrashJournalDurabilityTest` (17 tests) | Header + IST meta written at open; rows on disk before STOP is ever called; killed session detected as unfinished, finished one not; header-only journal not offered for recovery; **a row cut off mid-write costs that row and nothing else**; journal byte-identical to the export; commas in a decoded parameter survive; sample rows round-trip with altitude and IST stamps; replayed samples equal the rows the live recorder wrote; longer source wins at STOP; equal lengths keep RAM; journal beats raw log; write failure counted not swallowed; discard removes every artefact |
| `RecordTimeIstTest` (13 tests) | IST wall time with `+05:30`, never `Z`; round-trip; legacy `…Z` still parses to its true instant; naive read as IST; legacy-UTC and IST stamps for the same instant agree; `logStamp` carries the date; `display` is the IST date; zone/offset labels; zoned-vs-naive detection; unparsable → `null`, never `0`; `SessionTime` delegation keeps an imported trip's duration, including a window written half legacy and half IST; and `noShapeLosesItsFractionOrItsSecondsToAGreedyShorterPattern`, which pins the prefix-parsing trap described in §3 |
| `RawLogDatedRecoveryTest` (13 tests) | Dated lines read at the instant they state, not the file mtime (mtime three days later); a dated drive crossing midnight keeps its order with no heuristic; undated lines still recover; a mixed log reads each line by its own shape; TX/negatives/multi-frame/junk/header still skipped in both shapes; a connection log is never read as a trip; connection logs prunable, session logs never; **and the two parser-agreement tests from §4c** — `recoveryDecodesEverySingleBytePidTheLiveParserAccepts` decodes nine real frames (speed, coolant, IAT, throttle, STFT, ambient, both spellings of a single-byte answer, rpm and the odometer) and checks pid, CAN id, instant, payload and response hex against what `CanFrameParser` produces; `framesWithoutAPidToReadAreStillRejectedRatherThanGuessedAt` pins that a pid-less body, an odd-length body, the ISO 9141 shape, an ISO-TP first frame, a 7F negative and a flow-control frame all still yield nothing |
| `IstOnlyRecordTimeTest` (3 tests) | Source scan: no production file formats a record stamp in UTC; allow-list entries still exist; `RecordTime` still prints its offset |
| `AutoRecordPolicyTest` (new, 13 tests) | The auto-record rule as a pure function, so the two supervisors cannot drift: engine on with nothing recording starts a drive; engine on while recording changes nothing; never starts against a dead link (an empty trip looks like a drive that got 0 km); setting off means the supervisor touches nothing; **a fresh `rpm = 0` is a traffic light, not the end of the drive**, and gets the five-minute Idle Start-Stop grace; a stall longer than that still saves the trip; a MISSING reading is an ignition-off and gets one minute; the engine-off clock is armed once and not rearmed every tick; a restarted engine clears it; the 200.0 threshold is strict, so a cranking motor cannot open a trip; and `aWholeCityDriveWithSixJunctionsStaysOneTrip` runs a whole drive through the rule tick by tick and asserts one start and one stop |
| `BatteryOptimizationPolicyTest` (rewritten, 5 tests) | The new contract, and *why* the old one was wrong: prompts when restricted; prompts with no session live so the next drive is protected; never nags once granted; stays quiet inside the 24 h interval; asks again after it while still restricted |

Committed total: 83 suites, 719 tests (baseline before this task was 82 suites / 699 tests).

Two existing assertions were deliberately changed rather than worked around, both in
`BatteryOptimizationPolicyTest`: `neverNagsAfterTheFirstPrompt` and `silentWhenNoSessionIsLive`
pinned the behaviour that caused today's loss. The file documents that reversal at the top.

---

## 6. Retractions

1. **"START_STICKY plus the existing auto-connect loop recover everything even in the rare kill
   case"** — the `ObdKeepAliveService` class doc. False: with nothing on disk until STOP, there was
   nothing for any loop to recover. Doc rewritten to state what the service does and does not buy.
2. **"Purely additive: polling/recording logic stays exactly where it was"** — same doc. The service
   now starts recovery and survives task removal; that is not purely additive and is no longer
   claimed to be.
3. **My own 2026-09-16 note** that the one-shot battery prompt was the right call ("ask once, never
   nag again"). One prompt for the setting that decides whether the app keeps its data is a prompt
   that gets missed, and missing it costs the logs.

---

## 7. Owner run sheet (unchanged items carried forward)

1. Install this build **over** the existing one — the sideload key is stable, so trips, logs and
   permissions survive. Do **not** Reset-to-defaults first.
2. Settings → DATA & BACKUP → **Never lose a drive** → *Make battery Unrestricted*, then confirm the
   row turns green. Also do the Motorola-specific pass in §4.2.
3. Re-run PID discovery on this build (still not the full list: 42 claimed / 26 proved / 16 lost to
   the 0x00 bitmap lag / 0x20 never decoded — see `qa-qc-pid-discovery-export-2026-09-17.md`).
4. The rest of that run sheet stands: `01A4` while moving >20 km/h in D; Mode 09 `0902/0904/090A`;
   `22 202A` at 7E0; `018E`/`019E` under load; steady-80 km/h plus a WOT sweep to define the `016D`
   and `0170` scaling. Every timestamp in that next export will read IST.
5. After any drive where the app was killed, expect a notification: *"Killed session recovered — logs
   saved"*. If you ever see *"Could not recover…"*, send the text — it names the session and the
   reason, and that is a bug I want to hear about.
