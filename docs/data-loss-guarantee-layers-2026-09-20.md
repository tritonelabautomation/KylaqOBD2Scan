# The nothing-lost ledger: what protects a trip, against what (2026-09-20)

Owner question, verbatim: *"How are you assuring me 100% every trips logs will save no
matter what ever happens nothing is lost?"*

## The honest headline

A literal 100% against *whatever happens* does not exist for any app on any phone: if the
storage chip dies, the phone is lost or stolen, or the data is factory-reset away **and no
off-device copy exists**, the bits are gone — physics, not software. What this app does
promise, and now delivers in tested layers, is: **every failure mode the app can influence
is covered, no loss is ever silent, and the one layer that beats physics (Drive) is wired
to run by itself after every trip.** Anyone selling you more than that is selling fiction.

## The six layers, and what each survives

| # | Layer | Survives | Evidence in code / tests |
|---|-------|----------|--------------------------|
| 1 | Crash journal: every OBD row appended to `recordings/journal/` and **flushed before the call returns** | process kill, swipe-away, OS kill, OEM battery kill, crash on any thread, crash loop — costs at most the row in flight | `SessionJournal.appendTransaction` (synchronized write+flush, failures counted and surfaced, never swallowed); `CrashJournalDurabilityTest`, `KilledSessionRecoveryTest` |
| 2 | Raw frame log: every adapter line flushed per line from the moment the link is up | even a session whose journal could not open (storage error is announced, not hidden) | `RawLogManager`; `RawLogRecoveryTest`, `RawLogDatedRecoveryTest` |
| 3 | Finalize at STOP: wide samples + transactions CSVs, session JSON, bundle zip, Room rows | normal ends from UI **or** from the service's auto-stop rule — one shared `finalizeSession` | `RecordingManager.finalizeSession`; `BackupAndImportTest` |
| 4 | Automatic recovery on **any** start: app open (ViewModel init), service `onCreate` (START_STICKY restart after a kill), boot receiver (reboot AND update-install) | kills mid-drive, reboots mid-drive, updates installed over a running app; idempotent — never rebuilt twice, unrecoverable corpses archived and **reported**, not dropped | `recoverUnfinishedSessions`, `KeepAliveBootReceiver`; `KilledSessionRecoveryTest` (incl. never-twice); field proof: the 74-min OEM-killed drive and the 2026-09-20 13:25 crash-looped drive both came back as "Recovered Run" |
| 5 | The app stays openable: crash journal + safe mode + safe-mode update path | crash loops that would otherwise lock the owner out of recovery forever | `CrashJournal`, `CrashSafeModeScreen`; field proof: the 20:23 OOM loop opened safe mode, named its cause, and offered the fix |
| 6 | Off-device copy: Drive backup zip = all recordings + unsaved raw logs + prefs/ledger snapshot + crash logs; manual, **automatic after every trip stop**, optional daily | the only layer that beats physics: phone loss/theft, dead storage, factory reset | `DriveBackupClient.sendBackup`, `CloudBackupManager.performAutoBackupIfNeeded` (called from the stop watcher); `BackupAndImportTest` |

## What can still win (the honest remainder)

1. Phone physically gone or storage electrically dead **and** no Drive backup ever ran.
2. "Clear app data" / uninstall without a prior backup (updates are in-place installs and
   lose nothing; clearing data is a manual act of deletion).
3. The Drive folder itself deleted **and** the phone dead.

Mitigation for all three is one habit: stay signed in to Google with auto-backup on —
then every stopped trip pushes a copy off-device best-effort, and even crash records ride
along in the zip.

## The promise that IS absolute

**No silent loss.** Every layer either saves the data or says, out loud, what was pending,
what was rebuilt, what could not be and why (recovery banner, journal-failure warning at
record start, recovery summary notice). Nothing is ever deleted to hide a failure, and
every loss path found in the field gets the OOM treatment: root-caused, fixed,
regression-pinned, shipped. Current pinning: 100 suites / 809 tests / 0 failed.
