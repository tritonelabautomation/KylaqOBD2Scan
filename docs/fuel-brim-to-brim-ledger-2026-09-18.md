# Fuel ground truth: the brim-to-brim ledger (2026-09-18)

Source: owner screenshot `Screenshot_20260918-200729.png` (mileage log, 20:07 IST) plus the
owner's statement that one refill was done **to nozzle auto-cut with the engine off**, producing
the two session exports `ce0be7ee_*` and `65644986_*` (which have NOT reached the workspace yet -
see §5 for what they must contribute).

Every number below was re-derived from the card fields, not copied from the card's own computed
columns. Where the card's computed column disagrees with the re-derivation, the disagreement is
shown and the column is discarded.

## 1. The fill history, audited

| Fill (IST date) | Litres | Price/L | L x price/L | Card cost | Odo | Card +km | Δkm ÷ L | Card km/L |
|---|---|---|---|---|---|---|---|---|
| 2026-08-12 | 39.93 | 115.61 | 4,616.3073 | 4,616.31 ok | 2,436 | (hidden, +4xx) | 438.83/39.93 = 10.990 | 10.99 ok |
| 2026-08-24 | 36.89 | 125.17 | 4,617.5213 | 4,617.52 ok | 2,802 | +366 ok | 366/36.89 = 9.921 | 9.92 ok |
| 2026-09-09 | 36.66 | 115.61 | 4,238.2626 | 4,238.26 ok | 3,205 | +403 ok | 403/36.66 = 10.993 | 10.99 ok |
| 2026-09-17 | 31.13 | 115.80 | 3,604.8540 | 3,604.85 ok | 3,501 | +296 ok | 296/31.13 = 9.509 | 9.51 ok |

Odometer chain is self-consistent: 2,436 -> 2,802 (+366) -> 3,205 (+403) -> 3,501 (+296). The
08-12 +km badge is cut off in the screenshot; its implied delta from the card's own km/L is
39.93 x 10.99 = 438.8 km, consistent with a "+4xx" badge.

**Discarded column - rupees per km.** On two of four cards it does not equal cost / Δkm:
09-09: 4,238.26 / 403 = 10.52 but the card shows 11.386; 08-24: 4,617.52 / 366 = 12.62 but the
card shows 11.653. (09-17: 12.18 vs 12.159 and 08-12: 10.52 vs 10.516 do agree.) Whatever the
third-party app divides by on those two cards, it is not the odometer delta, so that column is
not used anywhere in this ledger.

## 2. Tank-true economy (the ground truth the logs alone can never give)

Each interval between two brim fills is a closed fuel balance: the litres pumped at the second
fill replace exactly what was burnt since the first.

| Interval | Δkm | Litres at second fill | km/L | L/100km |
|---|---|---|---|---|
| 08-12 -> 08-24 | 366 | 36.89 | 9.92 | 10.08 |
| 08-24 -> 09-09 | 403 | 36.66 | 10.99 | 9.10 |
| 09-09 -> 09-17 | 296 | 31.13 | 9.51 | 10.52 |

Pooled over all four fills: 1,504 km / 144.61 L = **10.40 km/L mixed-driving truth**
(band 9.51-10.99). Validity condition, stated not assumed: an interval is a closed balance ONLY
if both endpoint fills reached nozzle auto-cut. See §4 for which endpoints are confirmed.

## 3. App-side cross-checks already available

* Trip ce0be7ee (2026-09-17 11:26, 35 min): 13.1 km / 1.5 L = **8.73 km/L** at 22 km/h average -
  city-heavy, therefore correctly BELOW the 10.40 mixed truth.
* Recovered run 2026-09-18 15:44: 31.0 km / 3.82 L = **8.11 km/L**, drive analysis 44 min slow +
  39 min congested - again below mixed truth, correct direction; and it matched the cluster's
  own 8.1 km/l exactly in the 2026-09-18 field validation.
* Odometer agreement: the app's 01A6 read ~3,454 km in mid-September; the log says 3,501 km at
  the 09-17 fill. Same instrument, plausible daily delta - app and pump log measure one car.

These are coherence checks, not proof. Proof needs §5.

## 4. Which fills are confirmed brim - UNCONFIRMED, asked twice, unanswered

* The engine-off auto-cut fill: **UNCONFIRMED which card it is.** Candidate A = the 2026-09-17
  card (31.13 L, JIO BP Madhapur, 2 receipt pictures) with ce0be7ee (09-17 11:26-12:02) as the
  drive before it. Candidate B = a 2026-09-18 fill not yet in the log, with 65644986 after it.
* The 2026-09-09 fill (36.66 L): **UNCONFIRMED** whether auto-cut. If it was partial, the
  09-09 -> 09-17 interval is NOT a closed balance and the first valid pair becomes
  08-24 -> 09-09 (36.66 L / 403 km = 10.99).

Decision rule that needs no memory, only the missing session files: the odometer at the END of
ce0be7ee. If it reads ~3,501, the fill came right after that drive and candidate A holds. If it
reads ~3,501 plus another day's km, candidate B holds. The session odometers settle §4 by
arithmetic instead of recollection.

## 5. What the two session exports must contribute (still not in the workspace)

Three extractions close the check; the Gemini prompt handed to the owner on 2026-09-18 requests
exactly these among others:

1. Odometer first/last per session - gives recorded km and the UNRECORDED gap km around the fill
   (gap = odo at start of post-fill session minus odo at end of pre-fill session).
2. The engine-off window (RPM = 0, >= 60 s) that IS the refuel: start/end IST, and whether raw
   frames inside it show ECU responses (key-on engine-off) or silence (key off).
3. The fuel-rate integral per session: fuel_L = sum(rate_L_per_h x dt_h), over RPM > 0 rows.

