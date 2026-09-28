# Trend forensics: what the crosshair can and cannot say (2026-09-18)

Owner challenge, verbatim: "Do proper analysis are you 100% sure about this?" with four trend
screenshots (recovered run 5f9d2093 and ce0be7ee). This document answers with re-derived arithmetic
and separates what is certain from what is not. Confidence is stated per claim; nothing below is
asserted at 100 % that is not an identity or a byte-level decode.

## 1. The sampling contract (the root of most of what looks strange)

One poll loop rotates roughly fifty PIDs at ~11 Hz of RAW rows. Each individual PID therefore
arrives only once per loop cycle:

* Power channel: 665 points over 93 min 19 s = **8.4 s per point**.
* Voltage channel: 480 points over 34 min 39 s = **4.3 s per point**.
* Altitude looks 11 Hz (24,267 points in 35 min) only because the GPS stamp is written onto EVERY
  raw row, not because GPS fixes arrive at 11 Hz.

Consequences, all visible in the screenshots and all expected:

1. A WOT burst shorter than the cadence becomes ONE spike point. The 54.6 kW needle at 17:06:03 is
   a real event undersampled, not a fabricated point - see section 2 for the physics that prove it
   real.
2. The crosshair bubble lists, per signal, that signal's NEAREST sample. With 4-9 s cadence the
   values inside one bubble can be seconds apart in time. "Throttle 88.2 % with Power 8.6 kW" at
   16:01:57 is therefore NOT a same-instant contradiction: the throttle point sits on an accel
   spike while the power/load points come from an adjacent bucket. The trend help text now says so
   in the app.

## 2. Claims at 100 % (identities and byte-level decodes)

* **Power <-> RPM <-> torque identity.** P = 2*pi*N*T/60000. The 54.6 kW point at 2802 rpm implies
  T = 9549 * 54.6 / 2802 = **186.1 Nm** - exactly the over-reference peak documented on 2026-09-18
  (0162 percent torque, 106.3 % of the ECU's own 175 Nm reference on 0163). Three independent
  channels (power, rpm, torque) closing one identity is as certain as this data gets.
* **The spike is a real WOT event, not noise.** Load 99.6 % at the same instant corroborates full
  demand. At 35 km/h the wheel-side power needed to cruise is ~2.25 kW (rolling 0.015*mg + aero at
  1.2 kg/m3, CdA ~0.70 m2); 54.6 kW at the wheels implies ~4.1 m/s2 of acceleration - hard but
  inside this car's envelope (85 kW rated, ~11 s 0-100). Gear check: 2802 rpm at 35 km/h on 0.32 m
  rolling radius is an overall ratio of 9.66, i.e. gear ratio 2.50 at final drive 3.87 = third gear,
  exactly where a WOT pass from 35 km/h belongs.
* **8.6 kW at 65 km/h** vs a computed 5.94 kW cruise need: +45 %, i.e. gentle accel or a grade -
  plausible, not an anomaly.
* **Voltage semantics on ce0be7ee:** 12.1 V at RPM 0 is key-on engine-off (no alternator), 14.7 V
  is a charging phase, 13.2 V average is regulation. Same physics as the 10.1 V cranking dip
  documented on the 09-18 trip; different event, same instrument behaviour.
* **Decode formulas are spec-correct:** throttle = 0111 with A/255 (88.2 % = byte 225), load = 0104,
  power derived per the identity above with the 2.5 s rpm/torque pairing rule, all pinned by
  DerivedSignalSeriesTest.

## 3. Claims NOT at 100 % - flagged, not hidden

* **The ~20 s throttle flat-tops at ~100 % (ce0be7ee, ~15:59:41 and ~16:01:12) while power returns
  to baseline between the pulses.** If the throttle plate truly held 100 % for 20 s, power would
  have held high too. Candidate causes, in order of prior likelihood: (i) the crosshair/bubble
  time-mixing of section 1 making adjacent buckets look simultaneous; (ii) MED17.1.27 reporting a
  demand-side quantity on 0111 during over-run/cut phases; (iii) stale or repeated adapter frames.
  Distinguishing them needs the RAW rows of those two windows (tx timestamps + 0111 bytes), which
  live on the phone, not in this workspace - the ce0be7ee raw export has still not arrived here.
  Until then the throttle channel is labelled trustworthy for event shape and NOT trustworthy for
  duration of plateaus. That is the honest confidence level, and it is why no code "fix" was
  attempted: fixing a channel whose defect is unlocated would be guessing.
* **Whether the 54.6 kW burst peaked higher than 54.6 kW.** At 8.4 s cadence the true peak of a
  ~5 s burst is systematically undersampled. The number is a floor for the event, exact for the
  sample that exists.

## 4. What changed in the app because of this analysis

The trend help text now states the sampling contract and the nearest-sample behaviour of the
crosshair, so the next reader cannot mistake a mixed bubble for a physical contradiction. No
channel math changed: it was already identity-pinned and tested.
