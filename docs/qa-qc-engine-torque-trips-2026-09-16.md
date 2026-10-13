# Per-trip engine torque calculations (owner pipeline task 6, 2026-09-16)

**Owner task:** "Engine torque calculations."

Torque math already powered the Insights sweet-spot curves (`DriveAnalytics`, PID 0162
when answered, fuel-energy fixed-point recovery otherwise, factory curve dashed for
comparison). What was missing was the TRIP side, so this adds it:

## What ships

- **Trends channel "Torque" (Nm)**: PID 0162 percent-of-reference converted per sample -
  reference = the ECU's own **0164** value when it ever answered, else the factory
  **178 Nm** plateau (`PowertrainModel.PEAK_TORQUE_NM`, the documented 1.0 TSI figure).
  Overlays with every other channel (tasks 1-2) to see torque w.r.t. speed/throttle/load.
- **Trip fuel-card row**: `Engine torque (measured, PID 0162): mean X Nm • peak Y Nm while
  running • reference Z Nm` - engine-running samples only (rpm >= 400), so start-stop
  stalls never dilute the stats.
- **TripFuelSummary fields**: `meanTorqueNm` / `peakTorqueNm` / `torqueReferenceNm`,
  appended last with null defaults (positional-construction safety).

## Honesty rules

- 0162 silent for the whole trip -> all three stay null and the card shows NOTHING. No
  fabricated torque on trip cards; the fuel-energy recovery estimate remains exclusive to
  Insights, where it is labelled "recovered" per the no-fake-values rule (owner 2026-09-14).
- The reference actually used is printed on the row, so a reader can tell ECU-reported
  (0164) from factory-plateau conversion.

## Tests

`TorqueTripTest` (5): factory-reference conversion exact; 0164 overrides factory;
no-0162 trip claims nothing (reference stays null too); parked 90 % reading during
engine-off never lifts the running peak; 2-hex stored pids work like everywhere else.
