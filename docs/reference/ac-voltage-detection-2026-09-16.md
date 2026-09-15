# AC state, MEASURED: battery-voltage ripple detection (2026-09-16)

**Owner insight:** *"i found a easy way to guess if AC is in or off see voltage fluctuations
due to AC on"* — supplied with a labelled drive (session `2df90142`, Kylaq Run 2026-09-15
07:26): AC **off** for the first ~10 km, switched **on** during a 90-100 s signal halt.

## Why this matters

SAE J1979 mode 01 exposes **no compressor-state PID** on the EA211 — the climate system
lives on a comfort-CAN segment the ELM327 never sees. Until now the app could only price AC
from owner tags (`AcClimateModel`, tags -> kW via the documented 7VL compressor model).
The owner's heuristic turns AC state into something the car *shows* on a PID we already
poll (0142 control-module voltage, 2 s cadence):

- **AC OFF:** clutch open, pulley free-wheels; 0142 moves only with alternator regulation —
  a quiet band (MAD of a few hundredths of a volt);
- **AC ON:** the electromagnetic clutch cycles and blower/condenser fans pulse, so electrical
  load steps repeatedly; regulated voltage fluctuates visibly around a slightly lower mean.

Hardware basis for the mechanism: `docs/reference/kylaq-ac-compressor-hardware.md`
(compressor 2QD 820 803 (C), 7VL variable-displacement **with electromagnetic clutch**).

## Detector (`analysis/AcVoltageDetector.kt`, pure JVM)

1. Engine-running gate: rpm >= 400 (cranking dips and key-off decay are not AC);
   voltages outside 12.2-15.5 V masked even when rpm claims running.
2. 60 s windows, 30 s step, >= 12 samples; robust fluctuation = mean absolute deviation
   from the window median (MAD), so single coast-alternator-boost spikes cannot flip state.
3. **Self-calibrating:** quiet baseline = 20th percentile of this trip's own window MADs
   (floored at 0.02 V); ON threshold = max(2x quiet, quiet + 0.10 V); OFF threshold =
   max(1.5x quiet, quiet + 0.06 V). No per-car volts guesswork.
4. Hysteresis: 2 consecutive windows above to flip ON, 3 below to flip OFF.
5. Honesty outputs: `confidence` = p90/p20 window-MAD separation (a trip that never shows a
   quiet regime cannot be split and reports confidence ~1 instead of inventing segments);
   thin evidence returns EMPTY.

Surfaces on the trip fuel card (purple row): AC-on minutes vs trip minutes, first state flip
timestamp, quiet baseline, and a "weak separation - treat as a hint" caveat when confidence
is low. Computed from stored samples, so historical trips get analysed too.

## Validation status

- **Synthetic:** `AcVoltageDetectorTest` (7) + `TripFuelSummaryTest` integration (1): quiet
  band stays OFF; switch caught within ~2 windows of truth with honest lag; cranking/stalls
  excluded; boost spikes cannot flip; single-regime trips flag weak confidence; thin
  evidence returns EMPTY. CI green.
- **Real drive:** PENDING. The owner's labelled CSVs did not reach the agent workspace
  (attachment mount absent this session - checked twice). Two paths forward:
  1. on-device: the detector runs over `2df90142`'s stored 0142 samples in the next build;
     the owner compares the reported first-flip time with the remembered 90-100 s halt at
     ~10 km and reports back;
  2. re-attached CSV once the mount works, for an offline regression fixture.
  Thresholds are documented assumptions until one of these confirms them; the confidence
  flag keeps unvalidated splits visibly labelled.

## Not claimed

- No per-event clutch-cycle counting as a secondary signal yet (MAD alone is the state
  input); cycle-rate corroboration is a candidate follow-up once real data lands.
- Detector does not replace `AcClimateModel` kW pricing; it supplies the missing STATE.
  Wiring detected state to gate the model (instead of tags) is the obvious next step once
  on-device validation agrees with the owner's memory.