Final formula, computed only once coverage is known:
coverage = recorded km in [odo_prev_brim, odo_this_brim] / Δodo. If coverage is high (> ~90 %),
app-integrated litres over the interval must equal pump litres within rounding - a true
brim-to-brim proof. If coverage is partial (the likely case: 296 km driven vs the km the app
holds), the pump litres validate the BAND and the per-trip integrals remain validated by the
cluster cross-check instead; saying otherwise would overclaim, which this repo does not do.

## 8. Session forensics (Gemini snippet pass, 2026-09-18 evening; every hex re-derived here)

The six files still have not reached the workspace. The owner ran the extraction prompt through
Gemini, which could read only raw.txt snippets; its CSV-dependent sections came back ABSENT. What
survived is enough to close three questions, after correcting Gemini's truncation errors.

### 8.1 Frame-level re-derivation (arithmetic discipline: bytes first)

* 01A6 `00 00 88 3F` = 3487.9 km; `00 00 88 88` = 3495.2 km; `00 00 89 BA` = 3525.8 km - all three
  match Gemini. Regressing frames also confirmed: `885D` = 3490.9 and `8885/8886` = 3494.9/3495.0
  appearing AFTER 3495.2 (see 8.5).
* 012F `60` = 37.65 %, `EF` = 93.73 %, `66` = 40.00 % - the car DOES answer fuel level, on 7E8,
  inside our own poll loop (TX > 012F appears in the raw).
* 010C `0F1C` = 967 RPM and `0F44` = 977 RPM at session B start (parked, idling), `0000` = 0 RPM
  from 12:46:07 - the key-on-engine-off window.

### 8.2 Gemini's truncation errors, corrected

Gemini read session A's window as 11:26:39-11:47:04 and 7.3 km. The app's own trip card for
ce0be7ee says 11:26:39-12:02:36 and 13.1 km, so A ran ~15 minutes past Gemini's snippet and its
final odometer is ~3487.9 + 13.1 = 3501.0, not 3495.2. Consequently the gap is 42 min 10 s, not
57 min 42 s, and the 30.6 km gap distance splits: 5.8 km before the pump (3495.2 -> 3501.0) and
24.8 km after it (3501.0 -> 3525.8). Gemini's TOTAL gap distance was right; its split and windows
were not.

### 8.3 The fill is identified - ledger section 4 candidate A, confirmed by arithmetic

The odometer at the end of session A is ~3501.0, which is exactly the odometer on the 2026-09-17
fill card (3,501 km, 31.13 L, JIO BP Madhapur). The decision rule in section 4 needed nothing but
that number: the engine-off auto-cut fill IS the 09-17 card. Session B is the post-fill drive's
arrival: idle 967 RPM parked at 12:44:46, engine off 12:46:07, ECU answering (key-on) until
12:48:54, then key-off NO DATA until 12:50:10. The refuel itself sits in the gap, engine off at
the pump, exactly as the owner described.

### 8.4 A new independent ground truth: tank capacity from the fill

Level 37.65 % at 11:46 (pre-fill, mid-drive) and 93.73 % at 12:44 (post-fill AND post the 24.8 km
drive home). Raw rise 56.08 pts with 31.13 L pumped implies 55.5 L - a bound, biased low because
both readings sit inside consumption. Correcting each by the fuel burnt between reading and fill
(~0.5 L before, ~2.38 L after at the 10.4 km/L pooled truth) gives a rise of 61.8 pts for 31.13 L
= **50.35 L against the Skoda Kylaq's 50 L spec - agreement within 0.7 %**. One pump receipt, one
level PID and one spec sheet, three independent instruments, one tank. This validates the pump
litres, validates 012F as real and roughly linear at fill scale, and needs no assumption about
whether the 09-09 fill was brim.

Level is NOT trustworthy below fill scale, and the ledger says so: over 7.3 km inside session A
the level fell 6 steps (1.18 L at 50.3 L = 6.2 km/L) while over the 24.8 km post-fill drive it
implies 2.4 L = 10.3 km/L, and the app's own integral for A is 1.5 L = 8.73 km/L. One step is
0.197 L and a moving tank sloshes; short-segment level economics are noise, fill-scale deltas are
signal. Coherence, not proof, at segment scale.

### 8.5 Anomalies, adjudicated

* Odometer frames regress inside session A (3495.2 then 3490.9 then 3494.9/3495.0). A CAN/ELM327
  transient, not a car fault - an odometer cannot unwind. Deliberately NOT clamped: trip distance
  integrates 010D (`TripFuelSummary.distanceKm += v0 * dt / 3600`), never odometer deltas, so no
  owner-visible number is affected, and the journal's job is evidence - a clamp would destroy the
  fingerprint of the transient. Revisit only at the display layer, and only if the owner ever sees
  a dip on screen.
* Session B's tail polls into a dead ECU (NO DATA stream after 12:48:54). That is the keep-alive
  service holding the adapter for the next drive, by design; noted, not a defect.

### 8.6 What remains open

* Whether the 2026-09-09 fill (36.66 L) reached auto-cut - still unanswered, still the only thing
  standing between "closed balance" and "band" for the 296 km interval.
* The fuel-rate integral per session: CSVs still absent, so the app-side litre integral over the
  brim interval remains validated by the cluster cross-check and the band, not by pump litres.
  Coverage would be partial anyway (296 km driven vs the trips the app holds), so per section 5
  the pump litres could only have validated the band even with full CSV access.

## 6. Standing owner-side items carried## 6. Standing owner-side items carried

Receipt litres of the engine-off fill (and of the 09-09 fill if its card is not auto-cut);
confirmation of §4's two questions in any form, including one word each in chat; re-attach of the
six session files or the pasted Gemini extraction.
