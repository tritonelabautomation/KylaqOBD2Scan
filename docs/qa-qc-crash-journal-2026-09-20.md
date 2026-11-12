# QA/QC — crash journal, safe mode, and the "missed" trip (2026-09-20)

## Owner report (verbatim)

> "Again app is crashing when I open it crashes.
> No app crash logs why it has crashed does it have any issues with wireless Android Auto.
> Even now a trip record is missed discusting"

Three complaints, answered one by one, with the evidence.

## Finding 1 — "No app crash logs": true, and that was the app's fault

A grep of the whole app found **zero** uncaught-exception handlers. Android killed the
process and showed its generic "App keeps stopping" dialog; the reason died with it.
Nothing was ever written to disk. Fixed in this change:

- `KylaqApplication` (registered via `android:name` in the manifest) installs
  `CrashJournal` **before any activity, service or receiver runs**.
- Every uncaught exception — main thread, coroutine, keep-alive service, Android Auto
  host callbacks — is first written to `files/crash_logs/crash_<epochMs>.txt`:
  IST stamp (owner mandate 2026-09-17, never UTC), app version, device, thread, full
  stack trace. Then Android's own handler is chained, so system behaviour is unchanged.
- Files are **never deleted by the app** (never-lose-logs mandate) and now **ride along
  in every Drive backup** (`DriveBackupClient.sendBackup`; `ZipImporter` skips the
  `crash_*` names on restore — `Kind.UNKNOWN` — so they can never pollute a trip).
- On the next open, a "Previous run crashed" card shows the IST time and the two
  important lines (deepest `Caused by:` + the first `com.example` frame below it — the
  app line that threw) with a **Share log** button.

## Finding 2 — crash-loop breaker (safe mode)

If two consecutive starts die before the first frame is drawn
(`CrashJournalPolicy.SAFE_MODE_AFTER`), the next open shows a **Safe mode** screen that
touches none of the usual machinery — no ViewModel, no database, no Bluetooth. It states
the recorded reason, shows the full log, can share it, and offers **Try normal start**
(which forgives the streak and retries). A healthy start is only recorded after the UI
survives its first 4 seconds, because the ViewModel's init coroutines (auto-connect,
refuel backfill, update check) run right after composition.

## Finding 3 — wireless Android Auto: what is and is not true

- The app **does** expose Android Auto surfaces: `ObdCarAppService` (Car App Library)
  and `AutoDashActivity` (parked-surface car launcher entry). When the phone connects
  to wireless AA, the head unit binds that service **in the same process as the phone
  app** — so a crash triggered by the car connection kills the whole app, and would
  look exactly like "crashes when I open it" if it repeats on bind.
- Those paths were audited and are already guarded: session/screen creation is wrapped
  in `try/catch` with an `ObdFallbackScreen` that says why the car UI could not start
  instead of throwing.
- Wireless AA projection itself (maps, media) is Google's component and does not run
  this app's code.
- Honest limit: AA-induced crashes cannot be ruled in or out remotely. From this build
  on they do not need to be guessed: the journal records the thread and full stack of
  **any** crash, including one thrown while the head unit is bound, and the open-time
  card names it.

## Finding 4 — the "missed" trip record is almost certainly not lost

- Every OBD transaction is appended to the session journal **and flushed before
  `recordTransaction` returns** (owner mandate 2026-09-17), and raw-log frames are
  flushed per line from the moment the link is up — a process death costs at most the
  row in flight.
- `MainViewModel.init` calls `runAutoRecovery()` on every clean app open, and
  `ObdKeepAliveService.onCreate/onStartCommand` + `KeepAliveBootReceiver` run the same
  guarded recovery. A cut-off drive reappears as **"Recovered Run …"**.
- The crash loop was the blockage: a start that dies before or during recovery cannot
  finish it. Once the app opens cleanly (safe mode guarantees an openable screen, and
  "Try normal start" retries the normal path), the pending journal/raw log is rebuilt
  automatically — nothing has to be done by hand.
- If, after one clean open, no "Recovered Run" appears, the recovery banner states
  exactly what was pending, recovered, lost, and why (`RecoverySummary.notice()`), so
  the answer is on-screen rather than silent.

## Startup-path audit performed (what was checked, what was found)

| Surface | Verdict |
| --- | --- |
| Room DB (v13) | Full 1→13 migration chain + destructive-on-downgrade fallback present |
| `RecordingManager.recoverUnfinishedSessions` | Whole body wrapped in `try/catch`, per-session `runCatching` |
| `loadSavedRecordings` JSON parsing | Per-file `try/catch`; a corrupt session JSON cannot crash the open |
| Prefs snapshot restore (`AppDataSnapshot`) | Type-tagged buckets (string/boolean/long/int/float) — a restore cannot flip a pref's type and cause a `ClassCastException` on read |
| In-app updater (`checkForUpdate`) | Feed fetch wrapped in `runCatching`; parse guarded |
| Android Auto (`ObdCarAppService`, `AutoDashActivity`) | Guarded session creation + fallback screen |
| Uncaught-exception recording | **Did not exist** — added (Finding 1) |
| Crash-loop protection | **Did not exist** — added (Finding 2) |

## Tests

New JVM suite `CrashJournalPolicyTest` (7 tests): safe-mode threshold, crash file name,
record header contents (IST/version/device/thread), deepest-cause + culprit-frame
summarisation (wrapper frames must not be blamed), no-cause-chain fallback,
foreign-frames fallback, empty/null handling. Frame lines use real tab characters, the
exact shape `Throwable.printStackTrace` writes.

## Honest limits

- The root cause of the owner's crash cannot be named from this sandbox — there was no
  log to read, which is precisely the defect fixed here. The next crash, whenever it
  happens, writes its own reason to disk, shows it on the open-time card, and ships it
  to Drive with the next backup.
- Safe mode protects against open-time crash loops only. A crash minutes into a session
  is journaled but not "safe-moded" — by design, so normal driving behaviour is never
  altered by this machinery.
