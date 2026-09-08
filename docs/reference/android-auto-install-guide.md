# Android Auto: why a sideloaded APK never appears on a real head unit (and the 3 ways that work)

Verified against Google's official Android-for-Cars testing docs (fetched 2026-09-08):
https://developer.android.com/training/cars/testing#real-vehicles

> "To test your app in real vehicles, you must install it from a trusted source such as
> Google Play … You can use Internal App Sharing or an Internal Test Track to distribute
> your app to devices without going through the Google Play review process."
>
> "Android Auto has a developer option that lets you run apps that aren't installed from a
> trusted source. **This setting applies to media, messaging/notifications, and parked apps
> but doesn't apply to apps built using the Android for Cars App Library.**"

## What this means for Kylaq TSI Coach

- The in-app car dashboard (`auto/ObdCarAppService.kt`, IOT category, exported, minCarApiLevel 1,
  self-initialising `AppContainer`, auto-connect + 2 Hz template throttle) is **correct** —
  it renders in the Desktop Head Unit emulator.
- On the **real head unit**, Android Auto ignores the "unknown sources" developer toggle for
  Car-App-Library template apps. A sideloaded/ADB-installed APK is therefore invisible in the
  car launcher no matter which toggles are flipped. This is a Google distribution policy,
  not an app bug.

## The three paths that DO put the app on the head unit

1. **Play Console → Internal App Sharing (fastest, no review).**
   Upload the release **AAB** (or APK) to Play Console → Internal app sharing → get a link →
   install on the phone **from that link** with the same Google account → the install counts
   as trusted → Android Auto lists the app under its launcher "Apps" section.
   Requires a Play Console developer account (one-time USD 25).
2. **Closed / internal testing track.** Create an internal track, add your Gmail as tester,
   opt in on the phone, install from Play Store → trusted → appears on AA. Also the path to a
   public release later.
3. **Desktop Head Unit (DHU) on a PC — free, today.** Install the DHU from the Android SDK
   extras, enable AA developer mode + unknown sources on the phone, run the sideloaded build
   against the DHU window to verify the live OBD dashboard (rpm, speed, gear, converter slip,
   economy) without the car.

## After a trusted install, if the icon still hides

- Force-stop the Android Auto app (it caches the discovered-app list), reconnect the USB/WiFi
  session.
- AA settings → "Customize launcher"/app list → ensure the app is enabled.
- Head-unit side: some OEM launchers need a reboot of the unit after new apps appear.

## In-app verification

Settings → "Android Auto dashboard" card runs a live `PackageManager` query for the declared
`CarAppService` intent filter and reports discovery status on-device, so the owner can
distinguish "app broken" (never) from "distribution rule" (this page).
