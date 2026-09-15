# In-app updater — "Update available" instead of uninstall / download / extract / reinstall

Owner request, 2026-09-16:

> Hey everytime you push and generate a new .apk file in GitHub i need to uninstall
> existing download.zip extract install new .apk then again import all logs permissions.
> Is there a better way like update available I click it will automatically fetch latest
> update from GitHub when I click update similar to playstore it should update the app in
> background.

Short answer: **yes** — with one honest limit. Only a system installer (Play Store, or a
device-owner app) may replace an app with *no* user interaction. A sideloaded app can do
everything up to that line by itself, and then Android shows one confirmation. So the new
flow is:

> update prompt (or Settings → App update) → **Download & install** → progress bar →
> one system "Install?" tap → app reopens in place.

No uninstall, no `download.zip`, no extraction, no re-importing logs, no re-granting
permissions.

---

## Why every update used to demand an uninstall

This was not a packaging nuisance, it was a signing defect:

CI built `assembleDebug`, and every GitHub-hosted runner starts from a fresh VM with **no**
`~/.android/debug.keystore`. Gradle therefore generated a *new* debug key on every single
run. Two builds of the same app carried two different signatures, and Android refuses to
install a differently-signed APK over an existing one — the only way forward was to
uninstall, which deletes app storage: the Room database, every recording, every raw log,
the fuel ledger, and all granted permissions (Bluetooth, location, notifications).

**Fix:** one stable signing key for all sideloaded builds (`keystore/sideload.p12`), so a
new build has the same signature as the installed one and installs in place.

### The signing-key decision, and its trade-off

`keystore/sideload.p12` is **committed to this public repository**, together with its
public password (`kylaq.sideload.public`, alias `sideload`). That is a deliberate choice,
not an oversight:

- This repo *is* the distribution channel. The app only ever fetches
  `https://github.com/tritonelabautomation/KylaqOBD2Scan/releases/latest/download/…`, and
  publishing to that release requires **write access to the repo**. An attacker who can
  change what the updater downloads can already change the app's source and its CI.
- The downloaded APK's **SHA-256 is published alongside it** in `latest.json` and verified
  before the installer is invoked; a truncated or altered download is deleted, never
  installed (`UpdateManager.download`).
- It is a **dedicated sideload key**, separate from the Play upload key
  (`my-upload-key.jks`, which stays out of the repo and is gitignored). Publishing to Play
  later is unaffected.

What the committed key does *not* protect against: someone who can hand this owner an APK
out-of-band (not through the in-app updater) could produce one that Android treats as
"from the same developer". Rotation is cheap if that ever matters — generate a new key,
commit it, and accept one reinstall.

**Upgrade path with zero code change:** the build prefers secrets when present. Set
`SIDELOAD_KEYSTORE_BASE64`, `SIDELOAD_STORE_PASSWORD`, `SIDELOAD_KEY_PASSWORD` (and
optionally `SIDELOAD_KEY_ALIAS` / `SIDELOAD_KEYSTORE_PATH`) as repository secrets and the
committed key is ignored. Blank is treated as absent, because GitHub Actions sets an unset
secret to the empty string rather than leaving it undefined — without that rule an empty
password would silently break signing instead of falling back
(`envOrFallback` in `app/build.gradle.kts`).

---

## How an update travels

```
push to main or arena/*
      │
      ▼
 GitHub Actions  ── gradle assembleDebug (signed with the stable sideload key)
      │             versionCode = 10000 + GITHUB_RUN_NUMBER   (always increases)
      │             versionName = 1.0.<run number>
      ▼
 rolling Release "latest"   (push events only — a pull_request run must never publish
      │                      unreviewed code, and its token cannot write releases)
      ├── KylaqOBD2Scan.apk   stable asset name
      └── latest.json         versionCode, versionName, apkUrl, sha256, sizeBytes,
      │                       publishedAt, branch, commitSha, commitSubject, notes
      ▼
 phone: Settings → App update → Check (also auto-checked ≈ every 6 h at launch)
      │  GET .../releases/latest/download/latest.json     (public repo → no token)
      │  fail-closed parse: no positive versionCode, non-https apkUrl, or an
      │  absent/malformed sha256 ⇒ treated as "no update", never as an update
      ▼
 download to app-private filesDir/updates/, verify size + SHA-256
      ▼
 compare the installed signing certificate with the APK's
      │   match/unknown → hand to the package installer (one system confirmation)
      │   mismatch      → explain the one-time reinstall instead of firing an
      │                   installer that can only fail with "App not installed"
      ▼
 installed in place — Room DB, recordings, raw logs, fuel ledger, permissions intact
```

