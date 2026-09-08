# KylaqOBD2Scan 🚗

**A deep-dive OBD-II logger, diagnostics companion and driving coach — engineered around one vehicle platform: the Škoda Kylaq 1.0 TSI (EA211) with the AQ250 (09G) 6-speed torque-converter automatic.**

[![Build Android APK](https://github.com/tritonelabautomation/KylaqOBD2Scan/actions/workflows/build-apk.yml/badge.svg)](https://github.com/tritonelabautomation/KylaqOBD2Scan/actions/workflows/build-apk.yml)
![minSdk](https://img.shields.io/badge/minSdk-24-3DDC84)
![targetSdk](https://img.shields.io/badge/targetSdk-36-3DDC84)
![tests](https://img.shields.io/badge/unit%20tests-281%20passing-brightgreen)
![suites](https://img.shields.io/badge/test%20suites-27-blue)
![license](https://img.shields.io/badge/license-all%20rights%20reserved-lightgrey)

Built in Kotlin + Jetpack Compose (Material 3, dark "cyber" theme), Room, and coroutines. Every physical claim in the app (torque curves, gear ratios, converter slip, fuel energy) is traced back to an official source or to a captured live trace from the owner's own car — see [References](#references--sources).

> **Honesty-first design.** Where the ECU does not answer a PID, the UI says *Not available* instead of inventing a number. Where Android policy blocks a feature (e.g. sideloaded Android Auto template apps), the Settings screen explains the real install path instead of pretending. Amps/current are reported as unavailable because SAE J1979 exposes no such PID.

---

## ✨ Highlights

| Area | What you get |
|---|---|
| **Live telemetry** | Sectioned dashboard (driving & 6-AT, economy, engine, fuel & injection, combustion, temperatures, air/intake, GPS), Driving HUD, Raw Monitor with per-PID live grid, Adapter Console with raw TX/RX trace log |
| **Drivetrain intelligence** | Engaged-gear estimation per mode (D / S / M-paddle) using 09G ratios + self-calibration, torque-converter slip & health monitoring, sport-mode shift-prolongation tracking, turbo/boost analysis |
| **Ride physics** | Driving-state engine (cruise / accel / brake / coast / fuel-cut), neutral-coast detection 20–130 km/h with pedals released, per-ride behaviour timeline **with elevation** from GPS |
| **Trip analytics** | Recorded trips with overview + trends, cross-trip trend analyzer (idle fuel, avg speed, economy drift), fuel-grade comparison (e.g. X95 vs regular petrol), expected-vs-actual deviation math per trip |
| **Fuel & money** | Fuel logs with MID (instrument cluster) vs OBD-recorded comparison, fuel-cost tracking, per-trip expenses, receipts parsing, reports & exports |
| **Coaching** | ~32 km daily-route per-ride coaching, AI coach chat (Gemini REST with an offline rule-based fallback), Car Doctor with DTC library & VIN decoding |
| **Fleet & care** | Vehicle garage & profiles, maintenance catalog + reminders, documents vault, PID config & scanner |
| **Android Auto** | Template dashboard (`ObdCarAppService`) for trusted installs **and** a parked "Car Dash" activity for sideload scenarios — with an in-app guide explaining exactly which Android versions can use which route |
| **Safety & backup** | Command safety validator on every TX frame, Google Drive / SAF backup & restore of trips and logs |

---

## 🎯 Target platform & scope

- **Vehicle:** Škoda Kylaq 1.0 TSI Signature+ AT (2026), EA211 engine, AQ250 (09G) 6-speed torque-converter automatic with paddles.
- **Adapter:** ELM327-family Bluetooth Classic (SPP/RFCOMM) dongles; secure → insecure → reflected-channel socket fallback for clone firmware.
- **Protocol:** ISO 15765-4 CAN (11/29-bit @ 500 k / 250 k), SAE J1979 mode 01 PIDs, mode 03/07 DTCs, mode 09 VIN.
- **Phone:** Android 7.0+ (minSdk 24), developed and field-tested on a moto edge 20 (Android 13).

Other OBD-II CAN vehicles generally work for standard J1979 PIDs; the gearbox/engine *models* are Kylaq-specific by design.

---

## 🏗 Architecture

```
app/src/main/java/com/example/
├── bluetooth/    RFCOMM transport, ELM327 parser, connection manager, EA211 simulator
├── protocol/     ISO-TP & CAN frame parsing, PID/DTC decoders, VIN authority, safety validator
├── engine/       Physics & drivetrain models (powertrain, gears, converter, coast, turbo, economy)
├── analysis/     Trip summaries, cross-trip trends, ride behaviour, coaching, insights store
├── scheduler/    Scan coordinator, OBD scheduler, quick-connect, live telemetry store
├── data/         Room database, DAOs, repositories, settings, fuel & maintenance catalogs
├── ai/           Coach chat providers: Gemini REST client + rule-based fallback
├── auto/         Android Auto car-app-library service + parked "Car Dash" activity
├── backup/       Google Drive / SAF export-import
├── discovery/    PID discovery service & decoders
├── di/           Dependency wiring
├── ui/           Compose screens, viewmodels, theme, charts & components
└── MainActivity  Navigation host (drawer + NavHost)
```

**Design rules enforced in code**

1. One transport, one owner — the phone UI opens the single RFCOMM channel; Android Auto resumes polling on the same socket (`BluetoothManager.currentTransport()`).
2. Every outgoing AT/OBD command passes `SafetyValidator`.
3. Interval attribution credits the *previous* sample's state (ride behaviour), never a zero sentinel.
4. Charts always render numeric X/Y tick scales with units and legends.
5. No paywalls, no ads, no telemetry leaving the device except the owner's own Drive backup.

---

## 🧮 Engineering models (key constants)

| Constant | Value | Source |
|---|---|---|
| Max power / torque | 85 kW / 178 Nm (1750–4000 rpm) | VW SSP 111 (EA211 1.0 TSI) |
| Torque curve pins | 1000→117.5, 1250→144.2, 1500→165.0, 1750–4000→178.0, 4500→172, 5000→160, 5500→147.5, 6000→128, 6500→105 Nm | SSP 111 + owner dyno cross-check |
| 09G gear ratios (rpm per km/h pins) | g1 = 107.848, g5 = 22.334, g6 = 17.836 (+ self-calibrated scale) | 09G workshop manual / SSP |
| Drag area | 0.72 m² | Kylaq chassis spec sheet |
| Fuel energy | 32.3 MJ/L | Petrol standard |
| Idle fuel | 0.8 L/h (model fallback when ECU fuel-rate PID absent) | Measured from owner trace |
| Converter-slip alarm | slip share > 0.25 for > 60 s | Transmission deviation model |
| Neutral coast window | 20–130 km/h, no pedals | Owner requirement |

Implementation lives in `engine/PowertrainModel.kt`, `engine/GearModel.kt`, `engine/TransmissionEngine.kt`, `analysis/TripTrendAnalyzer.kt`, `analysis/TripFuelSummary.kt`.

---

## 🧪 Verification policy

- **27 test suites / 281 unit tests** run in CI on every push (`.github/workflows/build-apk.yml`), plus an APK build job.
- **Reference trace replay:** a real captured ELM327 session from the owner's Kylaq is checked into `tools/trace_sim/reference_trace.txt`; `KylaqTraceReplayTest` and `KylaqRealWorldTraceIntegrationTest` assert the decoder, scheduler and store semantics against it.
- **Adversarial decoder tests** (`PidDecoderAdversarialTest`, `DtcDecoderAdversarialTest`) fuzz malformed frames.
- CI posts a parsed test digest back to the open pull request so red runs are triageable without Actions access.

```bash
./gradlew testDebugUnitTest -PciExcludeRobolectric=1   # unit tests
./gradlew assembleDebug                                # debug APK
python3 tools/trace_sim/replay_trace.py                # offline trace replay
```

---

## 🔨 Build from source

| Requirement | Version |
|---|---|
| JDK | 17 (Temurin) |
| Gradle | 9.3.1 (wrapper / CI-pinned) |
| Android SDK | compileSdk 36, minSdk 24, targetSdk 36 |
| Kotlin | Compose compiler plugin, KSP for Room |

```bash
git clone https://github.com/tritonelabautomation/KylaqOBD2Scan.git
cd KylaqOBD2Scan
./gradlew assembleDebug        # app/build/outputs/apk/debug/
```

---

## 🚘 Android Auto — the honest matrix

Full detail in [`docs/reference/android-auto-install-guide.md`](docs/reference/android-auto-install-guide.md).

| Route | Requires | Works on Android 13 phone? |
|---|---|---|
| Play / Internal App Sharing install of the car-app-library dashboard | Google-trusted source (policy for template apps) | ✅ certain path |
| Developer-mode "unknown sources" | Media / messaging / **parked** apps only | ✅ for the parked "Car Dash" activity only on Android 15+ head-units/phones per policy |
| Sideloaded template app | Not permitted by car-app-library verification | ❌ |

The in-app Settings screen repeats this matrix so no owner ever chases a dead end.

---

## 📚 References & sources

**Bundled with this repository (`docs/reference/`)**

1. `ssp111-1-0-tsi-ea211.md` — Volkswagen Self-Study Programme 111: the 1.0 TSI EA211 engine family (architecture, torque/power figures).
2. `09g-workshop-manual.md` — AQ250 / 09G 6-speed automatic workshop manual extract (ratios, converter, service data).
3. `ssp117-karoq-part-ii.md` — VW SSP 117: supporting EA211/09G powertrain context.
4. `kylaq-signature-plus-chassis-specs.md` — Škoda Kylaq Signature+ AT chassis & dimensions spec sheet (drag area, masses).
5. `android-auto-install-guide.md` — Android Auto install routes, OS reality matrix, Internal App Sharing steps.

**External standards & documentation**

6. SAE J1979 — *E/E Diagnostic Test Modes* (PID definitions): https://en.wikipedia.org/wiki/OBD-II_PIDs and SAE International, https://www.sae.org/standards/content/j1979_201702/
7. ISO 15765-4 — *Road vehicles — Diagnostics on CAN*: https://www.iso.org/obp/ui/en/#iso:std:iso:15765:-4
8. ELM327 datasheet & AT command set (Elm Electronics): https://www.elmelectronics.com/
9. Android Auto apps for cars (car-app-library): https://developer.android.com/training/cars/apps
10. Testing car apps on real vehicles (trusted-source policy): https://developer.android.com/training/cars/testing#real-vehicles
11. Storage Access Framework / Drive integration: https://developer.android.com/guide/topics/providers/document-provider
12. Jetpack Compose & Material 3: https://developer.android.com/compose
13. Room persistence library: https://developer.android.com/training/data-storage/room
14. Gemini API (coach chat): https://ai.google.dev/
15. Team-BHP owner thread *"Skoda Kylaq owners speak: why the 1.0 TSI feels so addictive"* — community dyno-comparison reference used to style the power/torque charts.

**Captured data**

16. Owner-captured ELM327 session trace (Kylaq 1.0 TSI, ignition ON): `tools/trace_sim/reference_trace.txt` — the behavioural reference for decoder, scheduler and store semantics.

---

## ⚖️ License & disclaimer

© 2026 the repository owner. **All rights reserved** — no open-source license is granted at this time; reference documents are cited for personal study, diagnostics and repair of the owner's own vehicle.

This project is **not affiliated with, endorsed by, or sponsored by Škoda Auto a.s., Volkswagen AG, Google LLC or Elm Electronics**. OBD-II diagnostics while driving can be dangerous: interact with this app only when parked, or let a passenger operate it. Gear/converter/fuel figures are engineering estimates from public sources and captured data — they support, never replace, professional service equipment.

---

## 🙏 Acknowledgements

- The owner's patience, real-device field testing and captured reference trace — the reason every model in this repo is checked against reality.
- Volkswagen AG's Self-Study Programmes and the 09G workshop documentation, without which the drivetrain models would be guesswork.
- The Android developer documentation team, for the car-app-library and its clearly written vehicle-testing policy.

---

*Maintained on branch `arena/01a07c37-kylaqobd2scan` · CI: "Build Android APK" · PR #1 open & mergeable.*
