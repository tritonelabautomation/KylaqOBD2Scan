# Automatic Crash Reports

This directory receives crash logs automatically from the app (owner request 2026-09-23: "push error log automatically to GitHub some location which you have access so you can work anonymous").

## Flow
1. App crashes → `CrashJournal` writes `files/crash_logs/crash_<ms>.txt` (IST, device, stacktrace) — never deleted
2. Next launch `KylaqApplication.onCreate()` → `CrashReporter.onAppStarted()` → IO scope uploads pending crashes
3. Channels:
   - **Firebase Crashlytics** (anonymous, no token) — always, if `google-services.json` present
   - **GitHub Issue** with label `crash-auto` — if `BuildConfig.CRASH_REPORT_TOKEN` present (fine-grained PAT `issues:write`)
   - **Drive backup** — `crash_logs/` already included in `DriveBackupClient` backup (automatic)
   - **Repository dispatch** → workflow `crash-ingest.yml` commits file to `crashes/inbox/`

## Agent access (no user upload needed)
Arena agent can:
```bash
gh issue list --label crash-auto --limit 20 --json number,title,createdAt,body --jq '.[]'
gh issue view <number> --json body,comments
ls crashes/inbox/
```

Workflow `crash-auto-triage.yml` auto-comments on new `crash-auto` issues with triage checklist.

## Token setup (optional but enables GitHub auto-push)
1. Create fine-grained PAT: repo `tritonelabautomation/KylaqOBD2Scan`, permissions `Issues: Read+Write`, `Contents: Read+Write`, `Actions: Read+Write` (for dispatch)
2. Add as GitHub secret `CRASH_REPORT_TOKEN`
3. Add to local `.env`: `CRASH_REPORT_TOKEN=github_pat_...`
4. `app/build.gradle.kts` injects it as `BuildConfig.CRASH_REPORT_TOKEN` — empty = no GitHub push, still local+Drive

## Privacy
Crash logs contain: IST timestamp, app version/commit, device model/Android version, thread name, stacktrace, last session id (no GPS, no VIN, no location). Truncated to 20k chars.

## Never-lose-logs
Files in `files/crash_logs/` are never deleted by app, ride in every Drive backup, and now also auto-push to GitHub. Owner never needs to manually upload in chat.
