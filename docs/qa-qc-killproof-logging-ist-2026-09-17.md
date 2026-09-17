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
`SessionTime.parseMillis` now delegates to it, so an imported trip keeps the window it was recorded
in — the bug that helper was originally created to kill. A history that spans the upgrade has no
five-hour tear in it; a test mixes a legacy `…Z` start with an IST end and asserts the duration.

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

## 5. Tests added / changed

| File | What it pins |
|------|--------------|
| `KilledSessionRecoveryTest` (Robolectric, real files + real Room, 6 tests) | The owner's exact failure end to end: record live, **drop the manager on the floor**, construct a fresh one over the same directory (what a restarted process sees), recover with no tap → trip `COMPLETED`, 20 telemetry rows, all four files present and non-empty, samples CSV populated, max RPM/speed/coolant and both ECUs (`7E8`, `7E9`) reduced correctly, duration from the real frames. Plus: never recovered twice; a restart no longer throws the previous session away; a clean STOP leaves nothing to find; an empty session produces no invented trip; every recovered row round-trips to the instant it was recorded |
| `CrashJournalDurabilityTest` (17 tests) | Header + IST meta written at open; rows on disk before STOP is ever called; killed session detected as unfinished, finished one not; header-only journal not offered for recovery; **a row cut off mid-write costs that row and nothing else**; journal byte-identical to the export; commas in a decoded parameter survive; sample rows round-trip with altitude and IST stamps; replayed samples equal the rows the live recorder wrote; longer source wins at STOP; equal lengths keep RAM; journal beats raw log; write failure counted not swallowed; discard removes every artefact |
| `RecordTimeIstTest` (11 tests) | IST wall time with `+05:30`, never `Z`; round-trip; legacy `…Z` still parses to its true instant; naive read as IST; legacy-UTC and IST stamps for the same instant agree; `logStamp` carries the date; `display` is the IST date; zone/offset labels; zoned-vs-naive detection; unparsable → `null`, never `0`; `SessionTime` delegation keeps an imported trip's duration, including a window written half legacy and half IST |
| `RawLogDatedRecoveryTest` (8 tests) | Dated lines read at the instant they state, not the file mtime (mtime three days later); a dated drive crossing midnight keeps its order with no heuristic; undated lines still recover; a mixed log reads each line by its own shape; TX/negatives/multi-frame/junk/header still skipped in both shapes; a connection log is never read as a trip; connection logs prunable, session logs never |
| `IstOnlyRecordTimeTest` (3 tests) | Source scan: no production file formats a record stamp in UTC; allow-list entries still exist; `RecordTime` still prints its offset |
| `BatteryOptimizationPolicyTest` (rewritten, 5 tests) | The new contract, and *why* the old one was wrong: prompts when restricted; prompts with no session live so the next drive is protected; never nags once granted; stays quiet inside the 24 h interval; asks again after it while still restricted |

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
