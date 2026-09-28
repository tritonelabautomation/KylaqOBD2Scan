# Automatic Crash → GitHub — Zero-Touch Fix Loop (2026-09-23)

Owner request: "is there a way you can read reach app crash logs everything automatically and fix it. Like push error log automatically to GitHub some location which you have access so you can work anonymous with i need to every crash open upload to this chat. It should be like an event which should trigger you can work without my intervention"

## Solution

### 1. Local capture (already existed, now enhanced)
- `CrashJournal` installed in `KylaqApplication.onCreate()` as `Thread.setDefaultUncaughtExceptionHandler`
- Writes `files/crash_logs/crash_<epoch>.txt` with IST time, version, device, thread, full stacktrace
- Never deleted, rides in Drive backup (already in `DriveBackupClient`)

### 2. Auto-upload on next launch
- `KylaqApplication.onCreate()` now calls `CrashReporter.onAppStarted()`
- `onAppStarted()` → `startupCompleted()` + launches `uploadPendingCrashes()` on IO
- `uploadPendingCrashes()`:
  - Checks `SharedPreferences crash_reporter.uploaded_set` to avoid re-upload spam
  - Throttled to 1 upload per 5 min
  - For each pending file:
    - Logs to Firebase Crashlytics (anonymous, no token needed) if configured
    - If `BuildConfig.CRASH_REPORT_TOKEN` present, calls `GithubCrashUploader`:
      - POST `/repos/{repo}/issues` with title `Auto Crash: <first Exception line>` + body 20k truncated + labels `crash-auto,auto-reported,bug`
      - POST `/repos/{repo}/dispatches` event_type `crash-report` → triggers `crash-ingest.yml` workflow that commits file to `crashes/inbox/`
    - Marks uploaded (if token blank, marks immediately to avoid loop; if token present, marks only on success, else retries next launch)
  - Small 2s delay between uploads to avoid rate limit

### 3. GitHub side — agent accessible without chat upload
- `.github/workflows/crash-ingest.yml`: on `repository_dispatch: crash-report` or manual `workflow_dispatch`, creates `crashes/inbox/<ts>-<name>` and commits (skip ci) + creates issue if not recent dup
- `.github/workflows/crash-auto-triage.yml`: on `issues: opened/labeled` with `crash-auto`, auto-comments triage checklist and adds `triage-auto` label
- `crashes/README.md` explains flow
- Agent can now:
  ```bash
  gh issue list --label crash-auto --limit 20 --json number,title,createdAt,body
  gh issue view <n> --json body,comments
  ls crashes/inbox/
  ```

### 4. Opt-out
- `SettingsRepository.crashReportingEnabled` default true, toggle in Settings (future UI)
- `CrashReporter.uploadPendingCrashes()` checks `obd_research_prefs.crash_reporting_enabled` before any network

### 5. Privacy
- Only: IST timestamp, file name, app version/commit/run number, device model/Android SDK, thread name, stacktrace (20k truncated)
- No GPS, no VIN, no location, no raw OBD data
- Issue body labeled auto-reported, public repo — owner aware

### 6. Token setup (enables GitHub auto-push)
- Create fine-grained PAT: repo `tritonelabautomation/KylaqOBD2Scan`, permissions `Issues: Read+Write`, `Contents: Read+Write`, `Actions: Read+Write`
- Add as GitHub secret `CRASH_REPORT_TOKEN`
- Add to local `.env`: `CRASH_REPORT_TOKEN=github_pat_...`
- `app/build.gradle.kts` injects as `BuildConfig.CRASH_REPORT_TOKEN` + `CRASH_REPORT_REPO`
- Empty = no GitHub push, but local file + Drive + Crashlytics still work (fully anonymous fallback)

### 7. End-to-end without intervention
Crash → next launch auto-creates GitHub Issue `crash-auto` → workflow `crash-auto-triage` comments → Arena agent sees issue via `gh issue list` → fixes in `arena/...` branch → pushes → CI 814 tests → release → in-app updater prompts user. No manual upload to chat needed.

### Files added/modified
- `app/build.gradle.kts`: add crashlytics plugin + deps + BuildConfig fields
- `KylaqApplication.kt`: call `CrashReporter.onAppStarted()`
- `crash/CrashReporter.kt`, `crash/GithubCrashUploader.kt` (new)
- `data/SettingsRepository.kt`: `crashReportingEnabled`
- `.github/workflows/crash-ingest.yml`, `crash-auto-triage.yml` (new)
- `crashes/README.md`, `crashes/inbox/.gitkeep`
- `docs/crash-auto-reporting-2026-09-23.md` (this file)
