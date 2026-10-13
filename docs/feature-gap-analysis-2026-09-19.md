# Feature gap analysis — "what are we missing, check github" (2026-09-19)

Owner: *"What are features are we missing here check github."* Method: our own GitHub surface
(issues/projects/README), then the open-source OBD-II app ecosystem on GitHub (feature lists of
`fr3ts0n/AndOBD`, `tzebrowski/ObdGraphs` 60★, `Wal33D/OBD-Droid`, topic searches for
`obd2+android`), each candidate **verified against this codebase by grep** before being called
missing. Stars in this niche are low; the reference value is their feature lists, not their size.

## Own GitHub surface

- **No open issues, no projects.** Nothing was tracked as "missing" anywhere — this document is
  now the tracker until features become issues.
- **README was lying about tests**: badges said 290 tests / 29 suites; reality at this commit is
  770 / 93. Fixed in this change; keep it synced with the killproof doc census line.

## Verified MISSING (grep-proven absent)

| Feature | Who has it | Value for this owner | Build size |
|---|---|---|---|
| **I/M readiness monitors UI (0141/0101)** | OBD-Droid, AndOBD, every scan tool | **HIGH — PUC / inspection prep** | small — **BUILT THIS CHANGE** |
| Home-screen widget (fuel, odo, since-refuel km/L at a glance) | AndOBD, Torque-class apps | medium-high | medium (Glance/RemoteViews) |
| Live metric threshold alerts (over-temp, voltage, boost, low fuel) as notifications | ObdGraphs | high (safety while driving) | medium (liveNumericMap + keep-alive notification) |
| Wi-Fi / TCP / USB adapter transports, STNxxxx chips | ObdGraphs, AndOBD | low now (he owns BT Classic) | medium |
| BLE GATT transport (ELM327 BLE dongles) | ObdGraphs | low now | medium |
| Route map / GPX-KML export of the GPS trace | OBD-Droid, ObdGraphs | medium (trips already log GPS altitude + fixes) | medium-large (osmdroid) |
| Performance metering (0-100, in-gear pulls) | Torque-class | low (not his use) | small-medium |
| Wear OS companion | niche | low | large |
| PDF share export of vehicle history | OBD-Droid | low-medium | small |
| Recall / VIN-history lookup (NHTSA) | OBD-Droid | ~zero (US database, India-market car) | skip |
| Localisation beyond English | most | low (owner uses EN) | ongoing |
| Desktop/web log viewer companion | ObdGraphs | low (CSV + Trends already deep) | large |

## Deliberate absences (policy, documented in code — do NOT "fix")

- **Mode 04 Clear DTCs / Mode 08 actuator tests**: never executed; SafetyValidator blocks at the
  transport layer (EcuDiscoveryManager comment). Read-only is the product's stance.
- **Android Auto sideload**: template apps need a trusted install; the app explains the real
  install path instead of pretending (README honesty-first paragraph).
- **Amps/current PIDs**: SAE J1979 exposes none; UI says Not available.

## Already ahead of the reference set (for balance)

Kill-proof journal + automatic killed-session recovery; refuel detection with pump-receipt
calibration loop and brim-to-brim ledger; trend forensics with per-claim confidence; gear and
torque-converter intelligence per drive mode; IST-everywhere records; AI coach with offline
fallback; Drive mirror + SAF backup; restart-refuel receipt popup; observed-cadence honesty line.

## Recommendation order for next builds

1. **Live threshold alerts** (safety; reuses liveNumericMap + the keep-alive notification).
2. **Home-screen widget** (glance value; Glance composables, no new permissions).
3. **GPX export first, map view second** (GPS fixes already stored; export is cheap, map is not).
