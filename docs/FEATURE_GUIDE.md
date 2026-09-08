# Kylaq TSI Coach — feature & learning guide

Škoda Kylaq 1.0 TSI (EA211 evo2, 999 cc 3-cyl turbo, 85 kW @ 5000-5500 rpm,
178 Nm @ 1750-4000 rpm, kerb 1169-1219 kg, 45 L tank, BS6 Phase 2).
This document explains what the app measures, how, and how to read it while driving
your daily route.

## 1. Live telemetry (Dashboard, Raw Monitor, Android Auto)

- Polling follows the reference capture: functional header `7DF`, both `7E8` and `7E9`
  answer; ISO-TP multi-frame reassembly for VIN (`0902`) and long payloads.
- Every decoded sample is stored per ECU (`7E8_010C`, `7E9_010C`) and a quality-ranked
  primary is published per PID, so a stale or late frame from one ECU can never blank the
  other's good value.
- PIDs enabled by default are the proven EA211 set: RPM, speed, load, MAP, MAF, coolant,
  IAT, throttle, pedals, torque % (0161/0162), fuel rate (015E/019D), trims (0106/0107),
  timing (010E), rail pressure (0123/010A), lambda (0144), voltage, baro, ambient, oil
  temp, fuel level, plus research-only raw PIDs for discovery.

## 2. Log fuel, MID vs recorded (About & Fuel Guide, Trip detail)

"Log fuel" = PID 015E (L/h) integrated over time; distance from 010D. The trip card shows
litres, km, km/L, L/100 km, coasting seconds, idle seconds and a speed-band histogram.
The About screen compares your recorded number with the cluster (MID/MFA) display and
explains why small gaps are normal (rounding, calibration) while large gaps mean the
cluster is optimistic.

## 3. Power, torque and the efficiency sweet spot (Insights)

- Measured torque uses PID 0162 (% of reference torque, 0163/0164 or the factory 178 Nm);
  when the ECU does not answer, torque is recovered from fuel energy through a fixed-point
  solve against the BSFC-shaped efficiency map, or from vehicle dynamics.
- The factory curve (178 Nm plateau, 85 kW rated) is drawn dashed for comparison.
- The L/100 km-vs-speed sweep uses your *measured* top-gear ratio (minimum rpm per km/h
  observed above 40 km/h). Its minimum is the sweet spot — for the 6-speed gearbox this
  usually lands around 65-85 km/h. Above it, drag (∝ v²) dominates; below it, pumping
  losses dominate.

## 4. Driving in neutral / coasting (Insights, coach tips)

Owner's-manual window: selector in D, neither pedal depressed, 20-130 km/h. Two OBD
signatures are distinguished:

- **engine-braking fuel cut** — gear engaged, rpm high, fuel rate ≈ 0 L/h (the efficient one);
- **neutral idle coast** — drivetrain disengaged, rpm at idle (burns idle fuel, no engine
  braking).

Each event records distance, duration, fuel used and fuel saved against two baselines
(idle burn, modelled powered cruise). Totals accumulate per session.

## 5. Turbo behaviour (Insights)

Boost = MAP (010B) − baro (0133): negative is vacuum, positive is charge compression.
Tracked: live/peak boost, deepest vacuum, boost-vs-rpm histogram, tip-in lag (pedal stab
to 40 kPa — the measurable sub-2000 rpm lag), over-boost counts above 160 kPa, charge-air
temp and wastegate duty when answered.

## 6. X95 vs regular petrol (Insights → Tanks)

Refuels are detected from fuel-level rises (≥ 8 % within 45 min). Per tank the app stores
cruise-only ignition timing (healthy ≈ 20-35°), short/long-term trims, knock-retard
withdrawals under load and km/L, then scores 0-100. Higher octane lets the ECU hold
advance; weak fuel shows pulled timing and positive LTFT. The comparison note names the
better tank with the deltas.

## 7. Always-on logging (Connect dialog, Settings)

- Star a paired adapter as **default**; otherwise adapters are recognised by name
  (ELM/OBD/VGate/…) so a headset is never grabbed.
- **Auto-connect**: a supervisor re-opens the adapter within ~10 s of any drop, from the
  phone app or Android Auto.
- **Auto-record**: recording starts when rpm > 200 and is saved after the engine has been
  off for 60 s — every drive is captured without touching the phone.

## 8. Android Auto

- IOT-category Car App Service; the dashboard auto-connects, validates PIDs and polls by
  itself, shows RPM/speed/temps/pressures/fuel/economy/driving state at 2 Hz, with
  **Details** (boost, timing, trims, MAF, oil temp, run time, MIL distance, trip summary and
  every enabled PID) and **Reconnect** actions.

## 9. Google Sign-In

Credential Manager with a SHA-256 nonce; the OAuth Web Client ID can be entered on-device
(Settings → Cloud Backup → Google Sign-In setup), which also shows the package name and
signing SHA-1 to register in Google Cloud Console. Placeholders are rejected instead of
being sent to Google (the original "chooser opens then nothing" bug). Cancellation is not
an error; Firebase exchange only runs when a FirebaseApp exists.

## 10. Coaching (Insights → Coaching)

Tips are generated from measured data only: speed vs sweet spot, harsh accel/brake counts,
coasting quality, idle cost in litres, low cruise advance (octane hint), turbo lag, and
trip efficiency as a percentage of the 19.0 km/L ARAI figure — each with the physical
reason, because the goal is understanding the car, not gamification.
