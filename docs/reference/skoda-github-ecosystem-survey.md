# Skoda/VAG GitHub ecosystem survey — 7 repos the owner flagged (2026-09-13)

Every repo below was fetched and read before judging (standing rule). Verdicts are for
THIS project: Kylaq 1.0 TSI Signature+ AT 2026 (MQB-A0-IN, MST2-class headunit), owner
buys NO hardware, app stays J1979-read-only + on-phone analytics.

| # | Repo | State (fetched 2026-09-13) | Verdict for us |
|---|---|---|---|
| 1 | mattcabb/mib2std-toolbox | **Abandoned** ("Don't bother to install it. It's not ready yet", last commit 2021); explicitly lists *all Skoda infotainment units* as unsupported; SD-card PersonalPOI attack-vector docs only | **Reference only.** Real tool is the successor it points to: **olli991/mib2std-toolbox** (and olli991/mib-std2-pq-zr-toolbox). SD-card flashing of a 2026 warranty car = brick risk; NOT recommended; documented so we never suggest it casually |
| 2 | mrfixpl/mib2-backlight-menu | Alive (Dec 2025); MST2 ambient-light research on Golf MK7: exact `0x5F` adaptation names | **USEFUL — mined.** See §A: adaptation names added to the DIY guide's codemyVAG request list |
| 3 | jypma/skoda-manual | Alive; bash crawler that turns digital-manual.skoda-auto.com into offline HTML/PDF (needs browser cookie + manual ID) | **Useful tool, owner-side.** If we ever need Kylaq manual text (service intervals, MID menu names) offline, this is the sanctioned-by-nobody-but-harmless way; not an app dependency |
| 4 | NullString1/VWCDC | Alive (Mar 2026); ESP32 **CD-changer emulator** for legacy VW bus audio | **Not useful.** Hardware hack for pre-MIB headunits; Kylaq's unit has USB/BT/Android Auto natively |
| 5 | skodaconnect/skodaconnect | **ARCHIVED Mar 2025, DEPRECATED**; unofficial reverse-engineered MySkoda/Essentials Python lib; old API EOL Dec 2024 | **Evidence, not code.** Confirms yesterday's verdict: the old unofficial cloud route is DEAD; the official Škoda Public API (CarConnectivity connector) is the only viable Cloud Link path |
| 6 | skodaconnect/homeassistant-skodaconnect | **ARCHIVED Mar 2025**; HA plugin for the same dead old API ("So long, and thanks for all the fish") | Same as #5 — evidence; its successor pointer is the MySkoda-based HA integration |
| 7 | jilleb/mqb-pm | **ARCHIVED Mar 2022**; Android "Performance Monitor for VAG cars with Android Auto": in-car **exlap** channel + Torque OBD2 on the MIB2 screen, OEM themes | **Closest sibling app — inspiration + validation.** Proves the exact product shape we ship (OBD + Android Auto dash on MIB2). Future research seed: VAG *exlap* extended-performance channel (ECU-side power/torque) — availability on Kylaq 1.0 TSI unverified; our model-based curves remain authoritative until then |

## §A Mined from mrfixpl/mib2-backlight-menu (MST2 ambient/backlight menu)

VAG coding (0x5F infotainment → adaptations), Golf-MK7-MST2-derived, Gen2 channel names —
candidate lines for the owner's ₹0 codemyVAG / forum-dev coding request (alongside the
existing MID `17 → default_color` ask in `docs/reference/mid-red-theme-diy-guide.md`):

- `0x5F` → adaptation → `Car_Function_Adaptations_Gen2`:
  - `menu_display_ambient_illumination` → `active`
  - `menu_display_ambient_illumination_over_threshold_high` → `active`
  - `Interieur light 0x08` → `activated`
  - `Interieur light 0x08 msg bus` → `comfort data bus`
- Caveat recorded by the author: variant long-coding bytes 0-2 may need the facelift
  variant for the background-light submenu to appear (his 2016 Golf). Kylaq applicability
  UNVERIFIED — treat as a question to the coder, not an instruction.
- BCM (`0x09`) side and `.gcc`/`.mcf` zone/GUI mods are TODO even upstream → do not promise them.

## §B What this survey changes in the app

**Nothing today (additive-only rule holds).** Concrete follow-ups parked with owners:
1. Cloud Link batch remains gated on the owner's MyŠkoda public-API key test
   (`docs/reference/skoda-public-api-research.md` §4) — repos #5/#6 prove there is no
   unofficial fallback worth building against.
2. Ambient-light adaptation names (§A) appended to the DIY guide's no-device request list.
3. exlap channel = parked research topic for ECU-side power/torque cross-check (repo #7);
   no code until a Kylaq-positive source is fetched.
