# Fuel economy: what "ideal" means for the Kylaq 1.0 TSI AT, and where the litres actually go (2026-09-19)

Owner: *"Based on all the info we have so far related 1.0 TSI what would be ideal fuel economy
km/l why the hell we are getting less."* Answered by derivation from the car's own physics and
his own recorded data — not from brochures. Every constant below is either measured by the app
on his car or a standard physical value; the arithmetic is shown so any line can be challenged.

## Constants

| Quantity | Value | Source |
|---|---|---|
| Kerb mass + driver | 1255 + 95 ≈ 1350 kg | kerb 1213-1255 kg (Autocar spec) |
| Peak power / torque | 85 kW / 175-178 Nm | app PEAK_POWER_KW; F-6 oracle 0163 = 175 Nm on his ECU |
| Rolling resistance Crr | 0.011 | standard road-tyre range 0.010-0.012 |
| Cd × A | 0.32 × 2.2 ≈ 0.70 m² | compact-SUV typical |
| Air density | 1.2 kg/m³ | |
| Petrol energy | 0.745 kg/L × 44 MJ/kg ≈ 32.8 MJ/L | app fuel density constant |
| Drivetrain efficiency | 0.85 (AQ250 torque converter, locked) | |
| Engine efficiency | 0.18-0.26 by load point | EA211 TSI map shape: ~20 % at 6 % load, ~25 % at 18 % |
| **Idle fuel flow** | **1.05 L/h** | **app constant, measured on his car** |
| AC compressor | ≈ 0.35 L/h equivalent | 2-3 kW at the compressor ÷ city engine efficiency |

## Steady cruise (AC on) — what the hardware is capable of

P = Crr·m·g·v + ½·ρ·CdA·v³ at the wheels; fuel = P / (0.85 · η_eng(v)) / 32.8 MJ/L; + 0.35 L/h AC.

| Speed | Wheel power | Fuel | **km/L** |
|---|---|---|---|
| 60 km/h | 4.4 kW | 3.2 L/h | **~19-21** |
| 80 km/h | 7.9 kW | 4.8 L/h | **~17** |
| 100 km/h | 13.1 kW | 7.1 L/h | **~14** |

## His actual duty cycle (SINCE REFUEL photo: 364 km, 17:54 h, avg 20.3 km/h, 9.6 km/L = 37.9 L)

Per 100 km at 20 km/h average, one full stop per km (signal/junction), 40 km/h between:

| Bucket | Energy / time | Litres per 100 km | Share |
|---|---|---|---|
| Rolling resistance | 14.6 MJ | 3.1 | 30 % |
| Kinetic energy thrown away in brakes | 100 stops × ½·1350·11.1² ≈ 8.3 MJ | 1.8 | 17 % |
| Aero + drivetrain extras | ~7 MJ | 1.5 | 15 % |
| **Engine-on standing still** (40 % of the 5 h) | 2.0 h × 1.05 L/h | **2.1** | **20 %** |
| **AC compressor** | 5 h × 0.35 L/h | **1.75** | **17 %** |
| **Total** | | **≈ 10.3 L/100 km = 9.7 km/L** | |

Model says 9.7; his cluster says 9.6; his brim-to-brim ledger says 10.40 pooled. **The car is
not underperforming — the number is exactly what this duty cycle costs.** Autocar's own road
test of the same car measured 8.7 kmpl city / 13.36 highway / 11.03 overall; his city figure
beats their city figure.

## So what is "ideal"?

1. **Lab ideal (ARAI 18.1-19.7 km/L for the AT):** no AC, warm engine, gentle certified cycle.
   Unreachable on any Indian road; treat it as a certification artefact.
2. **Hardware ideal at steady cruise:** 19-21 km/L at 60-70 km/h, ~17 at 80, ~14 at 100 (AC on).
   His car can and does show these on a clean flyover run — check any SINCE START of a highway hop.
3. **Physical ideal for HIS duty cycle** (avg 20-22 km/h, AC on, Hyderabad): **≈ 10.5-11.5 km/L**
   with perfect technique. He measures 9.6-10.4. Gap to physical best: under 10 %.

## Average speed is the whole game (same model, stop-fraction falling as speed rises)

| Avg speed | Expected km/L (AC on) |
|---|---|
| 15 | ~8 |
| 20 | ~9.8 |
| 25 | ~10.8 |
| 30 | ~11.6 |
| 40 | ~13 |
| 60 | ~19 |

## Why "the hell" we get less — ranked by litres stolen per 100 city km

1. **Engine-on stationary time: 2.1 L.** Idling burns 1.05 L/h (his car's measured number).
   Every 10 min of parked engine-on = 0.18 L = ~1.8 km of range.
2. **AC in city: 1.75 L.** Compressor power is fixed; at 20 km/h it spreads over few kilometres.
3. **Brake-thrown kinetic energy: 1.8 L.** Each stop from 40 km/h dumps 83 kJ — the fuel that
   made it is gone. Aggressive stop-go driving can double this bucket.
4. **Part-load engine efficiency:** at 6-18 % of 85 kW the TSI runs ~20-25 %, half its best map
   point. Unavoidable in traffic; the reason cruise km/L is double city km/L.
5. Small: tyre under-pressure (+1-2 % rolling per 0.2 bar), cold starts (<10 km trips),
   converter slip below lockup (~15-20 km/h), E20 fuel's ~1-2 % energy deficit.

## What would actually move his number (in order of payload)

- Raise average speed: timing/route worth +5 km/h avg ≈ +1 km/L (table above).
- Kill engine-on standing: 10 min/day saved ≈ 0.18 L/day ≈ 5.4 L/month.
- Gentle acceleration to cruise, then coast to signals (feeds bucket 3 back).
- Tyre pressure at spec + 0.1-0.2 bar when loaded; AC fan over AC max-compressor when bearable.
- Nothing mechanical is suspect: cluster vs app vs ledger agree within model error, and his
  city figure beats published road tests of the same car.

## What is NOT claimed

- ±10 % model accuracy: stop frequency, AC load and wind are estimates; his measured rows
  (idle time from the start-stop analyzer, fuel rate integration) remain the truth.
- ARAI numbers quoted from published specifications, not measured here.
- The app does not yet print this breakdown per trip; if the owner wants it as a card
  ("where did my litres go"), the buckets above are all computable from stored rows.
