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
