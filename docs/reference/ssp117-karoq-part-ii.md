# VAG SSP 117 — Škoda Karoq Vehicle Presentation Part II, distilled

Source (direct PDF, verified accessible 2026-09-08):
`https://procarmanuals.com/wp-content/uploads/pdfs/ssp/ssp-117-skoda-karoq-vehicle-presentation-part-ii.pdf`
Landing page: `https://procarmanuals.com/vag-ssp-117-skoda-karoq-vehicle-presentation-part-ii/`
(lazy-loaded viewer; direct `wp-content/uploads/pdfs/ssp/` path found via Wayback snapshot).
Closing date 9/2017. Parse limit: pages 1–30 (engines + gearbox matrix); fuel system, MIB
infotainment, antennas, ambient lighting, BCM, wiring and data-bus chapters are beyond the cap.

## Why this document matters for the Kylaq app
The Karoq is MQB (Europe) — **its 1.0 TSI is the EA211 CHZD (200 Nm tune) and it pairs with
DSG, not AQ250**. The Kylaq (MQB-A0-IN) 1.0 TSI is the newer **evo** variant, 85 kW / **178 Nm
@ 1750–4000**, paired with the **AQ250-6F (09G) torque-converter AT**. So SSP 117 is used here
for: (a) EA211 1.0 TSI three-cylinder architecture education (nearly identical hardware family),
(b) explicit contrast data so the app's learning guide doesn't confuse the two tunes, and
(c) gearbox-family context (DQ200/DQ381 vs AQ250).

## 1.0 TSI 85 kW EA211 (engine code CHZD, Karoq tune)
| Parameter | Value |
|---|---|
| Design | Inline 3-cyl, 2×OHC, 12 valves, turbocharged, DI, front transverse |
| Displacement | 999 cm³ (bore 74.5 mm, stroke 76.4 mm) |
| Max power | 85 kW (115 HP) @ 5000–5500 rpm |
| Max torque | **200 Nm @ 2000–3500 rpm** (Kylaq evo tune: 178 Nm @ 1750–4000) |
| Compression | 10.5 : 1 |
| Weight | 94 kg DIN 70020 (incl. MQ200 flywheel) — **no balance shaft** |
| Fuel | Unleaded, **min RON 95** |
| Emissions | EU6 |

### Hardware highlights (vs older MPI/1.2 TSI)
- **Con rod small eye without bush** — pre-machined, fine-bored, roller-burnished; resists
  ovalisation; requires **DLC-coated piston pin** (~2.5 µm a-C:H over CrC/CrN/Cr base,
  >80 HRA / >635 HV10, Rz 0.6).
- **High-pressure injection raised 200 → 250 bar**; HP pump with pressure-control valve, rail,
  pressure sensor, 3 injection valves; LP side: tank pump + pump control unit.
- **Continuously controllable vane oil pump** on the crankshaft: variable eccentricity,
  1–4 bar map-controlled (sensor in head + PWM solenoid); failsafe = full delivery without
  signal; safety ball valve ~7 bar for cold starts.
- **Tri-oval timing gear pulleys** on intake & exhaust cam adjusters, phased to crank —
  reduces valvetrain dynamics loads.
- **Turbocharger: 1.6 bar relative boost**, thermal resistance up to 1050 °C, electrically
  controlled bypass (wastegate) valve; **intercooler integrated in the intake manifold**
  (charge-air/water).
- **Exhaust manifold integrated into the aluminium cylinder head** → lower pre-turbo EGT,
  less enrichment, faster warm-up.
- **Sodium-cooled exhaust valves** (60% Na fill, melts 97.5 °C; stem ø3 mm bore, 1 mm wall;
  3 g lighter than conventional) — prevents stem burn-off at high EGT.
- **Dual VVT** — independent intake and exhaust camshaft phasing.
- **Balancing without a shaft**: increased flywheel + torsional-damper weights rotate the
  first-order inertia-force product to horizontal → less idle vibration transfer (3-cyl).
- Separate cylinder-head and block cooling circuits.

## 1.5 TSI 110 kW ACT EA211 (DADA) — context only (not in Kylaq)
4-cyl 1498 cm³ (74.5×85.9), 110 kW @ 5000–6000, 250 Nm @ 1500–3500, CR 10.5:1, 115 kg;
**ACT** deactivates the two middle cylinders at low load; **APS** (atmospheric plasma spray)
cylinder-wall coating; **350 bar** injection; external air-to-air intercooler; two-stage
crankcase ventilation (coarse + fine oil separator, N80 canister); **5-mode electronically
controlled water pump with two ball valves** (mode 1 = no flow for fast warm-up … mode 5 =
max cooling) — the same thermo-management philosophy as the Kylaq's evo engine.

## Gearbox matrix (Karoq)
| Manual | Engines |
|---|---|
| MQ200-6F | 1.0 TSI 85 kW |
| MQ250-6F | 1.5 TSI, 1.6 TDI |
| MQ350-6F/A | 2.0 TDI 110 kW |

| Automatic (DSG) | Engines |
|---|---|
| DQ200-7F (dry clutch) | 1.0 TSI, 1.5 TSI, 1.6 TDI |
| DQ381-7A (wet clutch) | 1.5 TSI, 2.0 TDI 110/140 kW |

Suffix code: `-6F/-7F` = front-wheel drive, `-A` = connectable rear axle (4×4).
DQ381-7A improvements over DQ250-6F are detailed p.34–35 (beyond parse cap).
**No AQ250/09G in the Karoq** — the torque-converter 6AT is the India/MQB-A0 pairing
(Kylaq, Slavia, Virtus/Kushaq lineage), covered by the 09G workshop manual + SSP 291.

## Diesel overview (context only)
1.6 TDI 85 kW DDYA (250 Nm @1500–3200, CR 16.2), 2.0 TDI 110 kW DFGA (340 Nm @1750–3000),
2.0 TDI 140 kW DFHA (400 Nm @1900–3300, two balance shafts, CR 15.5) — EA288, common rail
2000 bar, VTG turbo, belt interval 210 000 km (1.6 TDI).

## App takeaways already applied
- Learning-guide/Insights copy: EA211 1.0 TSI hardware facts (DLC pins, sodium valves,
  250 bar DI, 1–4 bar variable oil pressure, integrated manifold/intercooler, 1.6 bar turbo,
  tri-oval pulleys, flywheel-based balancing).
- Explicit CHZD-vs-Kylaq tune contrast (200 Nm vs 178 Nm) so torque math keeps using the
  Kylaq's 178 Nm @ 1750–4000 (PowertrainModel pin).
- Min RON 95 confirmed → supports the app's X95-vs-normal-petrol tracking guidance.
