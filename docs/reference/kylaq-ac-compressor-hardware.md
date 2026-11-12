# Škoda Kylaq (MQB-A0-IN) AC compressor — hardware identification & modelling consequences

**Evidence date:** 2026-09-13
**Primary evidence:** owner photograph of the compressor nameplate in-car
(`IMG-20260619-WA0036.jpg`), read as:

| Label field | Value |
|---|---|
| Brand | VW AG (VW logo) |
| Part number | **2QD 820 803** (suffix series C; label shows `2QD.820.803`) |
| Manufacturer line | "MFD. BY: … AIR CONDITIONER CO., LTD", **MADE IN CHINA** |
| Manufacturer no. | **FM10S 14G** (aftermarket cross-refs list `FM10S14C-011 / -011A` for the same OE number) |

**Cross-references (secondary, catalogue level):**

| Source | Statement |
|---|---|
| VW Group genuine-parts catalogue (oemvwshop, code 2QD820803C / 2QD820803) | "A/c compressor **with electro-magnetic coupling**", weight 6.44 kg |
| Aftermarket cross-ref (intl2008, INTL-XZC2230) | OE `2QD 820 803 C` / `20D.820.803` / `FM10S14C-011`; **Type 7VL**; pulley **6PK**; clutch Ø **110-115 mm**; application: India Škoda Kushaq / VW Taigun / Polo / Lavida / T-Cross |
| Application fit | 2QD = MQB-A0-IN prefix (Kushaq / Taigun / Kylaq / Slavia family) |

## Hardware verdict

* **Electromagnetic clutch FITTED** (VAG catalogue title). The compressor is NOT
  clutchless.
* **Variable displacement, 7 cylinders** ("7VL" type, Sanden SD7V-family lineage;
  mfr no. FM10S14x ≈ 140-160 cc/rev class), 6PK belt pulley Ø 110-115 mm.
* Made in China by a VAG-contracted air-conditioner manufacturer (label).

## Consequences for `com.example.engine.AcClimateModel`

1. **AC OFF = exactly 0.0 kW.** With the clutch open the pulley free-wheels; there
   is no clutchless minimum-displacement parasitic floor (0.2-0.4 kW) to model.
   Locked by `AcClimateModelTest.clutch open means zero parasitic floor`.
2. **Load follows cooling demand, not engine rpm.** A variable-displacement unit
   trims its swash plate to hold evaporator target, so absorbed power is a function
   of delta-T (ambient − setpoint) and head pressure — the existing
   `AC_BASE_KW + KW_PER_DELTA_C * Δ` shape is the correct functional form. No
   rpm-proportional term must be added (that would be a FIXED-displacement model).
3. **Cap 4.0 kW** is consistent with full stroke at high head pressure for the
   7VL/140-160 cc class; retained.
4. **WOT/kickdown clutch drop** (VW load management) exists on this hardware but
   needs no model term: AC state is a per-ride owner tag and the learning compares
   whole-ride economy.
5. **AUTO modulation (×0.75) remains a documented assumption** — the comfort-CAN
   control-valve duty is not observable through the ELM327 J1979 channel. When the
   per-ride ON-vs-OFF learner accumulates rides in both AUTO states, the measured
   delta should be used to re-validate this factor (standing rule: primary
   measurements beat assumptions).
6. Calibration sanity: at Δ≈9 K the model prices ≈1.9-2.0 kW ≈ +0.71 L/h at idle,
   inside the typical 0.5-0.8 L/h idle AC penalty band for a B-segment petrol car.

## What was NOT changed and why

No numeric constant was altered by this research: every prior assumption survived
the hardware check (variable displacement was already assumed; OFF=0 already held).
The changes are identity constants (`HW_*`), corrected comments (clutch is on/off,
not a load term), an on-card hardware line, and guard tests pinning the clutch
semantics and part identity.