| Piece | File |
| --- | --- |
| Feed parsing, fail-closed rules, throttling, SHA-256, size/progress formatting (pure JVM) | `app/src/main/java/com/example/update/AppUpdateFeed.kt` |
| Fetch, verified download, signature comparison, install intent, "install unknown apps" routing | `app/src/main/java/com/example/update/UpdateManager.kt` |
| One flat UI state (`shouldPrompt`, progress, staged file, mismatch, error) | `app/src/main/java/com/example/update/UpdateUiState.kt` |
| Check / download / install actions, auto-check throttle stamp | `MainViewModel`, `SettingsRepository.lastUpdateCheckMs` |
| Launch prompt dialog | `MainActivity` (`dialog_app_update`) |
| Update card: installed build, new build, progress, install, check | `SettingsScreen` (`card_app_update`) |
| Stable signing + feed URLs in `BuildConfig` | `app/build.gradle.kts`, `keystore/sideload.p12` |
| Rolling-release publishing | `.github/workflows/build-apk.yml` → *Publish APK to the rolling release* |
| `REQUEST_INSTALL_PACKAGES` (documented, with the honest limit) | `app/src/main/AndroidManifest.xml` |
| Tests (17) | `app/src/test/java/com/example/AppUpdateFeedTest.kt` |

The APK is served by FileProvider from app-private storage
(`filesDir/updates/`), which the existing `<files-path name="files" path="." />` rule
already covers — no new provider paths, and nothing lands in shared storage.

---

## The one-time migration (builds before 2026-09-16)

The currently installed build predates stable signing, so it carries a throwaway CI key
and Android will refuse an in-place update from it. `UpdateManager` detects exactly this
(compares the installed certificate with the APK's) and says so instead of failing
cryptically. One last manual install is required:

1. **Back up first**: current build → Settings → Drive backup (or export ZIP). This
   preserves the recorded trips.
2. Uninstall the old build once.
3. Install the new build straight from the phone's browser — no ZIP, no extraction:
   `https://github.com/tritonelabautomation/KylaqOBD2Scan/releases/latest/download/KylaqOBD2Scan.apk`
4. Import the ZIP back (Trips & Recordings → Import ZIP).
5. From then on: **Settings → App update → Download & install**, or accept the launch
   prompt. Every later update keeps data and permissions.

Known cost of that single uninstall, stated plainly: the fuel ledger and ride logs live in
SharedPreferences, and unsaved drives live in `files/raw_logs/`; the Drive backup of the
*old* build covers `files/recordings/` only. Those three are therefore lost in this one
migration. Newer builds must make a reinstall lossless — see
`docs/known-gaps-and-honest-blanks.md`.

---

## Honest limits

- **No silent install.** Android grants that only to a system/store installer. The updater
  automates everything except the final confirmation, and the manifest comment says so.
- **`REQUEST_INSTALL_PACKAGES`** is a sensitive permission if this app were ever published
  to Play; for a sideloaded personal tool it is the standard mechanism. Android 8+ still
  requires the owner to allow "Install unknown apps" once, and `UpdateManager` routes
  there with an explanation rather than failing.
- **A locally built app always looks out of date.** Local builds get
  `versionCode = 100 + gitCommitCount` (< 10000), so any CI build outranks them. That is
  intended: the CI build is the distributable one.
- **The rolling release follows the working branch.** While development happens on
  `arena/*`, "latest" is that branch's newest build; after a merge to `main`, it is main's.
  `latest.json` carries `branch` and `commitSha`, and the UI shows both, so it is never
  ambiguous which build is being offered.
