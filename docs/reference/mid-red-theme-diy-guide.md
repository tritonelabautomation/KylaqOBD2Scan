# DIY Guide — RED MID Theme (+ bonus red infotainment) on Skoda Kylaq Signature+

Owner request 2026-09-13: "Man I want to do it DIY please".
Evidence base: `docs/reference/vag-coding-research.md` §3.1b/§3.1c. Tool: **OBDeleven** (VW-Group-licensed,
holds the SFD auto-unlock no generic app can legally have). KylaqOBD2Scan stays J1979 read-only by design —
this procedure happens entirely outside our app.

**Target car:** Skoda Kylaq Signature Plus AT, 2026 build, MQB-A0-IN, digital MID (8"), 10" infotainment.
**Goal:** module 17 (instruments) adaptation `default_color` → red RGB. Fallback/bonus: module 5F
`Skinning` → red infotainment theme (platform-proven; codemyVAG sells this exact feature for Kylaq Sig+).

---

## Phase 0 — Buy the right thing (₹0 wasted)

| Option | What | Price | Notes |
|---|---|---|---|
| **A (recommended, India-fast)** | Amazon.in → search **"OBDeleven NextGen Pro Pack"** | ₹15,999 (2% coupon → ₹15,679; dips to ~₹13-14k on sale) | Includes device + 12-month **PRO** plan. Prime delivery, no customs. |
| **B (latest device, official)** | [obdeleven.com PRO Pack + 100 Credits](https://obdeleven.com/products/pro-pack-100-credits) | €139.99 (~₹13.5k + shipping/duty) | **OBDeleven 3** device + 12-mo PRO + 100 OCA credits. 14-day money-back. International shipping → customs possible. |

❌ Do NOT buy: the €114.99 basic pack or any "Starter" tier — **no coding/adaptations, no SFD unlock**.
✅ PRO plan is the minimum tier that unlocks: Coding, **Adaptations**, **SFD auto-unlock (VAG)**, Vehicle backup.
Both devices work with the same Android/iOS app; OBDeleven 3 is simply the newer hardware.

## Phase 1 — Setup (10 min, at home)

1. Install **OBDeleven** app (Play Store) on the moto edge 20.
2. Create account with the email you'll use for the plan; activate the PRO plan code that ships with the pack
   (app → Profile → Subscriptions → activate).
3. When the device arrives: pair via the app (Bluetooth). Firmware-update the device if the app offers it.

## Phase 2 — Car prep (5 min)

1. Park outdoors/ventilated, handbrake on, all accessories off (dashcam, chargers).
2. **Unplug every other OBD device** — our ELM327 dongle AND the JioMotive (if on a splitter). One bus master
   at a time. Also force-stop KylaqOBD2Scan so its BT auto-connect doesn't fight the session (or toggle phone BT off between pairing steps).
3. Battery check: engine OFF but battery healthy (recent drive is ideal). Coding on a weak battery = bricked-module risk.
4. Plug OBDeleven into the OBD port (under-dash, driver side).
5. **Ignition ON, engine OFF** (press start button without brake, or key position 2).

## Phase 3 — FULL BACKUP FIRST (non-negotiable)

App → connect to car → **Vehicle backup** (PRO feature) → run a complete backup of all control units.
This is your undo button for everything that follows. Confirm it finished before touching any adaptation.

## Phase 4 — The MID red write (10 min)

1. App → **Control units** → find **17 — Instruments / Dashboard** (name may read "Switch panel for instrument cluster" or "Dashboard").
2. Tap it → **Adaptation** (not Long coding — we do NOT touch coding bytes today).
3. In the channel search box try, in order: `default_color` → `color` → `colour` → `rgb`.
4. **Channel found?**
   a. **Screenshot the stock value** (and write it on paper). Kushaq/Taigun stock is a blue-ish or amber triple; yours may differ.
   b. Set the RGB triple to one of:
      - **`{250, 0, 0}` — GT Red** (the value proven on Taigun/Virtus, thread 18748) ← recommended first try
      - `{230, 0, 0}` — Monte Carlo Red (`#E60000`, codemyVAG's table)
      - `{255, 0, 0}` — pure red (if the above look off)
   c. Confirm/write. If an **SFD prompt** appears → allow it (PRO plan pulls the token from VW servers automatically — this is the licensed path).
   d. Ignition OFF → lock the car → wait 2 min → unlock, ignition ON. Check the MID.
5. **Result checks:** gauge needles/backlit theme should shift to red tones. Some clusters apply instantly, some after the sleep cycle above.

## Phase 5 — Branch table (when it doesn't go to plan)

| Symptom | Meaning | Action |
|---|---|---|
| No `default_color`/colour channel anywhere in module 17 adaptation | Kylaq cluster firmware doesn't expose it (codemyVAG's "NOT available" was literal) | Screenshot the FULL adaptation list of module 17 → post it in OBDeleven forum thread 38477 asking devs to confirm from their Kylaq backup. Definitive answer, ₹0. |
| Write refused with SFD/security error (NRC 0x33/0x7F, "function unavailable", "SFD2") | 2026-build SFD2 gate — OBDeleven's own FAQ warns 2024+ adaptations "may be restricted" while they finish SFD2 rollout | 1) Update app+device firmware, retry (their SFD2 support ships progressively). 2) Ask OBDeleven support in-app (Help) for Kylaq-cluster SFD2 status. 3) Meanwhile take the infotainment red (Phase 6) if module 5F is unlocked, and/or go the codemyVAG remote-session route with your screenshots. |
| Write accepted but MID looks unchanged | Cluster caches theme until sleep | Lock car, wait 4-5 min (same reboot window as the infotainment), unlock. Still nothing → the channel exists but is cosmetic-dead on this firmware → restore stock value, record result. |
| Anything feels wrong (warning lights, cluster flicker) | Stop | Ignition off, wait 5 min, restart car. Restore backup if symptoms persist. This is why Phase 3 exists. |

## Phase 6 — Bonus, same session: RED infotainment theme (platform-proven, vendor-sold for Kylaq)

Module 5F recipe verified on Taigun/Virtus/Kushaq/Slavia (thread 18748) and codemyVAG sells exactly this
for Kylaq Sig+:

1. Control units → **5F — Multimedia / Information Electronics** → **Adaptation**.
2. Search `Skinning` (may appear under "Function configuration hmi").
3. Values: `0` = Blue (stock), `2` = **Red** (GT stock), `3` = Orange. Screenshot stock (likely 0) → write `2`.
4. Handle the SFD prompt as before. Exit, lock the car, **wait ~4 minutes** for the infotainment reboot, unlock.
5. Result: red accent theme in Settings → Display on the 10" screen.

Sport menu (Performance Displays): Kushaq/Slavia have the "Enabled Performance Displays on Infotainment" OCA;
Kylaq OCAs don't include it yet — request it in thread 38477 rather than guessing channels.

## Phase 7 — Rollback (always available)

- MID: rewrite the stock RGB triple you screenshotted in Phase 4a.
- Infotainment: rewrite stock `Skinning` value (Phase 6 step 3).
- Nuclear option: restore the Phase 3 full backup via the app.

## Golden rules

1. Backup before writes. Always.
2. **Adaptation channels only** — do NOT touch Long coding bytes or any other module in this session.
3. One value at a time; screenshot before and after.
4. Healthy battery, ignition ON/engine OFF, no other OBD devices on the bus.
5. Any SFD prompt = allow (that's the licensed unlock doing its job). Any *unexpected* error = stop, restore, report.
6. Record the outcome (success photos or error screenshots) — it settles the Kylaq question for the community
   (post in thread 38477) and for `vag-coding-research.md`.

## Warranty note (honest)

Adaptation writes are logged (SFD exists precisely to record who changed what). A cluster/infotainment
warranty claim could see the modification. Rolling back restores the stock value but not necessarily the
history. Risk is small for cosmetic adaptations — your call, made with eyes open.
