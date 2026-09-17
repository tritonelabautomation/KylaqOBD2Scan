# Kylaq real-car PID validation — owner discovery run 2026-09-16 (F-6 oracle)

Source: owner-run in-app **PID Discovery & Validation** against the real car.
VIN **MEXKPEPC2TG028855** → WMI `MEX` = Škoda India, model year char (10th) `T` = **2026** —
the app's VIN decode matches the car. Two ECUs answered the discovery broadcast:
**7E8** (engine) and **7E9**. Run timestamp 2026-09-16 08:57 IST; conditions at the
validated-PID sample: warm idle, ambient ≈ 31 °C, AC ON.

Per the F-6 rule this run is **ground truth** — it outranks workshop PDFs, spec sheets and
secondary articles. The full JSON report is re-exportable from the app's discovery screen;
this document preserves the decoded engineering conclusions and the raw frames behind the
one defect the run exposed. Per-PID raw hex bytes not transcribed here live in that export.

## 1. The discovery-sequence defect this run exposed (fixed same day)

Raw frames from the run's discovery log (verbatim):

```
TX 0100   RX: 7E804413C14EA                                   ← garbled / stale frame
TX 0120   RX: 7E906410098188001 / 7E8064100BE3EA813           ← the 41 00 bitmaps, ONE COMMAND LATE
```

Diagnosis: classic **ELM327 buffer lag**. The adapter returned a stale garbled frame for
`0100`; the genuine `41 00` bitmaps surfaced during `0120`. The decoder's strict per-request
check (the response's PID byte must equal the requested base) is *correct* and rejected the
lagged frames — but the consequence was that the **entire base block 0x01–0x1F vanished from
the report**: RPM (0C), speed (0D), coolant (05), MAP (0B), throttle (11), fuel-system
status (03), timing advance (0E), fuel rate (10) … all PIDs the app records successfully on
every trip with this car.

The lagged frames decode cleanly under their true base 0x00 (MSB-first, `pid = base + bit`):

| ECU | `41 00` bitmap | Supported PIDs (continuation bit included, per J1979) |
|---|---|---|
| 7E8 | `BE 3E A8 13` | 01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F 20 |
| 7E9 | `98 18 80 01` | 01 04 05 0C 0D 11 20 |

7E8's list is exactly the set the app polls and logs on this car — independent proof the car
supports the full recording chain and that only the discovery *reporting* was broken.
Bit 32 set on both ECUs = "next block (0120) available", consistent with the 0x20+ blocks
the run then decoded normally.

**Fix (this release):**
1. `PidDiscoveryDecoder.salvageLaggedBitmap(requestedBase, lines, alreadyDecodedBases)` —
   pure function: when the requested base's frame did not arrive, checks whether the RX
   carries a valid bitmap for another standard base not yet decoded this run and returns it
   **under its true base** (never re-attributed to the requested one).
2. `PidDiscoveryService` — on a decode miss: salvage-and-record the lagged block, log the
   lag explicitly, **retry the requested command once** (3 s timeout), decode the retry
   (salvaging again if needed). Only if both come up empty is the block skipped, and the log
   now says "even after one retry".
3. Regression tests replay the owner's exact frames:
   `laggedBaseBlockBitmapsFromTheOwnerKylaqRunAreSalvagedUnderTheirTrueBase` plus guards
   (already-decoded bases are not re-salvaged; the garbled `7E804413C14EA` frame salvages
   as nothing — no invented support).

With the fix, a re-run on the car must show the 0x00 block populated for both ECUs; if lag
recurs the log will read `RX to 0120 carried a valid 0100 bitmap (ELM buffer lag) - salvaged
under its true base.`

## 2. Capability bitmaps decoded from the run

| Base | 7E8 bitmap | 7E8 supported PIDs | 7E9 |
|---|---|---|---|
| 0x40 | `FE D0 84 01` | 41 42 43 44 45 46 47 49 4A 4C 51 56 (+continuation → 60) | `E0 80 00 00` → 41 42 43 49 (no continuation) |
| 0x60 | `6B 09 00 41` | 62 63 65 67 68 6D 70 7A (+continuation → 80) | silent in transcribed frames — see JSON export |
| 0x80+ | — | direct-response validation reached 0x8B, 0x9D, 0xA0, 0xA4 (below) | mostly silent |

Continuation-bit positions (0x20/0x60/0x80) appear in supported lists by design: in J1979
PID 0x20 for base 0x00 literally *is* "ECU answers 0120", etc.

**29 PIDs were validated 0141–01A6** — every TX'd PID in that span answered. Values
preserved from the run (all direct responses, not bitmap inferences):

| PID | Name (app catalog) | Value in run | Engineering read |
|---|---|---|---|
| 0143 | Absolute Load Value | 17.6 % | Light load at warm idle — plausible |
| 0144 | Commanded equivalence ratio | λ = 1.000 | Closed loop at stoich — textbook warm idle |
| 0145/47/49/4A/4C | Throttle positions + commanded actuator | validated | Closed-throttle idle set, all answered |
| 0151 | Fuel Type | Gasoline | ✓ matches 1.0 TSI petrol |
| 0156 | O2 Sensor Voltage (B1S1) | 0.635 V | Mid-band switching voltage — closed loop active |
| 0162 | Actual delivered torque | +6 % | Friction + accessories at warm idle — physically sane |
| **0163** | **Engine reference torque** | **175 Nm** | **The ECU's own reference — beats the catalogued 178 Nm peak.** VAG torque PIDs 0161/0162 are % OF THIS VALUE |
| 0164 | Engine peak torque | **no response** | This ECU does not answer 0164 — the 0163-first fallback order is proven necessary, not cosmetic |
| 0165 | Turbocharger Boost Pressure | validated | Answered at idle (≈ atmospheric side of the map) |
| 0167 / 0168 | (load/O2-based) | raw implausible → **rejected** | NO-FAKE-VALUES honesty gate fired correctly on real hardware |
| 018B | Fuel Pump Command | 31.8 % duty | Low-pressure pump at idle — sane |
| 019D | Engine fuel rate (mass) | 0.12 g/s → **0.58 L/h** | See §4 |
| 01A0 | (sump/airflow context) | −20 °C-style implausible | Recorded honestly as implausible, not dressed up |
| 01A4 | Gear | n/a | This ECU does not publish gear via 01A4 (manual gearbox) |

## 3. Torque chain — corrected against the car (shipped)

- **Live:** `ObdScheduler` torque Nm = `0162 %` × (`0163` = 175 Nm when present, else
  `0164`, else factory peak). Already ordered this way before the run; now **proven** right
  (0164 silent on this ECU).
- **Trip side:** `TripFuelSummary.torqueRef` and `TripTrendsView.torqueRefNm` aligned the
  same day — stored `0163` preferred over `0164`, both over the 178 Nm catalog constant
  (`PowertrainModel.PEAK_TORQUE_NM`). `TorqueTripTest` covers both priority rules
  (0163 beats factory; 0163 beats a stored 0164).
- Consequence: every torque figure on this car is referenced to the ECU's own 175 Nm
  (≈1.7 % below the spec-sheet number) and self-corrects if another ECU revision reports a
  different reference.

## 4. Idle fuel-rate lead (019D) — recorded, constants NOT moved

The app decodes 019D per J1979 mass rate `(A×256+B)/50` g/s (real-car calibrated
2026-09-13): the run's sample = **0.12 g/s** → ×3600 ÷ 745 g/L
(`PowertrainModel.FUEL_DENSITY_G_PER_L`) = **0.58 L/h** at warm idle, ≈31 °C ambient, AC ON.

`PowertrainModel.IDLE_FUEL_LH` stays **1.05 L/h**: that anchor was set from trip-*median*
measurements of the owner's own driving (2026-09-14 cross-anchor, with 0.8 L/h at the very
bottom of that measured band), while this is a **single instantaneous sample** — the AC
clutch may have been between engagements at the sampling instant, and rail-flow
instantaneous readings sit below trip-averaged burn. Per the 2026-09-13 evidence rule this
is a **lead, not a citation**: if repeated 019D idle samples across trips cluster near
0.6 L/h, revisit the anchor. Logged in `fuel-saving-evidence-base.md`.

