# Why Kylaq TSI Coach may not appear in Android Auto (and how to make it appear)

Owner question 2026-09-14: "why Kylaq TSI coach app is not showing in android auto?"

## Two gates, both external to our code

1. **Google's projected-AA category gate.** The production Android Auto launcher
   (phone projected onto the head unit) lists only Google-reviewed app categories
   (media, messaging, navigation, POI, parking, charging, EV, weather). An OBD
   telemetry dashboard is not an approved category, so a stock AA install will NEVER
   list it - this is policy, not a bug. RevHeadz-class dashboards reach head units
   only through the developer route below (or on Android Automotive OS head units
   via the car launcher, where our CAR_LAUNCHER/NAVIGATION activity entry applies).
2. **Manifest correctness (ours).** Fixed 2026-09-14: `androidx.car.app.minCarApiLevel`
   was declared at application level; the Car App Library spec requires it INSIDE the
   `<service>` element - the AA host reads service-level metadata only and could treat
   the app as incompatible. Service label also aligned to the app name so the launcher
   entry reads "Kylaq TSI Coach".

## The supported developer route (phone + any AA head unit)

1. Update the Android Auto app on the phone; verify AA itself works (Maps/media show).
2. Phone: Android Auto app -> Settings -> scroll to Version/About -> tap the version
   ~10 times until developer options unlock.
3. Enable **Unknown sources** (allows non-Play, category-outside car apps, incl. our
   CI/debug APK).
4. Reconnect the phone to the head unit (USB or wireless). If the launcher caches the
   app list, restart the head unit / AA once.
5. In the AA launcher open the Apps / custom strip: **Kylaq TSI Coach** should now be
   listed; launch it parked first. The session serves a driving-safe PaneTemplate
   (read-only telemetry rows), so AA keeps it visible per its distraction rules.

## Desktop Head Unit (DHU) alternative

Android SDK extras -> "Desktop Head Unit" emulator runs the projected AA host on a
desktop/phone without a car; our service binds to it with
`HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`, so DHU always shows the app - use it to
verify AA UI changes without the vehicle.

## What OBD data AA gets

AA shows whatever the phone app already has: connect the ELM327 in the phone app first
(Bluetooth), then AA mirrors the live dashboard panes. AA never talks to the dongle
itself.

## Round 2 (owner followed all 5 steps, AA still empty) - residual causes & fixes
Owner screenshots proved Developer mode + Unknown sources ON. Remaining suspects, in order:
1. **Stale APK** - About screen shows `build <commit count>`; the manifest fix landed at
   count 128, so the installed APK must read **build 129 or higher**.
2. **AA caches its car-app scan at start** - after installing a new APK: force-stop the
   Android Auto app (or reboot the phone) once, then reconnect.
3. **"Customise Launcher"** (AA Settings, visible in owner screenshot) - apps can be
   toggled OFF from the vehicle launcher strip there; ensure Kylaq TSI Coach is enabled.
4. **Bind-time crash hid the app**: `ObdCarSession.onCreateScreen` rethrew any throwable,
   failing the whole AA bind -> invisible app, no user-visible error. Now serves
   `ObdFallbackScreen` (diagnostic PaneTemplate) so the app ALWAYS appears; the fallback
   pane names the failure and points to the phone app.
5. **Black-dot notification**: keep-alive notification used the adaptive mipmap as
   smallIcon; status bars render those as a silhouette dot. New monochrome
   `drawable/ic_stat_kylaq` (white alpha glyph) fixes shade + AA media-style icons.
6. If 1-5 hold and the head unit still omits the Apps grid entry, the OEM AA skin is
   restricting the launcher; prove the phone side with the Desktop Head Unit emulator.
