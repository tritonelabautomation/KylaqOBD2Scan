# Senior Android Developer QA/QC Audit — KylaqOBD2Scan (v2)

**Date:** 2026-09-09 · **Auditor:** agent (senior Android review persona)
**Methodology:** [multica-ai/andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills) — the four principles applied literally:
1. **Think Before Coding** — no assumptions: every claim below was verified by reading the actual file (path:line cited).
2. **Simplicity First** — audit report + the minimum fixes that matter; nothing speculative added.
3. **Surgical Changes** — only 4 fixes applied (each traces to a finding); pre-existing cosmetic debt is *reported, not touched*.
4. **Goal-Driven Execution** — success criterion: CI green (compile + all unit tests) with the new parser suite; verified at the end.

**Scope:** `c3a09d3` + pending docs → 132 main Kotlin sources (35,378 LOC), 37→38 test suites, manifest, Gradle config, CI workflow.
Supersedes/complements: `docs/qa-qc-full-audit-2026-09.md` (Batch 21).

---

## 1. Verdict summary

| Area | Grade | One-liner |
|---|---|---|
| Manifest & permissions | **A** | BT permissions correctly split legacy/31+, `neverForLocation`, FGS `connectedDevice`, provider not exported |
| Concurrency & lifecycle | **A-** | Mutex-serialised transport, isActive-guarded poll loops, service scope cancelled in onDestroy; a few unstructured scopes (LOW-3) |
| Security | **A-** | No secrets in repo, env-var signing, backup/extraction rules present; release minify off (MED-2), keystore path now gitignored |
| Navigation completeness | **A** | 28 Screen objects ↔ 29 registered routes (2 parameterised), every screen ≥2 live references |
| Data integrity | **A-** | Codecs are pure + versioned + back-compatible; display-only formatting had a systemic locale hole (MED-1) |
| Test coverage | **B+** | 307→318 tests over engines/codecs/math/stores; the single most critical ingress point (Elm327Parser) was untested until now (MED-3, fixed) |
| UI/Compose hygiene | **B+** | No non-reactive `.value` reads in composables; 12/30 `items()` calls lack stable keys (MED-4) |
| Code hygiene | **A-** | 0 println / 0 TODO / 0 runBlocking / 0 GlobalScope / 0 empty catches / 0 prefs `.commit()`; 9 `printStackTrace` (LOW-1) |
| CI/CD | **A-** | Tests + APK + digest oracle + failure-tail fallback + KSP-flake retry; deprecation notices pending (LOW-4) |

## 2. Findings & fixes applied (surgical)

| # | Severity | Finding (evidence) | Action |
|---|---|---|---|
| MED-1 | Systemic display bug class | 154 `String.format` calls without a `Locale` (e.g. `MainActivity.kt:460-462`, `data/Fmt.kt:12-29`) — comma-decimal device locales render "12,3 km/L". Verified NONE feed parsed data (all display-only; the persistence codec correctly uses `Locale.US` at `FuelLogRepository.kt:205`) | **FIXED at the hub:** `Fmt.kt` now formats with `Locale.US` (7 call sites). Remaining 147 are cosmetic; recommend gradual cleanup + enabling Android Lint's default-locale check |
| MED-2 | Release hardening | `app/build.gradle.kts:96` `isMinifyEnabled = false` (and no `isShrinkResources`) for release | **REPORTED** (owner sideloads debug APKs; flip before any Play upload — one-line change + R8 rule verification pass) |
| MED-3 | Coverage hole on critical path | `Elm327Parser.parse` (`bluetooth/Elm327Parser.kt:22`) — the single ingress for every adapter byte — had **no dedicated test** (grep over `app/src/test` found zero references) | **FIXED:** new `Elm327ParserTest` (11 cases: hex frames, prompt stripping, multiline, NO DATA/CAN ERROR/UNABLE TO CONNECT/BUS INIT/MALFORMED/BUFFER FULL, prompt-only, timeout, whitespace) — every expectation traced to parser source lines |
| MED-4 | Compose state stability | 12 of 30 `items()` calls lack `key =` — highest-risk: `CoachChatScreen.kt:89` (append-heavy chat), `PidScannerScreen.kt:839` (raw log stream), `AiDoctorScreen.kt:474,589`, `DtcScannerScreen.kt:87`, `AddVehicleScreen.kt:390` | **REPORTED** (needs stable ids per model — a model-level change, not surgical; recommend `key = { it.id }` once chat/log entries carry ids) |
| MED-5 | Signing-material footgun | `build.gradle.kts:85` falls back to `${rootDir}/my-upload-key.jks`; `.gitignore` only listed `debug.keystore` — a dropped keystore could be committed | **FIXED:** `.gitignore` now blocks `my-upload-key.jks`, `*.jks`, `*.keystore` |
| LOW-1→fixed | Crash class | `GpsManager.kt:76` `lastLocation!!` on a mutable property (race-prone NPE) | **FIXED:** `lastLocation?.let { … }` — behaviourally identical, crash-proof |

