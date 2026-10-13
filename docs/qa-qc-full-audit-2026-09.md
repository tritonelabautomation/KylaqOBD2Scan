# Full QA/QC audit — line-by-line loophole hunt (2026-09-09)

**Audited tree:** branch `arena/01a07c37-kylaqobd2scan`, head `8656910` (CI green: 29 suites / 290 tests / 0 failed).
**Method:** read/grep of every module in `app/src/main/java/com/example/` plus manifest, gradle, CI workflow and res/. No claim below is inferred — each carries a `file:line` citation from this exact tree. Severity: **HIGH** = can crash or lose data on a real device; **MED** = wrong behaviour/degradation under realistic conditions; **LOW** = polish/hygiene; **INFO** = verified-good or policy note.

---

## 1. Executive summary

| Severity | Count | Themes |
|---|---|---|
| HIGH | 2 | Background foreground-service start can throw on Android 12+ (fixed same-day); unmanaged coroutine scopes around Room writes can crash on DB error (fixed same-day) |
| MED | 8 | wake-lock expiry on >1 h drives (fixed same-day); 151 locale-less `String.format`; half-implemented light theme; silent catches hiding log loss; zero Compose UI tests; GPS-provider single-source; O(n) history trim; R8 disabled |
| LOW | 7 | notification icon asset, auto-connect poll cadence, README badge drift, no lint/coverage/dependabot stage, discovery-module test depth, prefs-log caps documentation, ZipImporter best-effort count |
| INFO | 14 | verified-good controls listed in §4 |

Fixes applied in the same commit as this audit: **H1, H2, M1, M7** (all small, additive, test-covered where possible). Everything else is scheduled in the roadmap (§5) with effort estimates.

---

## 2. Findings

### HIGH

**H1 — Foreground-service start from background can throw (Android 12+).** `MainActivity.kt:171` called `ContextCompat.startForegroundService` from a `LaunchedEffect`. The auto-connect loop (`MainViewModel.kt:694-706`, 10 s cadence) can reach `CONNECTED` while the activity is *stopped* (user switched apps); on API 31+ that raises `ForegroundServiceStartNotAllowedException`, and an exception escaping a `LaunchedEffect` is an uncaught coroutine exception → **app crash exactly when the owner is not looking at the phone**.
*Fix (applied):* start wrapped in `runCatching`; on that exception a `keepAliveRetryPending` flag is set and `LifecycleResumeEffect` (lifecycle-runtime-compose, already a dependency at `app/build.gradle.kts:141`) retries the start the moment the UI is resumed. Stop path also guarded.

**H2 — Unmanaged coroutine scopes around Room writes.** `RecordingManager.kt:114`, `:351`, `:361` use bare `CoroutineScope(Dispatchers.IO).launch { tripRepository.… }`. With no `CoroutineExceptionHandler` and no supervisor, a Room failure (disk full, migration conflict, cancelled transaction) becomes an uncaught exception on the IO dispatcher → **process crash mid-recording**.
*Fix (applied):* each launch body wrapped in `runCatching` with an error log line, preserving fire-and-forget semantics without the crash path. Lifecycle-tied scopes remain a roadmap item (P2).

### MEDIUM

**M1 — Wake lock silently expires on drives longer than 60 min.** `ObdKeepAliveService.kt:37,93`: `acquire(WAKELOCK_TIMEOUT_MS = 3 600 000 ms)`, refreshed only in `onStartCommand` (i.e. on connect/recording transitions). A 3-hour highway drive with no state change loses the wake lock at hour 1 → with screen off the CPU may sleep and polling intervals stretch.
*Fix (applied):* service-owned `CoroutineScope(SupervisorJob + Dispatchers.Default)` re-acquires every 45 min while alive; cancelled in `onDestroy`.

**M2 — 151 `String.format` call sites without an explicit `Locale`.** Repo-wide grep: 151 hits lacking `Locale.US` (contrast: the AC card and transport timestamps do pass `Locale.US`). On devices set to e.g. `de-DE` or `hi-IN`, `%f`/`%d` render comma decimals or Devanagari digits in gauges and logs; CI (en-US runner) can never catch it.
*Roadmap P2:* mechanical pass replacing with `String.format(Locale.US, …)` + a CI grep guard (`! grep -R "String.format(\"" app/src/main`).

**M3 — Light theme is only half-implemented.** `MainActivity.kt:104-109` wires LIGHT/SYSTEM/DARK correctly, but many screens hard-code the dark palette (`DarkCanvas`, `DarkSurface`, `TextSecondaryDark`, `Color.White`) — e.g. `DrivingDashboardScreen.kt` body and `ui/components/AcClimateCard.kt`. Selecting LIGHT yields dark cards on a light scaffold and near-invisible secondary text there.
*Roadmap P2:* introduce semantic color slots in `ui/theme` and migrate hard-coded references screen by screen (each screen its own verifiable commit).

