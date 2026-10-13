# Kylaq 1.0 TSI AT — ideal fuel economy analysis (E20 / XP95 / driving bands)

Owner request 2026-09-14: "based on all available info of kylaq signature + AT weight and
every details you already have engine & torque converter details … what is ideal fuel
economy one can expect with E20 & xp95 etc — such analysis required."

Method: first-principles road-load + BSFC model, calibrated where the owner's own telemetry
is the oracle (idle burn, AC load). Every assumption is listed; anything not measured is
labelled ASSUMED with a sensitivity note.

## 1. Vehicle signature (verified)

| Parameter | Value | Source |
|---|---|---|
| Engine | 999 cc 3-cyl TSI, 115 PS @ 5000-5500, 178 Nm @ 1750-4000 | autocarindia / cardekho specs |
| Gearbox | 6-speed torque-converter AT (lock-up clutch above ~45 km/h) | autocarindia ("Torque Converter, 6 gears") |
| Kerb weight (AT) | 1213-1255 kg → model uses 1255 + 75 driver = **1330 kg** | cardekho/autocar |
| ARAI (AT) | **19.05 km/L** (MT 19.68) | cardekho/autocar |
| Tyres | 205/55 R17 tubeless | autocar |
| Fuel tank | **45 L** (owner's Car Scanner entry of 50 L is the Kushaq figure — correct it) | autocar JSON spec |
| Cd·A | 0.75 m² (ASSUMED: Cd≈0.34 × A≈2.2 m²; ±10 % moves 100 km/h result ∓4 %) | not published |
| Crr | 0.011 (touring 205/55 R17 at 33-35 psi) | industry typical |
| Air density | 1.15 kg/m³ (Hyderabad ~30-35 °C) | physics |
| Idle burn | **1.05 L/h** — owner F-6 telemetry median, not a guess | app calibration (PowertrainModel) |
| AC load | 1.1-1.9 kW compressor model (7VL variable + EM clutch) | app AcClimateModel |

## 2. Fuel energy (the E20/XP95 question)

| Fuel | Mass LHV (MJ/kg) | Density (kg/L) | Energy (MJ/L) |
|---|---|---|---|
| Petrol (E0) map basis | 44.0 | 0.735 | 32.3 (app constant) |
| E10 | 42.28 | 0.742 | 31.37 |
| **E20** | 40.56 | 0.750 | **30.42 (−3.0 % vs E10)** |

Modelled km/L penalty of E20 vs E10 at equal engine work: **−3.0 to −3.3 %**
(80 km/h: 21.4 → 20.7 km/L). Sits inside ARAI's stated 1-6 % E20 drop band and the
2-6 % field reports for E20-ready cars; pre-E20 calibrations lose more (Autocar:
BS4 Polo GT TSI −5.2 %). The Kylaq is E20-homologated, so expect the low end of
that band, i.e. **≈ −3 %**.

**XP95 / premium:** Indian regular petrol is now E20 with effective RON ≈ 95, so a
RON-95 premium grade offers **no octane headroom** on this 10.5:1 TC engine — the knock
sensor has nothing extra to advance. If a state's XP95 is an E10 blend it carries +3 %
energy/L, but at the typical +₹4-6/L premium the ₹/km still loses:
- regular E20 @ ₹115/L, 20.7 km/L → ₹5.56/km
- XP95 (E10) @ ₹120/L, 21.4 km/L → ₹5.61/km
- XP95 (E20) @ ₹120/L, 20.7 km/L → ₹5.80/km
Verdict: **regular E20 is the economic fuel**; XP95 only as an occasional detergent
top-up, never as a mileage purchase. (Community consensus matches: r/carIndia —
"no benefit in always paying for premium now since the octane rating is same".)

## 3. Road-load results (E20, AC off, lock-up, no wind, flat)

| Speed | Engine power | Burn | **km/L** |
|---|---|---|---|
| 50 km/h | 3.4 kW | 1.53 L/h | 32.6 |
| 65 km/h | 5.6 kW | 2.50 L/h | 26.0 |
| 80 km/h | 8.6 kW | 3.86 L/h | **20.7** |
| 90 km/h | 11.2 kW | 5.03 L/h | 17.9 |
| 100 km/h | 14.4 kW | 6.45 L/h | **15.5** |
| 120 km/h | 22.6 kW | 9.30 L/h | **12.9** |
| 80 + AC (1.9 kW) | 10.7 kW | 4.78 L/h | 16.7 |
| 100 + AC | 16.5 kW | 6.80 L/h | 14.8 |

BSFC shape assumed (g/kWh, petrol map, scaled by blend mass-LHV): <15 kW 310,
<25 kW 285, <40 kW 265, else 250 — typical EA211 part-load island.

## 4. City & mixed (torque-converter reality)

City model: stop-go energy (½mv² per stop × stops/km × 1.25 TC launch enrichment),
rolling+aero over distance, converter η 0.80-0.85 below lock-up, plus **measured**
idle burn during stopped time.

| Cycle | Parameters | **km/L** |
|---|---|---|
| City moderate | 25 km/h avg, 1.5 stops/km, 35 % idle | 14.6 |
| City heavy (Hyderabad peak) | 20 km/h avg, 2.5 stops/km, 45 % idle | 10.6 |
| Mixed 40/60 (city/80 cruise) | moderate city | 17.7 |
| Mixed 40/60 | heavy city | 15.0 |
| TC slip penalty @40 km/h | η 0.85 vs lock-up 0.92 | 34.7 vs 37.5 (−7.5 %) |

The Kylaq AT has **no auto start-stop**, so every stopped second costs the measured
1.05 L/h — in heavy city that is ~2.4 L/100 km, a fifth of city burn. Anticipation
(fewer full stops, longer coast-in — the app's 20-130 km/h coast coaching) is the
single biggest city lever after route choice.

## 5. The answer, in one table (E20, AC off unless noted)

| Condition | Expect (km/L) |
|---|---|
| **Physical ideal** (65-80 km/h steady, flat, no AC) | **21-26** |
| Best realistic highway (80-90, light AC) | 17-21 |
| 100 km/h cruise | 14.8-15.5 (16.8-17.9 if E10-era fuel existed) |
| 120 km/h cruise | ~12.9 |
| Hyderabad city | **11-14.5** |
| Mixed daily | **15-17.5** |
| ARAI claim (AT) | 19.05 — sits between ideal cruise and mixed, as it should |
| Range on 45 L at mixed 16.5 | ~740 km |

Sanity cross-checks: ARAI 19.05 brackets correctly; owner idle 1.05 L/h ⇒ heavy-city
idle share ~22-24 % of burn (matches the 10.6 figure's composition); E20 penalty −3 %
matches ARAI 1-6 % band; TC slip −7.5 % below lock-up matches typical converter losses.

## 6. How to verify per-trip in the app

TripDetail's km/L vs this table: a Hyderabad commute reading 11-14 is *at model*,
not a fault; a highway run below 15 at ≤100 km/h cruise indicates pressure/load/AC
anomalies worth a PID look (tyre pressure first — Crr 0.011→0.013 costs ~2 %).

## Sources
- Specs: autocarindia.com/cars/skoda/kylaq/specifications; cardekho.com/skoda/kylaq/specs
- E20 drop band: autocarindia E20 Q&A (ARAI 1-6 %, OEM 7-8 %, real BS4 5.2 %);
  niftytrader E20 brief (new cars 1-2 %); psuconnect E20 table (RON ≥95)
- Premium-octane parity: r/carIndia thread on XP95 vs E20 RON parity
- Owner-oracle constants: app F-6 idle calibration (1.05 L/h), AcClimateModel (7VL HW doc)
