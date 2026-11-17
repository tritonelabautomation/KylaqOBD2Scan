# Release 1.0.880 follow-up audit — signing, reconciliation, memory, import

**Audited / worked commit:** `03db0c5e3566f95c1f4b780f86f2e20998014de5` (release 1.0.880, versionCode 10880)
**main:** `d110bb425a4363d27218d3b4d3d20bef69558f1d`
**Branch:** `arena/e5f3e3fc-kylaqobd2scan` (PR #23, `pr_open`)
**Date:** 2026-11-12
**New work:** `aa9c137` (Phases 3/4/6), plus this document and the signing corrections.
**No APK was published for this work and `main` was not modified.**

---

## Phase 1 — Repository and release reconciliation

**Confirmed state**

| Item | Value |
|---|---|
| Release branch tip | `03db0c5` (= the audited commit) |
| `main` tip | `d110bb4` |
| Merge base | `d110bb4` — **identical to `main`'s tip** |
| Ahead / behind | 113 ahead, **0 behind** |
| Divergence | 444 files, +80 247 / −2 228 |
| Other branches | `arena/00e86e1e`, `arena/01a07c37`, `arena/01a0c688`, `arena/18e96897` |
| Other open PRs | #14, #1 (plus #23) |

### Why the production branch is ahead of `main`

Not a fork or a divergence: **`main` is a strict ancestor of the release branch.** Nothing on `main` is missing from the release, so the usual "merge conflicts" risk does not exist. The 113 commits are the entire history of owner-driven fixes since `d110bb4` (`fix-android-auto-revert`), all delivered through PR #23, which has never been merged.

The root cause is process, not code: the branch *is* the trunk in practice (every release is cut from it), while `main` is treated as a frozen baseline. PR #23's title — "Fix tank %→litre maths" — describes 1 of those 113 commits, which is why nobody treats it as the merge that lands everything.

### Authoritative production source

**`arena/e5f3e3fc-kylaqobd2scan` at `03db0c5`.** It is what the published 1.0.880 APK was built from, it is the only branch with the fix history, and `main` cannot reproduce a single owner-reported bug from the last two months.

### Safe merge plan (requires your approval — nothing done)

1. **Fast-forward `main` to `03db0c5`.** Because the merge base equals `main`'s tip, this is a true fast-forward: no merge commit, no conflict resolution, no history rewrite, nothing lost. It is the lowest-risk possible reconciliation.
2. Then, going forward: cut releases from `main` (or declare `main` a read-only mirror). The failure mode to avoid is a *second* long-lived branch repeating the same 113-commit gap.
3. **Do not** rebase, reset, or force-push either branch — the plan above needs none of them.
4. PR #23 should be merged (not squash-merged) so the individual fix commits and their crash-report rationale survive in history; squashing would collapse 113 explained fixes into one message.

I have not touched `main`, per the directive.

---

## Phase 2 — Signing security

### Measured facts

I extracted the certificate from the committed keystore using the password that is hardcoded in `app/build.gradle.kts`:

```
file        keystore/sideload.p12          2 660 bytes, committed in 8f90983
alias       sideload
subject     CN = Kylaq Sideload, O = Kylaq, C = IN
validity    Sep 28 2026  ->  Feb 13 2054
key         RSA 2048, sha256WithRSAEncryption
SHA-1       EC:B8:C9:F9:3B:E4:51:E5:A0:1C:31:29:67:B0:6B:1B:3D:11:C5:77
SHA-256     10:F5:29:99:F8:A4:6B:BF:28:2F:DA:C4:3B:B1:15:AB:17:63:AB:63:58:74:31:9E:41:95:00:1B:AD:4B:37:E1
```

**The private key is extractable** (`openssl pkcs12 -nocerts -nodes` succeeds with the documented password). This is a confirmed compromise, not a suspicion.

### Impact, stated precisely

The comment in `build.gradle.kts` claimed integrity comes "from write access to the repo, not from the secrecy of this key". **That reasoning is wrong**, and I replaced the comment with the measured facts.

What an attacker with the key can and cannot do:

- **Cannot** easily ship a malicious *in-app update*: `UpdateManager` verifies the SHA-256 from `latest.json` (`UpdateManager.kt:107`) and compares the installed certificate against the downloaded APK's (`:147`). Both must be beaten, and `latest.json` is served from the repo.
- **Can** forge an APK that installs silently *over* the real app when the owner side-loads it by hand from anywhere else — the exact workflow this project uses. Same package, same signature, no prompt about a different signer.
- **Can** sign anything claiming to be KylaqOBD2Scan. There is no revocation: the certificate is valid until 2054 and Android has no mechanism to revoke a signing identity.

### The hard constraint that shapes the fix

`minSdk = 24`. APK Signature Scheme v3 key rotation, which lets an installed app accept a *new* signing certificate, requires **minSdk 28+**. On API 24–27 a new key means the owner must uninstall before installing, which destroys the Room database, every recording, every raw log and all granted permissions.

**Therefore: any plan that simply replaces the key violates the "existing users must retain the ability to update without losing recordings" requirement on API 24–27 devices.** This is a platform limit, not something code can work around.

### Recommendation: keep the key, protect it properly

The only zero-data-loss rotation is to keep signing with this same key while removing it from the repository:

1. Base64 the keystore, store as `SIDELOAD_KEYSTORE_BASE64`; also set `SIDELOAD_STORE_PASSWORD`, `SIDELOAD_KEY_PASSWORD`, `SIDELOAD_KEY_ALIAS`.
2. The workflow **already** prefers these secrets over the committed file (`build-apk.yml:177-187`) — no code change is needed, only setting the secrets.
3. `git rm keystore/sideload.p12`, add `keystore/` to `.gitignore`, and change the in-file fallbacks so a missing secret **fails the build** instead of silently signing with a public key.
4. Accept that the key is already public history: its value is now *limiting* further exposure and preventing casual forgery, not restoring secrecy. Combined with the existing SHA-256 + certificate checks, that is a meaningful reduction in the practical attack surface.

**I could not verify whether the secrets are currently set** — the Actions secrets API returns 403 for this integration and CI logs are not reachable from here. The workflow's fallback means releases may be signing with the *committed* key today. Only you can confirm this in repository settings.

### Rollback strategy

- Keep the base64 keystore and passwords in a password manager, offline. Without them, no future build can install over an existing one.
- If a secret is rotated wrongly and a build is mis-signed: the previously installed app still validates its own certificate, so the owner simply installs the last good APK from the release that used the correct key. Nothing on the phone is touched by a *failed* install attempt.

### Fingerprint comparison table (for manual verification)

| Build | Expected SHA-256 of signing cert |
|---|---|
| 1.0.880 and every prior sideload build | `10:F5:29:99:F8:A4:6B:BF:28:2F:DA:C4:3B:B1:15:AB:17:63:AB:63:58:74:31:9E:41:95:00:1B:AD:4B:37:E1` |
| Any future build | must match the above, for as long as API 24–27 support is retained |

Verify a downloaded APK with: `apksigner verify --print-certs KylaqOBD2Scan.apk` and compare the SHA-256 digest line.

---

## Phase 3 — Memory and database: every call site

| Query | Call sites found | Verdict |
|---|---|---|
| `getSamplesForTrip` | none outside `TripRepository` | **unused** — screens use `getSamplesForAnalysis`, which strides above `UI_SAMPLE_CAP` (8 000) |
| `getSamplesForPid` | none | unused |
| `getSamplesForTrips` | `TripRepository.samplesForTrips`, `trendSamples` (only `trendSamples` is called, from `MainViewModel:2413`) | bounded by projection to trend PIDs; not changed this pass |
| `getRawLogsForTrip` | `TripDetailScreen:155`, `:267` — **both loaded the entire log** | **fixed** |
| `getSamplesForTripFlow` | none | unused |
| `getRawLogsForTripFlow` | none | unused |
| `getAllTrips().take(n)` | `recentTrips(50)` and `recentTrips(300)` | **fixed** |

### Fixed defects

**MEM-02a — `recentTrips` loaded every trip to show 50.**
`TripRepository.kt:87` was `tripDao.getAllTrips().take(limit)`. With 500 saved trips, 500 rows were read and materialised on the Trips overview (`recentTrips(300)`) and trip detail (`recentTrips(50)`). Replaced with `TripDao.getRecentTrips(limit)` — a real SQL `LIMIT` (`TripDaos.kt:23`). Signature unchanged, so no call site moved.

**MEM-02b — the diagnostic console loaded the whole raw log, twice.**
`TripDetailScreen.kt:155` and `:267` called `getRawLogsForTrip`, one row per OBD frame — tens of thousands for a long drive — to fill a `LazyColumn`. New `getRecentRawLogsForTrip` (`TripRepository`) reads the newest `HeapGuard.RAW_CONSOLE_ROWS` (2 000) via `DESC LIMIT` and returns them ascending (`TripDaos.kt:130`).

**MEM-02c — `rawCount` reported the wrong number.**
Because the list was whatever had been loaded, the overview labelled "raw frames" with a number that had nothing to do with frames recorded. Now `getRawLogCountForTrip` (`TripDaos.kt:133`).

**Nothing was removed from disk.** The database rows and the session files are untouched; only the projection handed to the UI is bounded, and the console now states "Showing the newest N of M recorded frames. The full log is preserved on disk and is included in every export."

### Tests

`BoundedQueryPolicyTest` — asserts the property the directive asked for directly: rows delivered to the recent-trips screen is constant for 0, 1, 50, 51, 500 and 5 000 trips of history; the old path is shown failing the same assertion; raw-console row count is identical at 100 / 50 000 / 500 000 frames; newest frames are the ones shown; disk retention asserted separately from the display bound.

---

## Phase 4 — Import and data integrity

### Defects found and fixed

**IMP-04a — the archive's real size was discovered during extraction.**
The size ceiling only fired inside the decompression loop, i.e. after files were already written onto a volume that might be nearly full. New `inspectArchive()` reads the **central directory** and reports declared entry count, true uncompressed total, largest single entry, duplicate normalized names and unsafe paths — before a byte is extracted.

**IMP-04b — duplicate normalized filenames silently overwrote each other.**
The extractor keeps only `File(entryName).name`, so `a/trip.json` and `b/trip.json` both became `trip.json`; the second silently replaced the first. Two sessions could be merged or one destroyed with no error. Now rejected at pre-flight via `normalizeEntryName()`.

**IMP-04c — free space was compared against the wrong number.**
`requiredSpaceBytes()` = declared uncompressed total + `IMPORT_WORKING_RESERVE_BYTES` (128 MB), because extraction is not the only consumer — Room's journal and WAL grow during the commit. Checked against `usableSpace` before extraction.

**IMP-04d — the pre-flight copy would have defeated its own check.**
The archive must be copied to a seekable file to read its directory. I first put that copy inside the extraction temp dir, which would have added the archive's own size to the space just measured. Moved to its own cache directory and deleted immediately after inspection.

Entry-count (5 000) and per-entry (512 MB) ceilings from the previous audit are kept and now also enforced from the central directory; the in-loop checks remain as defence in depth against a directory that lies about its contents.

### Tests

`ArchivePreflightPolicyTest` — genuine backup passes; reserve arithmetic on both sides of the boundary; a compressible bomb judged on uncompressed size rather than download size; the `a/` vs `b/` collision rejected with normalization proving the collision exists; hidden-entry handling; traversal; every rejection reason; and an assertion that no rejection reason allows extraction to proceed.

### Not done (honest gap)

Staging-then-commit for the *database* half of the import (validate every session fully, then commit) is **not** implemented. Pre-flight now prevents the archive-level failures, and the existing temp-dir cleanup on failure is intact, but a mid-insert failure can still leave earlier sessions committed. Recorded as IMP-05, open.

---

## Phase 5 — Release build

### What exists

- Sideload APK is published to a rolling GitHub Release with `latest.json` (versionCode, `sha256`, `apkUrl`) — `build-apk.yml:330-380`.
- In-app update path verifies SHA-256 of what landed on disk and compares certificates before offering to install.
- Release variant exists with signing config; `isMinifyEnabled = false`.

### What is missing (not implemented — decided against, with reason)

**R8/minification for release (REL-03) is not enabled.** Enabling it here would be reckless: it changes the shipped binary, the app uses reflection-heavy code (Moshi, Room, Firebase, WebView bridges, Android Auto `CarAppService`), and **there is no way to run the resulting APK in this environment**. Shipping a minified build untested is exactly the "do not claim what you have not verified" failure the directive warns about. The correct sequence is: enable R8 → build → install on the CPH2793 → exercise connect/record/export/Auto → then ship. That is device work, listed below.

**A separate `release` build variant is not wired into CI.** The current sideload build is a `debug`-flavoured APK with debugging stripped (historical ColorOS install workarounds). Replacing that pipeline is a multi-step change with real risk of breaking the owner's ability to install at all, and cannot be verified without a device.

Neither was silently dropped; both are in "requires device testing".

---

## Phase 6 — Test and QA gates

**DB-03 (partially closed).** A full `MigrationTestHelper` suite needs a schema JSON per historical version. `exportSchema` had always been `false`, so those files have never existed for versions 1–13 and **cannot be reconstructed** — they describe what the code looked like at each past release. They accumulate from 14 onward now that export is enabled.

What is provable today is the registry, which no test covered. `MigrationRegistryTest` asserts every step 1→2 … 13→14 exists exactly once, the chain is contiguous, no migration starts from an already-used version, and none targets a version above 14. A gap there is a device that cannot open its database after updating, discovered only at runtime on the affected phone.

**REL-02 — lint gate added.** `lintDebug` never ran in CI. New `lint` job publishes error/warning counts to the job summary. Deliberately a **reporting** gate: the baseline of pre-existing findings is unknown, so gating the build immediately would block releases on old noise. Promotion path is written into the workflow.

### Actual CI result

```
run 43985842564 (aa9c137)     unit-tests: success   lint: success   build: success
134 suites, 1065 tests, 0 failed, 0 errored, 1 skipped      (was 1046)
```

Test count grew by 19 across this pass: `BoundedQueryPolicyTest` (5), `ArchivePreflightPolicyTest` (9), `MigrationRegistryTest` (4), plus one from the intermediate commit.

---

## Phase 7 — Outstanding work

### Requires a physical device (cannot be done here)

1. **R8 release build**: enable, build, install on CPH2793, exercise connect → live dashboards → record → export → import → Android Auto. Verify Moshi/Room/Firebase/keep-rules survived shrinking.
2. **Existing-install upgrade test**: install 1.0.880, then the new build over it; confirm signature match, DB intact, recordings readable, permissions retained.
3. **Interrupted-import test**: kill the process mid-import, confirm no half-written trip appears and existing trips survive.
4. **Migration on real data**: back up a 1.0.880 database, open with the new build, confirm every table readable.
5. **Bluetooth/ELM327 matrix** and **OBD reference-trace validation** carried over from the previous audit (BT-01…06, OBD-02…05).

### Decided by you, not by me

- **Fast-forward `main` to `03db0c5`** (Phase 1 plan). Nothing done; waiting on approval.
- **Set the four signing secrets** and remove `keystore/sideload.p12` from the tree. I cannot set repository secrets or confirm whether they already exist (403 from the API for this integration).

### Still open in the register

IMP-05 (staged DB import), REL-03 (R8), AI-03 (API key as query parameter), MEM-02 remainder (`samplesForTrips`/`trendSamples` are PID-bounded but unpaged), PERM-02, SEC-02.

---

## Production approval decision

**Not approved for a new release.** Justification:

- The work on this branch is a genuine improvement (three real memory defects, four import-hardening defects, a migration-registry test, a lint gate) and CI is green at 1065 tests.
- But the directive's acceptance criteria include **memory/endurance measurement**, **existing-install upgrade testing** and **release-build verification**, and none of those can be performed without a device. Reporting approval without them is precisely the failure mode the directive forbids.
- Independently, the **signing key remains compromised and public** until the secrets are set and the file is removed. That is a P1 release blocker I cannot resolve from code.

Recommended path to approval: set the signing secrets and remove the keystore (minutes, no risk), fast-forward `main` (minutes, no risk), then one device session covering upgrade-in-place, record-and-export, and an R8 build. After that this is releasable.

**Unverified in this report:** whether the signing secrets are currently configured; the lint finding baseline (job log not readable from this environment); anything about behaviour on real hardware.
