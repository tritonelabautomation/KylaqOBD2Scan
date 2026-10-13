# Skoda Kylaq Signature+ 1.0 TSI AT — chassis, tyre, brake & electrical reference

Verified against Skoda India catalogue listings (Dec 2024 launch data) and Autocar India
spec sheets (2026), cross-checked with the owner's reported build. Feeds the gearbox model
(tyre circumference), the ride X-ray (battery-voltage envelope) and the parts guide.

## Tyres & wheels

| Source | Size | Wheel | Rolling circumference |
|---|---|---|---|
| Owner-reported | 205/55 R16 | 16" | ≈ 1.985 m |
| Catalogue (Signature / Signature+ AT, 16" alloys) | **205/60 R16** | 16" | ≈ 2.050 m |
| Catalogue (top trims, 17" alloys) | 205/55 R17 | 17" | ≈ 2.065 m |

**How the app resolves the ambiguity:** `Aq250GearModel` learns the true rpm-per-km/h scale
from stable cruise samples (bounded ±25 % around the prior). The three candidate tyres differ
by ~1–4 % in effective circumference — after a few rides the *learned* scale tells the owner
which tyre the car actually wears (and detects pressure/wear drift and post-service fitment
changes). Spare: steel, space-saver.

Tyre circumference maths: `π × (rim × 25.4 + 2 × width × aspect) / 1000`.

## Brakes & stability (Signature / Signature+ AT)

- Front: ventilated disc · Rear: **drum** (disc only on top trims)
- **ABS** (4-channel) + **EBD** standard across the range
- **ESP** (electronic stability program) + **traction control (ASR)** standard
- Electronic differential lock (XDS-style, via brake intervention)
- Brake assist: not listed for Signature AT; hill-hold: not on Signature, yes on higher trims
- Steering: electric power assist, tilt + telescopic

## Suspension & dimensions

- Front: independent MacPherson strut, coil springs
- Rear: non-independent torsion beam, coil springs
- L 3995 · W 1783 · H 1619 · wheelbase 2566 mm · GC 189 mm
- Kerb ≈ 1255 kg (1.0 TSI AT range) · boot 446–1265 L · monocoque, 5 doors

## Electrical — what OBD can and cannot log

- **Voltage: YES.** J1979 PID **0142** (control-module voltage) is polled every 2 s; the ride
  recorder stores a min/avg/max envelope per ride. Healthy charging band with engine running:
  **13.2–14.8 V**. Avg < 13.2 V or dips < 11.5 V flag charging weakness or heavy electrical
  load (AC clutch + blower + rear defogger).
- **Current (amps): NO — physically unavailable.** J1979 defines no battery-current PID and
  this ECU exposes none; measuring amps needs a clamp meter or a shunt in the battery strap.
  The app therefore logs the voltage envelope (the observable consequence of current draw)
  and correlates it with the owner-tagged AC/blower state instead.

## Climate state (AC on / blower / off)

J1979 exposes no AC-clutch or compressor PID on this ECU, so the owner tags the state with
one tap on the dashboard (OFF → AC → BLOWER). The ride recorder splits seconds/km/fuel per
state (interval-attributed like every other channel), yielding per-ride and cross-ride
"AC costs +X % fuel" comparisons — the same evidence pattern as the D/S/M mode tag.

## Model anchors already shipped elsewhere

- Engine: 85 kW @ 5000–5500, 178 Nm @ 1750–4000 → `PowertrainModel.kt` +
  `docs/reference/ssp111-1-0-tsi-ea211.md`
- Gearbox: AQ250-6F ratios 4.148/2.370/1.556/1.155/0.859/0.686 → `GearModel.kt` +
  `docs/reference/aq250-09g-ssp291.md`, ATF service → `docs/reference/09g-workshop-manual.md`
