# QA/QC: heap exhaustion and the GcWatcher timeout — 2026-11-16

Owner report: *"is this related poor RAM management by app on mobile? Why does app leaking
memory or RAM"* — with two crash records from the same phone on the same day.

## What the phone actually said

Both records are already in this repo as auto-filed issues (#26, #27, and the earlier #17–#24).

```
Auto Crash: java.lang.OutOfMemoryError: Failed to allocate a 56 byte allocation with 73728 free
bytes and 72KB until OOM, target footprint 536870912, growth limit 536870912;
giving up on allocation because <1% of heap free after GC.
    at android.os.Parcel.obtain(Parcel.java:5591)
    at android.os.Binder.execTransact(Binder.java:1362)
```

```
Auto Crash: java.util.concurrent.TimeoutException:
com.android.internal.os.BinderInternal$GcWatcher.finalize() timed out after 10 seconds
    at android.os.SystemProperties.native_get(Native Method)
    ...
    at com.android.internal.os.BinderInternal$GcWatcher.finalize(BinderInternal.java:61)
    at java.lang.Daemons$FinalizerDaemon.doFinalize(Daemons.java:389)
```

Device: OnePlus CPH2793, Android 16 (SDK 36), app 1.0.860.

### Reading the evidence

* **The failing allocation is 56 bytes.** A 56-byte request cannot fail because of one large
  object. It fails because the heap has no room left at all: `growth limit 536870912` is the full
  512 MB that `android:largeHeap="true"` had granted, and `<1% of heap free after GC` means the
  collector had already run and given up. The heap was **full of long-lived data**, not one big
  buffer.
* **The binder thread and the finalizer are victims, not culprits.** `Parcel.obtain` and
  `GcWatcher.finalize` are just the first places that happened to allocate on a full heap.
* **The second record is the same disease.** `FinalizerWatchdogDaemon` kills the process when a
  single finalize exceeds 10 s. With the heap permanently near its ceiling, ART spends nearly all
  its time collecting, so an innocent finalizer (here OnePlus's own `GcWatcher`, which reads
  system properties) is starved past the deadline. Same root cause, different symptom — which is
  why "fixing" either stack frame would have changed nothing.
* **Earlier reports** (#17, #18, #20, #21, #24) all failed on **8/32/48-byte** allocations from
  `kotlinx.coroutines` bookkeeping, and #19 died on the main thread inside Compose's
  `LaunchedEffect`. Again: the last allocation, never the cause.

**So: was it "poor RAM management"?** Yes — but not the kind a leak detector finds. There was no
single object graph held forever. The app was **allocating far too much, far too often, for the
whole duration of a drive**, while also holding two or three full copies of the data being
recorded, and it had asked Android for a 512 MB heap to make room for that. The bigger heap made
each GC pass longer, which is exactly what produced the 10-second finalizer timeout.

## The four defects that were actually fixed

### 1. The per-frame publish churn (the big one)

`RecordingManager.recordTransaction` rebuilt the entire live ring **on every frame**:

```kotlin
_currentTransactions.value = activeTransactionList.toList()
```

At the app's polling rate that is dozens of fresh 200-element lists per second, for the entire
drive, all garbage within milliseconds. `RawLogManager.onRawLog` did the same with a 1000-element
list, `ObdScheduler.appendRawHistory` did a full `mapValues { toList() }` over every polled PID,
and `PidDiscoveryService.appendLog` had **no cap at all** and grew for the life of the process.

All four are now throttled (`HeapPressure.shouldPublish`) and bounded. The live screens read the
same numbers a human eye can read; the allocation rate drops by two orders of magnitude.

### 2. Whole-drive materialisation on the recovery and import paths

| Path | Before | After |
|---|---|---|
| `ZipImporter` restore | `JSONObject(jsonFile.readText())` — whole document as text, then a full org.json tree, then the record list | `SessionJsonReader.readSession` (streaming `JsonReader`), one record at a time |
| `ZipImporter` CSV fallback | `csvFile.readLines()` | `bufferedReader()` + `readLine()` |
| `ZipImporter` sample count | `sampleCsv.readLines().size - 1` | `SessionJsonReader.countTransactionRows` |
| `recoverFromRawLog` | `List<Telemetry>` → `List<TransactionRecord>` → `List<SynchronizedSample>`, all alive at once | `RawLogRecovery.forEachTelemetry` writes each row straight to the CSVs that ARE the trip, then `finalizeSessionFromFiles` reads them back in chunks |
| `SessionJournal.readMeta` | `readLines()` | `bufferedReader()` |

`recoverFromRawLog` ran **at app start, right after a kill** — the worst possible moment, on the
heap that had just been through the death that made recovery necessary. Peak RAM there is now one
frame instead of one drive.

### 3. There was no runtime measurement at all (`HeapGuard` had only static guesses)

`HeapGuard`'s caps were chosen by eye and could not know whether the process was *already* near
its ceiling. New `MemoryGuard` + `HeapPressure`:

* samples `Runtime.totalMemory() - freeMemory()` against `maxMemory()` every 5 s;
* listens to `onTrimMemory` / `onLowMemory`;
* three bands — **NORMAL < 70 %**, **ELEVATED ≥ 70 %**, **CRITICAL ≥ 85 %**;
* sheds the RAM rings that own memory (live HUD ring 200 → 60 → 20, raw-log ring 1000 → 200 → 50,
  per-PID research history 50 → 20 → 6, discovery console 500 → 250 → 100) and slows the publish
  cadence;
* **never touches disk.** Every ring it shrinks is rebuilt from a file that is already written and
  flushed, so a shed costs a scrollback, never a frame of a drive.

### 4. `largeHeap` was removed

The 512 MB ceiling is literally where the app died (`target footprint 536870912`). The extra
headroom was being consumed rather than sparing the process, and it made every GC cycle longer —
the direct cause of the GcWatcher timeout. Nothing needs it now that every long path streams.
Running at the normal per-app heap means GC runs sooner, in smaller increments, and the process is
a smaller target for the low-memory killer.

## Why it will be diagnosable next time

The two reports could only say *that* a 56-byte allocation failed. `CrashJournal` now writes, into
every crash record:

```
heap: used=498MB max=512MB (97%) level=CRITICAL trim=15 sheds=12
---
memory pressure log (tail) ---
2026-11-16 20:34:40+05:30 sample level=ELEVATED used=372MB max=512MB (72%) thread=DefaultDispatcher-worker-3
2026-11-16 20:34:55+05:30 onTrimMemory(15) level=CRITICAL used=441MB max=512MB (86%) thread=main
```

The pressure log is bounded (64 KiB, one rotated generation) and lives in `files/`, **outside**
`crash_logs/` — crash files themselves are still never deleted (never-lose-logs) and are still
uploaded to GitHub Issues.

## Tests added

| File | What it pins |
|---|---|
| `HeapPressureTest` (16 cases) | band arithmetic, boundary ownership, clamp behaviour, publish throttle, trim mapping |
| `MemoryGuardTest` (8 cases) | a shed reaches the rings; rings shrink and recover; **disk still holds every line after a shed**; the diagnostic line exists |
| `StreamedRecoveryTest` (3 cases) | streamed parser returns exactly the rows the collecting one does; a streamed raw-log recovery still produces a COMPLETED trip with Room samples and no leftover scratch dir; a TX-only log still reports `NothingToRecover` |
| `CrashJournalPolicyTest` (+3 cases) | the heap line is recorded, is not mistaken for a stack frame, and older records parse unchanged |

The `MemoryGuardTest` disk assertion is the load-bearing one: the entire design trades RAM for
disk reads, and it is only defensible because the disk copy is written and flushed **before**
`recordTransaction` returns.