## 3. Reported, deliberately NOT touched (skill #3: not my mess, not broken)

- **LOW-1:** 9 `printStackTrace` (`GpsManager.kt:57,70`, `RawLogManager.kt:106`, `ZipImporter.kt:122,333`, +4) — should be `Log.w(TAG, msg, e)` for release logcat hygiene.
- **LOW-2:** 11 remaining `!!`, all inside null-guarded blocks (`AutoScanObdScreen.kt:129`, `DriveBackupScreen.kt:166`, `ProfilesScreen.kt:111,121`, `FuelQualityAnalyzer.kt:121`, `TurboAnalyzer.kt:83`, …) — theoretical recomposition races only.
- **LOW-3:** unstructured `CoroutineScope(Dispatchers.IO).launch` at `RecordingManager.kt:114,354,365` and `AppContainer.kt:57` — no cancellation handle; bodies are short writes, so leak risk is minimal. Recommend one injected `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- **LOW-4:** CI deprecation warnings: `setup-java@v4` → v5; Node-20 action deprecation notices (checkout/upload-artifact/setup-gradle majors).
- **INFO:** `PidDefinition.kt` is 1,728 lines (data table — fine); `MainViewModel.kt` is 1,358 lines and accreting feature hooks — recommend splitting per-domain VMs at the next natural seam (not mid-batch).

## 4. What passed verification (evidence, not vibes)

- **Permissions:** `AndroidManifest.xml:9-17` — legacy BT capped at 30, `BLUETOOTH_SCAN neverForLocation`, FGS types declared; `ObdKeepAliveService` `foregroundServiceType="connectedDevice"` (:80).
- **Concurrency:** `Elm327Transport.kt:63,131` `transportMutex.withLock` serialises every adapter transaction; `ObdScheduler.kt:181,194,213` loops gated on `isActive && transport.isConnected`; `ObdKeepAliveService.kt:115-120` cancels `refreshScope` in `onDestroy`.
- **Security:** signing reads `STORE_PASSWORD`/`KEY_PASSWORD` from env only (`build.gradle.kts:87-89`); zero `AIza`/hardcoded keys repo-wide; `dataExtractionRules` + `fullBackupContent` present (`AndroidManifest.xml:24-25`); FileProvider `exported=false` (:104).
- **Safety architecture:** `SafetyValidator.kt:42-53` — 0x22 read-only allowed; 0x2E/0x27/0x10/flash categorically rejected; covered by tests (`ExampleUnitTest`, `KylaqMasterRepairTest`).
- **Navigation:** every one of the 28 `Screen` objects resolves to a registered route (26 direct + `PidDetail`/`TripDetail` parameterised at `MainActivity.kt:389,567`) and has ≥2 live call sites.
- **Compose reactivity:** 0 non-reactive `viewModel.x.value` reads inside `ui/screens` (the Batch-15-era bug class is extinct).
- **Hygiene greps:** println 0 · TODO/FIXME 0 · runBlocking 0 · GlobalScope 0 · empty catch 0 · `Thread.sleep` 0 (2 hits are comments) · prefs `.commit()` 0 · SimpleDateFormat-without-Locale 0.

## 5. Test coverage map (38 suites after this audit)

- **Protocol/decoders:** PidDecoderAdversarial, DecoderRealWorldVerification, DtcDecoderAdversarial, **Elm327ParserTest (NEW)**, CodingLabCodecTest, KylaqTraceReplay, KylaqRealWorldTraceIntegration, KylaqDiscoveryComprehensive, PidDiscovery{Decoder,Service}
- **Engines/physics:** PowertrainEngines, GearModel, TransmissionDeviation, TripMath, TripFuelSummary, TripTrendAnalyzer, DriveIntelligence, RideBehaviorRecorder, AcClimateModel, FuelQuality (via DriveInsights)
- **Data/stores:** FuelLogCodec, FuelLogParity, DriveInsightsStore, LiveTelemetryStore, FleetStores, FleetExporter, BackupAndImport, MaintenanceCatalog, CatalogSelection, TripPlans, FmtAndReceiptParse
- **Safety/service:** SafetyValidator (2 suites), ObdKeepAliveServiceTest
- **VIN:** VinAuthority A/B, VinDecoderValidation A/B
- Known untested layers (accepted): Compose UI (no instrumentation in CI), SharedPreferences-backed repositories (Robolectric excluded from CI by design — `9c5353c`), Drive/GMS integration (device-only).

## 6. Goal-driven verification (skill #4)

Success criterion: after the 4 fixes, CI compiles and **all suites pass including the new 11 parser tests** (307 → 318). Result recorded in the PR digest comment for the audit commit — see `gh api repos/tritonelabautomation/KylaqOBD2Scan/issues/1/comments` (latest entry).

---

## Addendum 2026-09-12 — Post-mortem: why the audits missed the fuel/dashboard lineage bugs

Owner question after the zeroed fuel card and empty dashboard grid: *"Why didn't previous
QA & QC catch this?"* Honest answer, no excuses:

1. **Both audits were static.** Grep/structure/manifest/concurrency checks and pure-unit
   suites verify how code *looks* and how isolated maths behaves. The fuel bug was a
   *cross-layer data-lineage* defect: storage writes 2-hex PID suffixes
   (`TransactionRecord.pid`), the analyser reads 4-hex keys (`010D`). Every existing test
   fed `summarize()` the 4-hex spelling because the test author assumed the writer's
   format — the exact assumption that was wrong. No test ever crossed the
   writer→storage→reader boundary.
2. **Configuration-absence bugs are invisible statically.** 015E/019D were catalogued,
   enabled, decoded and unit-decoded correctly; they were simply absent from three
   validation lists, so the eligibility gate never polled them. Nothing in a static read
   contradicts itself — only a runtime assertion "every displayed metric has a validated
   source" catches it.
3. **The dashboard advertised ~30 tiles while polling validated ~11 PIDs.** Same class,
   bigger blast radius: the capability gate (added earlier to kill phantom values)
   silently starved every unvalidated tile into "Not available".
4. **There was no device-or-truth oracle for displayed values.** Trace-replay tests stop
   at the decoder; nothing replays a trace through storage → analysis → UI contract.

### Guards added with the fixes (make the class unrepeatable)
- `TripFuelSummaryTest`: regression feeding **stored-format (2-hex)** samples.
- `normalizePidKey()` canonicalises every spelling at the analysis boundary.
- **Progressive auto-probe** in `ObdScheduler` polling loop: ONE unresolved enabled PID
  probed per cycle → every displayed tile (present and future) self-validates at
  runtime; refusals become explicit `NOT_SUPPORTED`.
- **Honest tiles**: `TelemetryDashboardContent` renders "NOT SUPPORTED BY ECU" /
  "no answer (timeout)" / "probing..." instead of silent "Not available";
  `DashboardHonestLabelsTest` locks the contract.
- Loud fuel-card states for missing samples / unanswered fuel-rate PIDs.

### Standing rule for future audits (lineage checklist per displayed metric)
For every value a screen shows: (a) name its source PID/flow, (b) prove the PID is in the
poll/validation path, (c) prove the stored key format matches the reader's lookup,
(d) prove the empty state is loud. A metric failing any step is a finding, not a todo.
