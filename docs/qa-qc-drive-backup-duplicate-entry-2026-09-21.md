# QA/QC — Drive backup dead since journal shipped: duplicate entry (2026-09-21)

## Owner report

Screenshots at 10:08 IST, App & Cloud Settings, Google Drive Backup & Sign-In
signed in as `tritonelabautomation@gmail.com`, Automatic Cloud Backup ON,
"Last synchronized: Never" and a red toast that says it all:

> **Backup failed: duplicate entry: 096b9a42_transactions.csv**

The owner wrote only "Unable to backup". The toast and the Never stamp are the
evidence: every backup since the crash journal shipped has been dying on this.

## Code-proven root cause

`DriveBackupClient.sendBackup` built its file list with:

```kotlin
val files = recordingManager.recordingsDir.walkTopDown().filter { it.isFile }
```

`recordingsDir` contains two things with the **same flat name**:

- `journal/<id>_transactions.csv` — the working copy, kept on purpose after a
  clean STOP by DATA-LOSS FIX 4. If the process dies inside `finalizeSession`
  (Room + ZIP + analyses, seconds), the finished marker would otherwise hide the
  journal from recovery and the drive would be lost. Keeping the journal beside
  the finalized session files guarantees recovery can prefer the longer copy.

- `session_<id>/<id>_transactions.csv` — the finalized copy written by
  `finalizeSession`.

`ZipExporter.createTripZip` then did:

```kotlin
val entry = ZipEntry(file.name)   // flat!
zos.putNextEntry(entry)
```

`ZipOutputStream` throws `ZipException: duplicate entry: <name>` on the second
file with the same name. The exception bubbled to the ViewModel and surfaced as
the red toast. The "Last synchronized: Never" line proves no zip ever made it
to Drive after the journal landed (2026-09-17). The restore that the owner did
on 2026-09-20 used a zip from before that date.

This is L6 of the guarantee ledger (off-device copy) failing open: local data
was never at risk, but the Drive copy that makes the "phone lost" row safe was.

## Fix — belt and suspenders

Two independent guards, either of which fixes the report; together they make
the class of bug impossible:

1. **DriveBackupClient excludes the journal dir** — the journal is working
   state, not backup state. Finalized trips live in `session_<id>/`. A drive
   still in progress is covered by `raw_logs/raw_log_*.txt`, which IS packed.
   The journal's purpose (survive a kill inside finalize) is orthogonal to Drive.

   ```kotlin
   val journalDir = File(recordingsDir, "journal")
   val journalPrefix = journalDir.path + File.separator
   val files = recordingsDir.walkTopDown()
       .filter { it.isFile && !it.path.startsWith(journalPrefix) }
   ```

2. **ZipExporter dedupes by entry name** — first copy wins, second same-named
   file is skipped instead of throwing. If any future caller hands a list with a
   collision (raw log + session, crash log + snapshot, etc.) the backup still
   lands.

   ```kotlin
   val seen = mutableSetOf<String>()
   if (!seen.add(file.name)) continue
   ```

## Regression test

`ZipExporterDedupTest.duplicateNamesSkipTheSecondCopyInsteadOfKillingTheBackup`

- Creates `session_a/deadbeef_transactions.csv` and
  `journal/deadbeef_transactions.csv` with different contents plus a unique raw
  log.
- Calls `createTripZip` with both same-named files.
- Asserts: zip contains 2 entries (not 3), no exception, first copy's bytes win.
- This pins the belt: even if the walk exclusion is ever removed, the zip never
  dies on a duplicate again.

## Verification

- CI push `b704ee0`: `100` suite(s), `809` test(s): **0 failed**, 1 skipped.
  (Was 99/808 after the one-drive-one-trip fix.)
- Manual: after installing the build that contains this commit, Settings →
  Google Drive Backup & Sign-In → "Back Up Now" must succeed, "Last
  synchronized" must become a timestamp, and the Drive folder must contain a
  new `kylaq-obd-backup-*.zip` whose entries are unique.

## Honest remainder

- Any backups taken between 2026-09-17 and this fix never reached Drive; the
  local trips are intact but their off-device copy is missing for that window.
  One successful "Back Up Now" after this fix closes the gap — it zips every
  local trip, including the ones from that window.
- The journal working directory is intentionally NOT in Drive backups after this
  fix. That is correct: a drive that was in progress at backup time is
  represented by its raw log; a finished drive by its session files. The journal
  is only needed on the device that is currently recording.
