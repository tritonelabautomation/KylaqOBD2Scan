# Full-application engineering audit — KylaqOBD2Scan

**Audited commit:** `2baa0cfaf3f501ebf16746603baeb9f6383469d6` (branch `arena/e5f3e3fc-kylaqobd2scan`, PR #23, `pr_open`)
**Comparison baseline:** `main` = `d110bb425a4363d27218d3b4d3d20bef69558f1d`
**Date:** 2026-11-12
**Method:** static source audit of 222 tracked files (109 Kotlin, 68 Python, 12 XML, 3 Gradle KTS). Not a physical-device QA certification.

> Branch note: the directive asked for a new branch `arena/full-app-hardening`. This session is bound to
> `arena/e5f3e3fc-kylaqobd2scan` and cannot create or switch branches. All work continues on that branch,
> which is `pr_open` against `main`, so nothing reaches `main` without review.

---

## 0. Repository state that changes the picture

**This branch is 107 commits and 438 files ahead of `main`, and none of it is merged.**

```
git rev-list --count origin/main..HEAD   -> 107
git diff --stat origin/main..HEAD        -> 438 files changed, 79 347 insertions(+), 2 205 deletions(-)
```

PR #23 is titled *"Fix tank %→litre maths (45 L Kylaq spec)…"*, which describes one commit out of 107.
The published release (1.0.868) is built from this branch, so **the shipped app and `main` are not the same
code**, and `main` is ~79k lines behind what the owner is running. This is the single largest release-
engineering risk in the project and it is not a code defect — it is a process defect. It is recorded here as
**REL-01** because every other finding's blast radius depends on which branch actually ships.

---

## 1. Corrections to the external audit

The peer audit was directionally right about recording memory, but several specific claims do not hold
against this tree. Stating this precisely, because acting on false findings wastes work and can break
working code.

| Peer claim | Verdict | Evidence |
|---|---|---|
| **P0-03** "CI does not run the tests; only `assembleDebug`" | **Already fixed on this branch** | `.github/workflows/build-apk.yml:25` defines a `unit-tests` job running `gradle testDebugUnitTest`; `:141` `needs: unit-tests` gates the build job. The cited state is real for `main` (`git show d110bb4:.github/workflows/build-apk.yml`) — `main` line 34 is bare `assembleDebug`. So the finding is true of `main`, false of the branch that ships. |
| **IMP-01** "JSON import reads complete files into memory" | **Fixed** | `ZipImporter.kt:198-202` now calls `SessionJsonReader.readSession(...)`; the old `JSONObject(jsonFile.readText())` is gone from the import path. |
| **GPS** "`lastLocation`/`totalDistance` are not reset when a new session starts" | **Was true at the audit's baseline; already fixed** | At `d110bb4:…/GpsManager.kt`, `startTracking()` never reset either field. Current `GpsManager.kt:84-86` resets `tripAltitude`, `totalDistance`, `lastLocation`. Introduced by commit `3ba15fa`. |
| "ZIP import has a **100 MB** uncompressed ceiling" | **Wrong number** | `ZipImporter.kt:32` — `MAX_ZIP_UNCOMPRESSED_BYTES = 2048 * 1024 * 1024L` (2 GB). Path-traversal check *does* exist (`:553`). The real gap is entry count and per-entry size, not the total. |
| **DB-02** destructive downgrade | **Confirmed, but narrower than stated** | `AppDatabase.kt:59` `.fallbackToDestructiveMigrationOnDowngrade()` — **downgrade only**. Upgrades run 13 explicit migrations (`:58`, `MIGRATION_1_2`…`MIGRATION_13_14`). Normal app updates do not wipe. |
| **AI-01** API key embedded in `BuildConfig` | **Confirmed in shape, inert in the shipped APK** | Key comes from `.env` via the secrets plugin (`app/build.gradle.kts:190`); `.env` is gitignored (`.gitignore:17`); CI supplies no `GEMINI_API_KEY`, so the release APK carries the `.env.example` placeholder `MY_GEMINI_API_KEY`. `FirebaseAiDoctorProvider.kt:42` rejects it and falls back to `RuleBasedChatProvider`. The *architecture* is still wrong — any build with a real key ships an extractable secret. |

Several peer "P1" items (BT-01/02/03/06, OBD-02/03/04/05, E-instrumentation) are **test-coverage requests
requiring hardware**, not identified code defects. They are in the plan as work, but they are not counted as
defects below, because claiming them as defects would overstate what a source audit can prove.

---

## 2. Defect register

Severity: **P0** data loss / crash / security / core-diagnostic corruption · **P1** major functionality,
performance, reliability · **P2** UI, maintainability · **P3** cosmetic.
Classification: **CD** confirmed defect · **SD** suspected defect · **RDT** requires device testing ·
**IR** improvement recommendation.

### P0

| ID | Cls | File:line | Explanation | Reproduction | Confidence | User impact | Correction |
|---|---|---|---|---|---|---|---|
| **DB-01** | CD | `data/db/AppDatabase.kt:34` `exportSchema = false` | Room never writes schema JSON. Room's generated migration validator therefore has no historical schemas to compare against, and no schema diff is reviewable in git. | Any PR that alters an entity cannot be checked for an accidental column/type change. | High | Silent schema drift; a future migration can pass CI and corrupt or drop columns on real data. | Set `exportSchema = true`, add the KSP `room.schemaLocation` arg, commit `app/schemas/`. |
| **DB-02** | CD | `data/db/AppDatabase.kt:59` `fallbackToDestructiveMigrationOnDowngrade()` | If the DB version on disk is *higher* than the running APK (e.g. owner sideloads an older release, or rolls back a bad update), Room **deletes the entire database** with no warning. | Install 1.0.868 (v14), then install an earlier APK (v13). All trips, samples, raw logs, DTC history vanish. | High | Total loss of all diagnostic history — directly violates the standing "never lose recorded data" rule. | Remove the fallback; on downgrade, refuse to open and surface a clear "this data was written by a newer version" message rather than deleting. |
| **DB-03** | CD | `app/src/test`, `app/src/androidTest` | **Zero** migration tests. No `MigrationTestHelper`, no schema fixtures. 13 hand-written migrations have never been executed by a test. | Introduce a typo in `MIGRATION_9_10`'s SQL; the full suite still passes (it does not touch Room migrations at all). | High | A broken migration reaches the owner as a crash-on-launch or silent data loss. | Add Room `MigrationTestHelper` tests once DB-01 exports schemas; test every version boundary and one interrupted migration. |
| **API-01** | CD | `ui/viewmodel/ManualViewModel.kt:200`, called from `ui/screens/OwnerManualScreen.kt:429` | `fun mediaBlocking(...) = runBlocking { … }` is invoked from `WebViewClient.shouldInterceptRequest`, which Android calls **on the UI thread**. | Open the owner's manual, scroll to an image-heavy page. | High | Blocks the main thread on disk I/O per image; ANR under a slow device or large media. StrictMode would flag it. | Make the interceptor return `null` and pre-resolve media asynchronously, or serve from a `WebViewAssetLoader`; never `runBlocking` on the UI thread. |
| **AI-02** | CD | `ui/viewmodel/MainViewModel.kt:3091`, `:3115`; `ai/FirebaseAiDoctorProvider.kt:135` | `_aiChatHistory` grows without bound for the process lifetime; the whole list is serialised into every Gemini request as `contents`. No trim, no summarisation, no request-size cap. | Ask the AI Doctor ~50 questions in one session. | High | Growing heap, growing request bodies, quadratically rising token cost, eventual 400 from the API. | Keep the last N turns in the request, summarise the rest, cap the serialised size, cap stored history. |
| **IMP-04** | CD | `data/ZipImporter.kt:540-570` | Unzip loop enforces only a 2 GB **total** byte ceiling. No entry-count limit, no per-entry size limit, no free-space check before extracting. | Import a crafted ZIP with 100 000 tiny entries, or 2 GB of content on a phone with 1 GB free. | High | Storage exhaustion, `IOException` mid-import, possible partial import left behind; on a full volume the app can destabilise system-wide. | Cap entry count (e.g. 5 000), cap per-entry bytes, check `StatFs` free space against the declared uncompressed size before writing, extract to staging and commit on success. |
| **SEC-01** | CD | `AndroidManifest.xml:84` `android:allowBackup="true"`, no `fullBackupContent`/`dataExtractionRules` | The whole `filesDir` — recordings, raw logs, GPS traces — is eligible for cloud auto-backup and device-to-device transfer. Vehicle and location data leave the device under the app's implicit consent. | Check Google account backup for the device. | High | Silent exfiltration of location/vehicle history; also a restore of stale data into a fresh install. | Set `allowBackup="false"`, or define `dataExtractionRules` that exclude `recordings/`, `raw_logs/`, and the DB, and keep an explicit user-driven backup path instead. |
| **REL-01** | CD | repository process | Branch is 107 commits / 438 files ahead of `main` and unmerged; `main` is 79k lines behind the shipping release. | Compare `origin/main` with the published APK. | High | Any contributor (or agent) branching from `main` — as this directive originally proposed — starts from code that cannot reproduce owner-reported bugs, and fixes get re-litigated. | Land PR #23 (or explicitly declare the branch the trunk), then keep `main` == shipped. |

### P1

| ID | Cls | File:line | Explanation | Impact | Correction |
|---|---|---|---|---|---|
| **MEM-01** | CD | `data/GpxExporter.kt:88-117` | `exportToGpx`/`exportToKml` build the entire document in a `StringBuilder` (`joinToString` over every point, then `writer.toString()`) and only then `writeText`. Two full copies of the whole route. | OOM risk on a long trip's route export — the same class of failure as the 2026-11-16 crash, in a path my earlier fix did not cover. | Stream to the `File` with a `BufferedWriter`; write coordinates incrementally. |
| **MEM-02** | CD | `data/db/dao/TripDaos.kt:62, 74, 81, 105` | `getSamplesForTrip`, `getSamplesForPid`, `getSamplesForTrips`, `getRawLogsForTrip` return unbounded `List`s. A mitigation exists (`getSamplesForTripStrided`, `:71`) but the unbounded variants are still live. | A 4-hour recording (the directive's own endurance target) can materialise hundreds of thousands of rows at once. | Add `LIMIT`/paging (`PagingSource` or keyset), and route UI reads through the strided/bounded variants. |
| **DB-04** | CD | `data/db/TripRepository.kt:295-309` | `deleteTrip` deletes 5 tables with **5 independent DAO calls**, no `@Transaction`. A failure or process death midway leaves orphaned samples/raw logs/events/AI analyses against a deleted trip. | Partial deletes accumulate; storage stats drift; orphan rows are invisible in the UI but still consume space. | Wrap in a single `@Transaction` repository method (or `db.withTransaction`). |
| **DB-05** | CD | `data/db/TripRepository.kt:295-309` vs `data/RecordingManager.kt:2209-2232` | Deletion is split across two stores with opposite orders and no shared transaction: `RecordingManager.deleteRecording` deletes the **files first**, then fires the DB delete as a detached `CoroutineScope(Dispatchers.IO).launch` whose failure is only logged. | If the DB delete fails, the files are already gone and the trip rows are unreadable zombies with no recovery path. Directly contradicts "nothing shed must be unrebuildable". | Delete in one ordered, transactional operation; do not remove files until the DB delete is confirmed, and keep the recovery path able to rebuild from whatever remains. |
| **ARCH-01** | CD | `data/RecordingManager.kt:2214, 2223` | `CoroutineScope(Dispatchers.IO).launch { … }` creates a scope with **no job, no lifecycle, no cancellation** — two instances in the delete paths (also `:2334` area). | Work survives ViewModel death, cannot be cancelled, and errors vanish into a log line. | Use an owned `SupervisorJob` scope with a defined lifetime, or move the work into a suspend function called from `viewModelScope`. |
| **PERM-01** | CD | `MainActivity.kt:312-313` | `val allGranted = permissions.values.all { it }` folds location, Bluetooth **and** `POST_NOTIFICATIONS` into one "Bluetooth OK" flag. Denying only the notification prompt sets it false. | The app believes Bluetooth is unavailable when it is granted. Currently the flag has **no readers** (`:307`, `:313` only), so impact is latent — a refactor that consumes it introduces a real bug. | Compute the flag from the Bluetooth permissions *only*. |
| **IMP-05** | CD | `data/ZipImporter.kt` (extraction + import flow) | On any failure after `unzipSafely`, the temp dir is deleted (`:147`) but nothing proves partially-written trip rows/recordings are rolled back; there is no staging-then-commit boundary. | A failed import can leave half a trip visible. | Extract → validate → import into staging → commit atomically; never publish until fully validated. |
| **REL-02** | CD | `.github/workflows/build-apk.yml` | **No `lintDebug` gate** anywhere in CI (`grep lint .github/workflows/*.yml` → no match). | Android-lint findings — including the `allowBackup`, permission and lifecycle checks above — are never enforced. | Add `gradle lintDebug` as a job; fail on `error`-severity. |
| **REL-03** | CD | `app/build.gradle.kts:157` `isMinifyEnabled = false` (release) | Release ships unshrunk and unobfuscated; `proguard-rules.pro` exists but is never applied. | ~74 MB APK, no dead-code removal, no obfuscation of the embedded `BuildConfig` key. | Enable R8 for release, verify the keep rules, and test the APK on device before shipping. |
| **AI-03** | SD | `ai/FirebaseAiDoctorProvider.kt:135` | API key travels as a **query parameter**, which lands in proxy/access logs and is retained by intermediaries. | Key exposure beyond the APK-extraction risk. | Use the `x-goog-api-key` header; better, move to a backend proxy with App Check. |

### P2 / P3

| ID | Cls | File:line | Note |
|---|---|---|---|
| PERM-02 | RDT | `MainActivity.kt:344-356` | `POST_NOTIFICATIONS` is bundled into the *startup* request. Denial is indistinguishable from Bluetooth denial in the callback. Behaviour needs device verification on Android 13+. |
| UX-01 | IR | `MainActivity.kt:337` | Location is requested at launch rather than at the point of use; Play policy and user trust both favour deferred, contextual requests. |
| SEC-02 | CD | `AndroidManifest.xml:100-118` | `MainActivity` and `UpdateInstallActivity` are `exported="true"`; `BluetoothStateReceiver` and `KeepAliveBootReceiver` are exported with system broadcasts. Verify no sensitive `Intent` extras are trusted from outside. |
| OBD-06 | CD (partly fixed) | `protocol/PidDecoder.kt:45-49`, `:295` | Negative responses (`7F`) are correctly rejected, and implausible values map to `null` rather than a fake zero — this requirement is already met. Remaining work is to prove it across every PID with reference vectors. |
| BT-04 | CD (by design) | `bluetooth/Elm327Parser.kt:112` | The `else ->` branch classifies any unrecognised non-empty response as `OK`. Acceptable *only* because `CanFrameParser`/`PidDecoder` validate downstream. Worth an explicit comment plus adversarial tests so nobody later "simplifies" the validation away. |

---

## 3. Repair plan (dependency-ordered)

Ordering is driven by blast radius and by which fixes unlock tests for the rest.

**Phase A — unblock and de-risk (no user-visible change)**
1. **REL-01** Merge/declare PR #23 so `main` matches the shipped APK. Nothing else is safe to plan until this is settled.
2. **REL-02** Add a `lintDebug` CI job — read-only at first, so the finding count is visible before anything is enforced.

**Phase B — data-safety P0s (each independently shippable)**
3. **DB-01** `exportSchema = true` + commit `app/schemas/`. Prerequisite for DB-03.
4. **DB-02** Remove `fallbackToDestructiveMigrationOnDowngrade()`; replace with an explicit refuse-and-explain path. *Requires a test proving a downgrade cannot delete data.*
5. **DB-04 + DB-05 + ARCH-01** One transactional delete, files removed only after the DB confirms, detached scopes removed. These three touch the same call path and should be one change with one test file.
6. **DB-03** Migration tests for all 13 boundaries, enabled by step 3.

**Phase C — memory P0/P1 (same failure family as the 2026-11-16 crash)**
7. **MEM-01** Stream GPX/KML export.
8. **MEM-02** Bound the DAO reads; route UI through strided/paged queries.

**Phase D — security and permissions**
9. **SEC-01** `allowBackup` + `dataExtractionRules`.
10. **API-01** Remove `runBlocking` from the WebView interceptor.
11. **AI-02 + AI-03** Cap conversation history, cap request size, move the key to a header, document the backend-proxy recommendation.
12. **PERM-01 + PERM-02** Bluetooth-only permission folding; contextual notification request.

**Phase E — import hardening**
13. **IMP-04 + IMP-05** Entry/per-entry/free-space limits plus staging-then-commit.

**Phase F — release engineering**
14. **REL-03** R8 for release, with on-device verification of the shrunk APK.

**Phase G — hardware-dependent (cannot be completed in this environment)**
15. BT-01…BT-06, OBD-02…OBD-05: adapter-firmware matrix, reconnect stress, ISO-TP addressing. These require a CPH2793, a real ELM327, and a Kylaq.

---

## 4. Regression-test plan per fix

| Defect | Test to add | Type |
|---|---|---|
| DB-02 | Downgrade-simulation: open a v14 DB with a v13 schema config; assert it **throws/refuses and the file is still intact** — never that the file is deleted. | Robolectric |
| DB-03 | `MigrationTestHelper` per boundary (1→2 … 13→14), plus a truncated-migration case. | Robolectric + Room testing |
| DB-04/05 | Delete a trip, then assert **all five** tables have zero rows for that ID, and assert file removal is skipped when the DB step fails. | Robolectric |
| ARCH-01 | Cancel the owning scope, assert the delete does not continue. | Unit |
| MEM-01 | Export a route of N points with a bounded writer; assert output is identical to today's and no full-document `String` is built. | Unit |
| AI-02 | Feed 100 turns; assert the serialised request stays under the cap and the tail is preserved. | Unit |
| IMP-04 | ZIP with 10 001 entries is rejected before extraction; per-entry ceiling enforced; free-space guard triggers. | Unit |
| PERM-01 | Notification denied + Bluetooth granted ⇒ Bluetooth flag true. | Unit |
| API-01 | Media request from the interceptor performs no blocking call on the calling thread. | Unit (StrictMode) |

---

## 5. What this audit cannot claim

- **No physical-device verification was performed.** No CPH2793, no ELM327, no vehicle is available here.
- **No Gradle/JDK in this sandbox**, so nothing below has been compiled locally. Correctness is established by CI (`unit-tests` + `build` jobs) and by porting pure logic to Python for spot-checks.
- **Memory/endurance numbers are absent by necessity**: the directive's 4-hour / 500-trip / low-memory targets need a device or an emulator profiler. They are proposed as gates, not reported as results.
- Items marked **RDT** are explicitly *not* proven defects.

## 6. Delivery status

Stage 1 (this register) and Stage 2 (the plan above) are complete. **No code has been changed for the findings in this document yet.** Per the directive, implementation begins only once the audit and plan are committed.
