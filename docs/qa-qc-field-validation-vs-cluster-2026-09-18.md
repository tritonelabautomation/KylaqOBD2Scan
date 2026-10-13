# QA/QC — Field validation against the car's own trip computer (2026-09-18)

**Date:** 2026-09-18 (IST)
**Build:** 1.0.368 (code 10368), branch `arena/01a07c37-kylaqobd2scan`
**Evidence:** two photos from the owner, same drive. (1) The app's recovered trip screen,
`Recovered Run 2026-09-18 15:44`, session `5f9d2093`, photographed 17:45 IST. (2) The Kylaq virtual
cockpit in "Since start" mode, photographed 17:17 IST (`IMG_20260918_171749096_HDR.jpg`).

**Why this exists.** Everything in this repo until now was validated against traces, simulators and
unit tests. This is the first drive where the app's numbers can be laid beside the instrument
cluster's own trip computer for the same journey. The owner's standing rule is that a claim is not
evidence until it replicates; this is the replication.

---

## 1. The cross-check

| Quantity | App (recovered trip) | Cluster "Since start" | Verdict |
|---|---|---|---|
| Distance | 31.0 km | 32 km | agree within 1 km (≈3 %). Cluster rounds to whole km; the app integrates 010D vehicle speed, which under-reads wheel distance slightly |
| Average economy | 8.1 km/L | 8.1 km/l | **exact match** |
| Average speed | 20 km/h | 20 km/h | **exact match** |
| Duration | 15:44 → ≈17:17 = 93 min | 1:34 h = 94 min | agree within a minute |
| Fuel burned | 3.82 L | implied 32 ÷ 8.1 = 3.95 L | 3 % apart, and the gap *is* the 1 km distance gap; the ratio both report is identical |

Three of five quantities match to the digit the cluster displays, and the two that differ do so by
the cluster's own rounding plus the known difference between speed-integration and wheel distance.
For a first uninstrumented city drive against an independent gauge, this is agreement, not
coincidence: the economy figure in particular is a ratio of two independently-sourced quantities
(fuel from 015E/019D, distance from 010D) landing on the cluster's own number exactly.

## 2. Internal consistency of the app's own card

Each figure below was re-derived by hand from the others; every one closes.

- `100 ÷ 8.1 = 12.35` → the card's **12.3 L/100km**. ✔
- `31.0 ÷ 8.1 = 3.83 L` → the card's **3.82 L** burned. ✔
- `31.0 km ÷ 1.5667 h = 19.8` → the card's **avg 20 km/h**. ✔
- avg 20 overall vs 22 moving ⇒ moving fraction 0.909 ⇒ ≈85.5 min moving, ≈8.5 min not; idling
  232 s + engine off 241 s = 473 s = 7.9 min. ✔ (coasting 231 s counts as moving, throttle closed)
- Start-stop saving: 242 s = 0.0672 h × the card's own measured 1.24 L/h idle baseline = 0.0833 L →
  the card's **≈0.08 L**. The saving is computed from an idle rate measured *on this trip*, not from
  a constant, which is the honest way to estimate it. ✔
- Battery: **10.1 V min at 15:55:54** is a cranking dip at one of the 8 logged restarts; **14.4 V
  max** is charging; mean 12.2 V during stalls vs 12.7 V charging with 0.5 V sag under stall loads.
  All three are what a start-stop car with a healthy battery looks like. ✔

### Three stall counts that are not a contradiction

The card shows **9 stalls**, **8 restarts** with a fuel spike, and battery statistics "during
**6** stall(s)". Those are three nested subsets, not three readings of one number: stalls detected;
of those, the ones that produced a logged restart spike; of those, the ones long enough to have
meaningful battery samples. 9 ⊇ 8 ⊇ 6 is the expected shape.

### Two figures one second apart, left that way on purpose

The overview line says **engine off 241 s**; the start-stop card says **242 s**. They come from two
independent analysers (`TripFuelSummary.engineOffSeconds` and `StartStopAnalyzer.engineOffSeconds`)
with slightly different edge rules at stall boundaries. One second between two estimates of the same
quantity is honest. Forcing them to print identically would mean fudging one of them, so they are
left as they are and the difference is recorded here instead.

## 3. The one number that needed explaining: peak torque above reference

The card read *peak **186 Nm** while running • reference **175 Nm*** — side by side, that looks like
the app exceeding the engine's rated torque and therefore like a decode error.

It is neither. PID 0162 decodes as `A − 125` **percent of reference**, and that scale runs from −125 %
to **+130 %**, so a reading above 100 % is representable by design. 186 ÷ 175 = **106.3 %** — a
momentary over-reference transient, which on a turbocharged engine under overboost is a real
physical reading. The reference 175 Nm is the ECU's own answer on PID 0163 (this car reports its
reference there and stays silent on 0164), not an assumption.

The card now says so instead of leaving two numbers that appear to disagree: when the peak exceeds
the reference it appends the percentage and the reason. The reading itself is **not** clipped or
capped — a capped peak would be a fabricated one, and the no-fake-values rule outranks a tidy card.

## 4. What this also proves about the other two mandates

- **Kill-proof recovery ran in the field.** The trip is titled `Recovered Run`, which only the
  unfinished-session recovery path produces. The drive reached disk and was rebuilt without a manual
  tap.
- **IST is what the phone shows.** The title (`2026-09-18 15:44`), the battery extremes
  (`15:47:18`, `15:55:54`) and the phone clock (17:45) are all IST wall time with no UTC anywhere on
  the screen — the mandate from 2026-09-17, visible on the device rather than in a test.

## 5. What remains unverified

- Absolute fuel burned against a brim-to-brim fill. The cluster and the app agree on the *ratio*;
  neither has been checked against pumped litres, which is the only true ground truth for 015E/019D.
- The 1 km distance gap's sign and size across more drives. One drive cannot separate speed-
  integration under-read from the cluster's whole-kilometre rounding.
- Whether 0162's over-100 % transients track overboost events (they should coincide with high MAP
  and WOT). The raw percent column for this session would settle it; it lives on the phone.
