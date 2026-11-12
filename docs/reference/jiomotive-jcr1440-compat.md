# JioMotive JCR 1440 (B) — Compatibility Assessment for KylaqOBD2Scan

**Date:** 2026-09-09 · Owner now possesses a JioMotive "Vehicle Tracker-Router OBD II JCR 1440 (B)".

## 1. What the device actually is (evidence)

Hardware teardown (Sushil Chaudhari, Automotive & IoT Security Researcher,
[medium.com/@sushilchaudhari2303/jio-motive-f60e85db9e8f](https://medium.com/@sushilchaudhari2303/jio-motive-f60e85db9e8f), Oct 2025)
+ official spec sheet (Jio/Amazon QSG PDF):

| Component | Part | Role |
|---|---|---|
| LTE modem | Qualcomm **MDM9207** (+ PMD9607 PMIC, WTR2965 RF) | 4G Cat-4 uplink to Jio cloud (150/50 Mbps) |
| MCU | NXP **MIMXRT1051CVL5B** (i.MX RT1050 family, 512 KB RAM + 1 MB SPI flash) | CAN/GPS/sensor processing |
| Wi-Fi/BT | Realtek **RTL8723DS** | Wi-Fi hotspot (802.11 b/g/n, 6-8 users) + **BT 4.0 (BLE)** for JioThings-app pairing |
| CAN | TJA1042-class high-speed transceiver | **OBD protocols: CAN ISO 15765-4 only (11/29-bit, 250/500k)** |
| Sensors | GPS patch antenna, 3D accelerometer + gyroscope | Location, harsh-driving events, anti-tow |
| Power | 12/24 V, 5 F supercap backup | Ride-through on battery disconnect (theft detection) |
| Ports | Micro-USB (**debug/service only**), reset pin | Not user data ports |

It is a **cloud telematics terminal**, not a scan tool: it reads standard OBD data
itself, streams it over its own eSIM (JioEverywhereConnect plan sharing — device is
**network-locked to Jio**; porting your number bricks it) to the **JioThings app**
via Jio's servers.

## 2. Can KylaqOBD2Scan use it as an adapter? — **No, and here is the honest why**

1. **No ELM327 command interface.** Our app speaks the ELM327 AT/hex protocol over
   Bluetooth SPP (classic) or BLE-serial. The JCR 1440 exposes **no AT interpreter**:
   its BT 4.0 is a proprietary provisioning channel for the JioThings app, and all
   vehicle data flows LTE → Jio cloud → JioThings app. There is no documented local
   GATT service, no serial profile, no public API.
2. **Ecosystem + network lock.** Activation requires a Jio number and the JioThings
   app; the device is ARAI-certified and **locked for Jio network** (Reliance Digital
   listing). Reverse-engineering the RTL8723DS BLE protocol would violate Jio's ToS,
   be fragile across firmware updates, and still not give real-time PID streaming at
   our app's 1-4 Hz poll rates (the device is a low-rate telematics logger by design).
3. **Different purpose.** JioThings gives location, geofencing, anti-theft, SOS,
   driving-behaviour summaries and a Wi-Fi hotspot. KylaqOBD2Scan gives live 1.0 TSI
   PID telemetry, turbo/boost tracking, power/torque curves, neutral-coast analysis,
   fuel logs, per-ride coaching, Android Auto HUD. Zero of our features can be fed
   from Jio's cloud (no public data export).

## 3. How to run BOTH on the Kylaq (recommended setup)

- The Kylaq has **one OBD-II port**; two devices need a cheap **OBD-II Y-splitter
  cable** (₹200-500). CAN is a parallel bus — both nodes listen fine on the same
  ISO 15765 500k segment. Keep total dongle draw in mind (both are bus-powered;
  a quality splitter is sufficient, avoid 3+ way hubs).
- Division of labour: **JioMotive** = tracker/hotspot/SOS/insurance-style telematics;
  **our ELM327 + KylaqOBD2Scan** = real-time telemetry, logging, coaching, HUD.
- Bonus synergy: JioMotive's **Wi-Fi hotspot** can carry our app's Google Drive
  backup and AI-coach traffic on drives where the phone's mobile data is weak.

## 4. Does it change the VAG coding verdict (Batch 23)? — **No**

The JCR 1440 reads **standard OBD (ISO 15765-4) only** and sends it to Jio's cloud.
It has no UDS extended-session (`0x10`), security-access (`0x27`) or write (`0x2E`)
capability exposed to users, and no SFD2 token channel. The MID-red-theme /
sport-menu / infotainment-colour-theme conclusions in
`docs/reference/vag-coding-research.md` stand unchanged.

## 5. References

- Teardown: medium.com/@sushilchaudhari2303/jio-motive-f60e85db9e8f (chip-level, Oct 2025)
- Official QSG/spec PDF (JCR 1440 hardware spec, chipset, OBD protocols, JioThings app flow): m.media-amazon.com/images/I/B1Nob3PHgkL.pdf
- manuals.plus JioMotive JCR 1440 FAQ (setup, Jio-number lock, plan sharing, interstate roaming)
- reliancedigital.in product page ("locked for Jio network", ARAI certified, ₹5,799)
