# Does the AC consume battery during an idle start-stop stall? (owner pipeline task 5, 2026-09-16)

**Owner question:** *"When engine start stop stopped car sometime AC will be still running
during that time does it consuming battery"*

## The honest answer, from the hardware

**Yes - but only the fans, not the cooling.** The Kylaq 1.0 TSI's air-conditioning uses a
**belt-driven 7VL variable-displacement compressor with an electromagnetic clutch**
(nameplate 2QD 820 803 C - see `docs/reference/kylaq-ac-compressor-hardware.md`). During
an auto-stop the engine - and therefore the belt - stands still, so the compressor
**cannot pump refrigerant**: cooling pauses until restart. What keeps running is the
**blower and the fans, straight off the 12 V battery**, which is exactly why you still
feel air from the vents while the engine is off.

This is by design, and the car manages it: the start-stop ECU monitors battery state of
charge and 12 V stability, and it refuses a new auto-stop (or restarts the engine early)
when the battery needs charging. A stall with the blower running is a net battery drain,
repaid by the alternator after restart - the energy is not free, it just moves from
"idling fuel" to "battery then alternator load", which is still far cheaper than burning
~1 L/h of petrol at a standstill (measured warm-idle baseline in the start-stop row).

## What the app now MEASURES (not assumes)

J1979 exposes **no battery-current PID** on this ECU, so drain cannot be quoted in
amp-hours without inventing numbers. Instead `analysis/StopBatteryAnalyzer.kt` slices the
**real stall windows** (newly exposed by `StartStopAnalyzer.Summary.stopWindows`, closing
at the last observation still proven stopped - never at the restart instant) out of the
stored 0142 voltage and reports, on the trip fuel card:

- mean and min battery voltage **inside the stalls** vs the engine-running **charging
  baseline** (rpm >= 400 samples only);
- the **sag** between them - a labelled proxy for the 12 V load the blower/fans/defogger
  put on the battery while stopped (deeper sag = heavier stall loads);
- how many stalls **overlapped a measured AC-on segment** (voltage-ripple detector, see
  `docs/reference/ac-voltage-detection-2026-09-16.md`): those are the stalls where the
  blower demand was demonstrably present.

Evidence floors keep it honest: fewer than 3 voltage samples inside stalls, or no charging
baseline, and the row stays hidden instead of guessing.

## Tests

- `StartStopWindowsTest` (5): window brackets the proven-stopped stretch, two stalls two
  windows in order, sub-3 s flickers yield nothing, a trip ending mid-stall still records
  its window, and Bluetooth silence never fabricates window time (gap-expiry closes at the
  last observation).
- `StopBatteryAnalyzerTest` (6): stall slicing mean/min, baseline separation, out-of-window
  samples never leak in, AC-on overlap counting, evidence floor, inclusive window edges.

## Not claimed

- No amp-hour or watt figures: no current sensor answered on this ECU, and a fabricated
  Ah number would violate the no-fake-values rule. The sag + measured AC overlap is what
  the data can honestly support; if a battery-current PID ever answers on this car, the
  analyzer is the place to upgrade.
- Compressor wear/clutch cycles during restarts are out of scope here.