**M4 — Thirteen empty catches; two hide real failures.** `grep -rn "catch (_: Exception) {}"` → 13 hits. Nine are defensible best-effort cleanups (socket/stream closes `Elm327Transport.kt:107,120,123,126`; sign-out `CloudBackupManager.kt:314`; zip sample count `ZipImporter.kt:191`; discovery parse guards `EcuDiscoveryManager.kt:867,898`). Two swallow **file-logging** errors: `RawLogManager.kt:95,115` — a full disk or permission loss silently kills the raw trace with no user-visible signal.
*Roadmap P2:* surface a one-shot "raw logging stopped" status flow from RawLogManager.

**M5 — Zero Compose UI tests for 35 screens; Robolectric excluded in CI.** `ui/screens/` contains 35 composables; `app/src/test/` has 35 files, all JVM-unit (models/codecs/analysis). `.github/workflows/build-apk.yml:47` runs with `-PciExcludeRobolectric=1`. Consequence proven by history: navigation-layer regressions (batch-16 compile-only breakage) are invisible until a device or the compiler complains.
*Roadmap P3:* Compose UI test artifact + a smoke test per root screen (launch → assert top-bar tag), run in a separate CI job.

**M6 — GPS is single-provider.** `GpsManager.kt:46` requests `GPS_PROVIDER` only (1 s / 5 m). Graceful degradation is good (`:50-62` returns Boolean, marks `isAvailable=false` when the provider is off), but in urban canyons/tunnels elevation and speed aiding stop entirely; `FUSED`/`NETWORK_PROVIDER` fallback would keep coast-detection elevation quality higher.
*Roadmap P3:* provider fallback chain + per-sample provider tag in the ride log.

**M7 — O(n) trim on the per-PID history hot path.** `ObdScheduler.kt:708-715`: `list.removeAt(0)` on an `ArrayList` of up to 500 `TransactionRecord`s, executed per received sample per PID → ~500-element array shift at steady state.
*Fix (applied):* trim via `subList(size-499, size)` copy before append — single O(cap) copy only when full, no per-element shift; public `StateFlow<Map<String, List<…>>>` contract unchanged.

**M8 — Release builds ship unminified.** `app/build.gradle.kts:96` `isMinifyEnabled = false`. APK is larger and all code (including the reflection-based RFCOMM fallback path) is trivially readable. Enabling R8 safely needs keep-rules for `createRfcommSocket` reflection and Compose/Room verification.
*Roadmap P3:* enable R8 on a branch, add `proguard-rules.pro` entries, validate with instrumented connect test before merging.

### LOW

**L1 — Keep-alive notification uses the adaptive launcher icon** (`ObdKeepAliveService.kt:75` `R.mipmap.ic_launcher`). Status-bar rendering of adaptive/colour icons is non-compliant on some OEM skins. *Roadmap P1:* monochrome `ic_stat_obd` vector.
**L2 — Auto-connect loop ignores adapter state.** `MainViewModel.kt:694-706` wakes every 10 s even with Bluetooth off; listening for `ACTION_STATE_CHANGED` would make it event-driven. *Roadmap P2.*
**L3 — README badge drift.** `README.md` badge text says 281 tests; suite count is now 29/290. *Fixed in this commit.*
**L4 — No static-analysis stage.** CI runs unit tests + assemble only; no `lint`, `detekt`, `ktlint`, coverage upload, or dependabot/renovate config. *Roadmap P1 (lint veto job), P3 (detekt/coverage).*
**L5 — `EcuDiscoveryManager` (~900 lines) is the least test-covered complex module;** its parse helpers at `:860-900` are guarded by empty catches rather than tests. *Roadmap P3:* extract pure parsers + adversarial tests (pattern already proven by `PidDecoderAdversarialTest`).
**L6 — Prefs-backed insight logs are capped but the caps are undocumented in-app:** coast 100 / tank 60 / ride 80 entries (`SettingsRepository.kt:332-338`). Oldest entries drop silently; a Settings line stating retention would prevent "where did my January rides go?" *Roadmap P1 (one-line Settings note).*
**L7 — `ZipImporter.kt:191` best-effort sample count** can under-report imported sample counts on malformed CSVs; acceptable, but the import summary should say "approx." when the count path failed. *Roadmap P2.*

---

## 3. Cross-cutting checks that came back CLEAN (verified, not assumed)

