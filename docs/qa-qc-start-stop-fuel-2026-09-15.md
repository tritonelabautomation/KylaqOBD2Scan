# QA/QC — idle start-stop: fuel saved, restart spikes, and a trip-truncating watchdog bug

**Date:** 2026-09-15
**Trigger:** owner question — *"did you add fuel saved with auto start stop function and also
fuel spikes any during the auto start stop?"*

## Ground truth: the car really has it

The Škoda Kylaq 1.0 TSI lists an **Idle Start-Stop System** as standard equipment
(zigwheels.com Kylaq 1.0 TSI spec page; cardekho.com Kylaq specs — "Idle Start-Stop System:
Yes"). At a standstill the ECU shuts the engine off, keeps answering on CAN (rpm 0, fuel
rate ≈ 0, ignition still on so the ELM327 stays linked) and cranks again on clutch/brake
release. Everything below follows from that behaviour.

## Audit result before this change

| Question | Answer before | Answer now |
| --- | --- | --- |
| Does trip fuel double-count stalls? | No — fuel is integrated from the **measured** rate (015E / 019D), which reads ~0 while stopped, so totals were already honest | unchanged |
| Is the fuel *saved* by start-stop shown anywhere? | **No** — nothing detected stalls at all | Yes — per trip, estimate labelled with its baseline |
| Were stall seconds counted as "idling"? | **Yes** — `TripFuelSummary.idleSeconds` was speed-only (≤ 1 km/h), so engine-off time landed in the idle bucket; `DrivingCoach` then billed `idleSeconds × 1.05 L/h` — imaginary fuel for time the engine was not running | Split: `idleSeconds` (engine running, rpm > 300) vs `engineOffSeconds`; coaching paths were already rpm-gated (`DriveAnalytics`) or rpm-banded (`TripTrendAnalyzer` 400–1300) and needed no change |
| Restart fuel spikes? | **Not detected**; cranking enrichment samples could leak into idle averages | Captured per restart (peak L/h + integrated L) inside a 6 s enrichment window and excluded from the idle baseline; the fuel itself stays in the trip total because it is really burned |
| Does a long traffic light break recording? | **YES — P0 bug**, see below | Fixed |

## P0 bug found while auditing: the watchdog shredded city drives

`MainViewModel.startSessionAutomation()` auto-stops a recording after **60 s** of
`rpm ≤ 200`. With Idle Start-Stop, every signal longer than a minute looked like "car
parked": the recording stopped mid-drive, and the engine restart began a **new** trip — one
city drive silently split into fragments (and the auto-Drive-backup then mirrored the
fragments).

**Fix:** the discriminator is link liveness, not rpm alone. `liveNumericMap` only serves
non-stale values (`LiveTelemetryStore.markStale` drops aged entries), so:

- `rpm != null && rpm ≤ 200` → ECU is answering with the engine off = **start-stop stall** →
  5-minute grace before auto-stop (longest signals are ~2-3 min; a parked-but-awake car
  still stops eventually).
- `rpm == null` → stale entry dropped = ignition off / Bluetooth gone → the original 60 s
  rule.
- Engine restart (`rpm > 200`) resets the timer and the **same recording continues**.

## How the accounting works (`analysis/StartStopAnalyzer.kt`, pure JVM)

Detection (interval attribution: the seconds before a sample belong to the previous
observation — same rule as `TripTrendAnalyzer` / `RideBehaviorRecorder`):

- **stall** = rpm < 300 AND speed < 1 km/h AND fresh samples keep arriving; committed only
  at ≥ 3 s (restart flicker / sensor dropout is discarded, not claimed);
- **gap > 15 s** (same ceiling as `TripFuelSummary.MAX_GAP_MS`) closes the stall at the last
  observation — link silence is NEVER converted into claimed engine-off seconds, and is not
  counted as a restart either;
- **restart** = rpm ≥ 400 after a committed stall → opens a 6 s cranking-enrichment window:
  peak fuel rate + integrated spike fuel are reported, and window samples are excluded from
  the idle baseline;
- **fuel burned while stopped** is integrated from measured samples (normally ~0; if a car
  purges EVAP while stalled, that fuel honestly reduces the claimed saving).

Saved fuel — an estimate against real data, always labelled:

```
saved ≈ baselineIdleLh × engineOffSeconds / 3600 − measuredFuelBurnedWhileStopped   (clamped ≥ 0)
baselineIdleLh = this trip's own measured warm-idle rate (rpm 400–1300, speed < 1,
                 outside enrichment windows, ≥ 30 s of fuel-bearing evidence)
               → else the 1.05 L/h model figure (PowertrainModel.IDLE_FUEL_LH), labelled MODEL
               → no stalls at all ⇒ Baseline.NONE and nothing is claimed
```

This obeys the no-fake-values rule: the number is derived from the trip's own recorded
samples whenever possible, the fallback is the documented model constant, and the UI says
which one was used. Nothing is shown for trips without start-stop activity.

## Where it surfaces

- **Trip fuel card** (`TripDetailScreen.TripFuelLogCard`): `• engine off X s` next to
  coasting/idling; green row `Start-stop: N stall(s), engine off X s • fuel saved ≈ Y.YY L
  (estimate, baseline …)`; amber row `Restart fuel spike: peak Z.Z L/h across N restart(s)`
  with the honest note that this fuel is real and already inside the trip total.
- **Weekly overview / drive analysis strip**: STOPPED band = `idleSeconds + engineOffSeconds`
  (the band means "not moving"; splitting engine-off out of it would under-report city
  standstill). Calm score uses the same standstill total.
- **Fuel Savings Guide FAQ**: start-stop answer now points at the measured rows.
- Legacy trips without rpm samples keep the old attribution — the split never invents
  engine-off time without evidence.

## Tests

- `StartStopAnalyzerTest` (10): measured-baseline saving; sub-threshold flicker; link
  silence closes-and-doesn't-claim; MODEL fallback + labelling; purge fuel reduces saving;
  DFCO coasting not a stall; normal trip claims nothing; trip ends mid-stall; two stalls;
  rpm-null trip fabricates nothing.
- `TripFuelSummaryTest` (+2): stall seconds split out of idle with analyzer wired end to end;
  rpm-less trips keep legacy idle attribution.
- Existing suites untouched and green (weekly overview and drive-analysis tests construct
  summaries without the new fields; defaults keep them valid).

## Not done (deliberately)

- Live "stalled right now" dashboard state — the live economy card already refuses to show
  idle L/h when rpm ≤ 400 (`EconomyEngine`), so no fake value appears during a stall; a
  dedicated LIVE START-STOP badge is cosmetic and can follow if the owner wants it.
- Starter/battery wear tracking — no PID evidence base for it on this car; not invented.