## 5. What this run confirms about existing app behaviour

- The daily recording chain (01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F from the
  salvaged base block; 41–47 49 4A 4C 51 56; 62 63 65 67 68 6D 70 7A; 8B 9D; A0 block)
  is bitmap- or response-validated on the actual car — **no PID additions needed** for this
  ECU family.
- The implausible-value honesty gate works on real hardware (0167/0168/01A0 rejects).
- VIN decode (`MEX`, year `T`) is correct.
- Multi-ECU (` / `-joined) and spaceless compact-hex frame handling both work — the run's
  two-ECU responses decoded throughout (the only failure mode was the lag, §1).

## 6. Follow-ups for the next owner run

1. Re-run Discovery & Validation on the fixed build → the **0x00 block must now appear**
   for both ECUs.
2. Capture 2–3 more `019D` idle samples (AC on AND off) for the idle-anchor decision in §4.
3. If `0142` control-module voltage reads zero on the discovery path while the live poller
   shows ~12–14 V, note it in the export — the live recorder's voltage series (feeding the
   AC ripple detector) remains the trusted source for voltage.
4. **ECU identification + module census** (added 2026-09-17, see
   `vag-ecu-did-research-audit-2026-09-17.md` §4): record `0904`/`090A` verbatim, run the
   Coding Lab `22 F190` sweep across `7E0`–`7E7`, and read `F187`/`F189` at `7E0` and `7E1`.
   This settles Simos18-vs-MED17.1.27 (evidence points to Bosch MED17.1.27, `04C9060xx`) and
   finally names the second responder `7E9`.
   Correction to §2 above: the "(manual gearbox)" gloss on silent `01A4` was an inference and
   is wrong about the car (6-speed AQ250 torque-converter automatic). The honest reading is
   only that the engine ECU does not publish gear via `01A4`.