- **No secrets in source:** Gemini key read from `BuildConfig.GEMINI_API_KEY` with an explicit placeholder guard (`FirebaseAiDoctorProvider.kt:41-43,54-56`); no `AIza…/sk-/Bearer` literals anywhere.
- **No `GlobalScope`, no raw `Thread(`** in main sources; all scheduling via structured scopes or the service scope.
- **No TODO/FIXME/XXX debt** in `app/src/main`.
- **Room integrity:** version 9 with a complete `MIGRATION_1_2 … MIGRATION_8_9` chain and downgrade-only destructive fallback (`data/db/AppDatabase.kt:32,56-57`); no `allowMainThreadQueries`.
- **Auto Backup hygiene:** DB + recordings excluded from cloud backup (`res/xml/backup_rules.xml`), preventing multi-MB backup timeouts.
- **Bounded memory:** per-PID history capped 500 (`ObdScheduler.kt:710-712`), raw console buffer capped (`RawLogManager.kt:49,84`), prefs logs capped (`SettingsRepository.kt:332-338`).
- **Transport discipline:** single mutex-serialised command path, 15 s RFCOMM connect timeout, per-command timeouts, safety validator on every TX (`bluetooth/Elm327Transport.kt`).
- **GPS failure honesty:** provider-off returns false and flags `isAvailable=false` instead of masquerading as "stationary" (`GpsManager.kt:50-62`).
- **Android Auto lifecycle:** car screens retry init on lifecycle events instead of assuming readiness (`auto/ObdDashboardScreen.kt:75-102`).
- **Network posture:** no cleartext traffic config, no debuggable override in manifest/gradle.
- **Component exposure:** only `MainActivity`, the launcher alias activity and the mandatory `CarAppService` are exported; keep-alive service is `exported=false` with `foregroundServiceType="connectedDevice"` (`AndroidManifest.xml:38,53,79,86,104`).
- **Theme modes wired** LIGHT/SYSTEM/DARK at the root (`MainActivity.kt:104-109`).
- **CI transparency:** failure digest auto-posted to PR #1; trace-replay tests pin decoder behaviour to the owner-captured reference (`tools/trace_sim/reference_trace.txt`).

---

## 4. Roadmap (future work), phased and verifiable

**P1 — one-commit, zero-risk (≤1 h each):** monochrome notification icon; Settings retention note; `lint` CI job (non-veto → veto after one clean run); README badge auto-sync script.
**P2 — behaviour hardening (half-day each, own APK + device check):** locale-safe formatting pass + CI grep guard; light-theme semantic colour migration (per screen); RawLogManager failure surfacing; event-driven auto-connect; ZipImporter "approx." flag; lifecycle-tied scopes in RecordingManager.
**P3 — structural (day-scale, branch + instrumented proof):** Compose UI smoke-test job; R8 enablement with keep-rules; GPS provider fallback + provider tagging; EcuDiscovery parser extraction + adversarial tests; detekt + coverage upload; dependabot config.
**P4 — product ideas already validated by owner mandates:** owner-manual chat deep-link, refuel station history in RefuelDialog, Settings language/mileage-rate units, tablet navigation rail (WindowSizeClass), WCAG AA contrast pass on 9-10 sp captions.

Every phase ends with: CI green + owner-device verification before the next phase starts — the discipline adopted after the 2026-09-08 revert episode.

---

## 5. References

1. Foreground service start restrictions (Android 12+): https://developer.android.com/develop/background-work/services/foreground-services#fgs-access-from-background
2. Foreground service types & `connectedDevice`: https://developer.android.com/develop/background-work/services/foreground-service-types
3. Wake locks guidance & timeouts: https://developer.android.com/training/scheduling/wakelock
4. Uncaught exceptions in coroutines: https://kotlinlang.org/docs/exception-handling.html
5. Locale-safe formatting: https://developer.android.com/reference/java/util/Locale#default-locale
6. Room migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions
7. Auto Backup rules: https://developer.android.com/guide/topics/data/autobackup#IncludingFiles
8. Location providers & battery: https://developer.android.com/develop/sensors-and-location/location/strategies
9. R8 / shrink resources: https://developer.android.com/build/shrink-code
10. Compose UI testing: https://developer.android.com/develop/ui/compose/testing
11. Lifecycle-aware effects (`LifecycleResumeEffect`): https://developer.android.com/jetpack/androidx/releases/lifecycle
12. CI failure-digest tooling in-repo: `tools/ci/collect_test_failures.py`, workflow `.github/workflows/build-apk.yml`.

*Audit authored 2026-09-09 against head `8656910`; re-run this checklist after every P-phase merge.*
