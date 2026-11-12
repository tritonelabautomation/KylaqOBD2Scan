# QA/QC — one drive, one trip: resume across process deaths (2026-09-21)

## Owner report

Trip list at 09:39 beside a cluster photo of a **31 km / 1:44 h** drive:
*"You see what it did to my 31km trip nothing logged."*

The list showed, for that one drive: `Kylaq Run 07:36` (**3 transactions**),
`Kylaq Run 07:36 (recovered)` (**13 717 transactions**) and
`Recovered Run 08:00` (**45 957 transactions**). The drive was NOT lost -
13 717 + 45 957 rows at the live poll rate is exactly a ~104-minute drive - but
it was **shredded**: one trip per process death, plus a stub.

## Two defects, code-proven

1. **Shred on death.** `recoverUnfinishedSessions()` ran in the service's
   `onCreate` on every restart and finalized the live journal as its own
   "Recovered Run"; the auto-record supervisor then opened a brand-new session
   for the still-running drive. Every mid-drive death therefore cut the drive
   in two. (The deaths themselves are a separate case: the crash journal holds
   their stacks; the OOM fix of 1.0.502 removed the known cause.)
2. **Stub trip.** `AutoRecordPolicy.decide` could STOP a session seconds old:
   a cranking blip opens it, half-a-second of ECU silence at start closes it -
   the 3-transaction row. The grace windows covered real ends of drive; nothing
   covered false ones at the start.

## Fix shipped

- `SessionRecoveryPolicy.RESUME_WINDOW_MS` (15 min) + `isResumable(age)`: a
  cut-off journal inside the window belongs to a drive that may still be
  running.
- `SessionJournal.resume(metadata)`: reopen the journal in APPEND mode - same
  files, same headers - and `RecordingManager.resumeRecording(id)` reloads the
  previous process's rows wall-anchored (IST stamps) into the RAM lists so the
  finalized trip carries every leg; `startOrResumeRecording()` is now the single
  entry point of BOTH supervisors (UI loop and service loop).
- `recoverUnfinishedSessions(skipFreshMs)` skips resumable journals AND raw logs
  still inside the window (both loops); a **deferred sweep** (service every
  60 s; ViewModel once at window+60 s) finalizes anything that aged out without
  being resumed - a drive that ended at the kill still becomes a trip with
  nobody opening anything.
- `AutoRecordPolicy.MIN_SESSION_MS` (90 s): a session younger than that is never
  auto-stopped, whatever the grace windows say.

## Regression tests

- `KilledSessionRecoveryTest.oneDriveStaysOneTripAcrossAProcessDeath`: leg 1,
  simulated death, recovery with the window must skip, resume must return the
  SAME session id, leg 2, stop - one COMPLETED trip whose transaction and
  sample counts equal leg1+leg2, and exactly one saved recording for the id.
- `AutoRecordPolicyTest.aSessionYoungerThanTheMinimumIsNeverAutoStopped`:
  engine-off past grace at 30 s of age -> NONE; past MIN_SESSION_MS -> STOP;
  callers without an age keep the historical verdict.

## Honest boundaries

- The resume window trades a <=15 min display delay for integrity: a drive that
  ended exactly at a kill appears after the window (sweeps), not instantly.
- Deaths mid-drive still cost the row in flight and leave a crash record; the
  journal + raw log + recovery chain still guarantees no trip is lost - this fix
  guarantees it is also **one** trip.
- If the phone reboots (uptime clock reset) mid-drive, resumed rows keep wall
  time via IST stamps; only the in-RAM monotonic column mixes bases, which no
  stored field depends on (window and stamps are wall by construction).
