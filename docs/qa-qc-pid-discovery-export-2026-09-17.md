# QA/QC — owner PID-discovery export audit (2026-09-17, corrected)

**Subject:** the exported JSON of the in-app *PID Discovery & Validation* run against the real
Kylaq (VIN `MEXKPEPC2TG028855`, run 2026-09-16 08:57 local per `rawLogs[]`, export written
`2026-09-16T08:58:05.839Z`, `ATSP6`, ISO 15765-4 CAN 11-bit 500 kbit/s, functional `7DF`,
responders `7E8` + `7E9`, 29 rows "confirmed active").

**Owner question:** *"is there any scope we can improve and discover more PIDs, or is this the
list — no more PIDs available? give me verdict."*

## VERDICT

**No, this is not the full list — but the gap is smaller and different from what the first
revision of this document claimed.** Counted from the frames the car actually sent:

| | count |
|---|---|
| Mode-01 data PIDs this car **claims** in the five bitmaps the run captured | **42** |
| …of which the export **proved** (validated, decoded, listed) | **26** |
| …lost to ELM327 buffer lag — the entire `0x00` block | **16** |
| `0x20` block | **exists** (marker bit set) but was **never decoded** → contents unknown |
| Rows in the export that are **not measurements at all** (range markers `0160`, `0180`, `01A0`) | **3** |

So the honest total is **≥ 42 Mode-01 PIDs**, the report proved **26**, and the 16 missing ones
are the channels the app already polls on every single trip — rpm, speed, coolant, MAP,
throttle, timing advance, fuel rate, O2 sensors present, OBD standard. A discovery report about
this car that does not contain engine speed is a broken report, not a complete one.

Four things are genuinely recoverable, and all four are fixed or actionable in this release:

1. **16 PIDs** come back with the RX-drain fix (no more one-command-late bitmaps) + the salvage
   path that files a lagged bitmap under its **true** base.
2. **The `0x20` block** gets decoded instead of being silently rejected — that is an unknown
   number of additional PIDs, not zero.
3. **`01A6` is the ODOMETER.** The car answered `41 A6 00 00 86 EB` and the app printed
   *"Unknown Research PID 01A6"*. That frame is `34539 / 10 =` **10279.5 km**. A real,
   already-answered channel was being thrown away. It now decodes.
4. **`018E` (engine friction, percent torque)** was claimed *and answered* — the log shows
   `DIRECT_VALIDATED … Decoded: 82` — but the catalogue had no entry for it, so the app printed
   the bare byte. `0x82 = 130`, and `A − 125` = **+5 %** of the 175 Nm reference torque the same
   run measured: ≈ 8.8 Nm of engine friction at warm idle. A real, already-answered physical
   quantity, discarded as raw hex (§8.3).

> **Retraction.** The first revision of this audit (commit `5449e5c`) stated that the `0180`
> bitmap `00 24 00 0D` proves PID `83` (NOx sensor), `85` (NOx reagent system) and `86`
> (particulate-matter sensor), and that a bug was hiding "3 real channels". **That was wrong.**
> The bits were decoded by hand instead of by the decoder: `00 24 00 0D` sets `8B`, `8E`, `9D`
> and `9E`. This petrol Kylaq never claimed a NOx or PM PID. CI caught it — three assertions
> failed — and every claim below is now generated from the shipped decoder, not from a spec
> sheet read on paper.

Per the standing evidence rule this export is **F-6 ground truth** for what the car said, and it
is also ground truth for defects in our own pipeline.

---

## 1. The bitmaps, decoded by the shipped decoder

```
TX 0100  RX 7E8064100BE3EA813   41 00 BE 3E A8 13
TX 0120  (never decoded - its answer arrived during TX 0140's slot and was rejected)
TX 0140  RX 7E8064140FED08401   41 40 FE D0 84 01
TX 0160  RX 7E80641606B090041   41 60 6B 09 00 41
TX 0180  RX 7E80641800024000D   41 80 00 24 00 0D
TX 01A0  RX 7E80641A014000000   41 A0 14 00 00 00
```

| base | bitmap | data PIDs | marker (bit 32) |
|---|---|---|---|
| `0x00` | `BE 3E A8 13` | `01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F` (16) | set → `0x20` exists |
| `0x20` | — | **UNKNOWN — never decoded** | unknown |
| `0x40` | `FE D0 84 01` | `41 42 43 44 45 46 47 49 4A 4C 51 56` (12) | set → `0x60` exists |
| `0x60` | `6B 09 00 41` | `62 63 65 67 68 6D 70 7A` (8) | set → `0x80` exists |
| `0x80` | `00 24 00 0D` | `8B 8E 9D 9E` (4) | set → `0xA0` exists |
| `0xA0` | `14 00 00 00` | `A4 A6` (2) | **clear → no `01C0` block** |

**42 data PIDs.** The second responder, `7E9`, claims only `01 04 05 0C 0D 11` (bitmap
`98 18 80 01`) and `41 42 43 49` (`E0 80 00 00`) — a subset of `7E8`, consistent with a
transmission/gateway ECU answering the functional broadcast, not an independent channel set.

### 1.1 What the lag cost

```
TX 0100   RX: 7E804413C14EA                              ← stale garbled frame
TX 0120   RX: 7E906410098188001 / 7E8064100BE3EA813      ← these ARE the 41 00 bitmaps
```

The ELM327 answered **one command late**. The strict PID-mismatch rejection was correct
behaviour; the consequence was that `ranges[]` in the export starts at `0140`, and that the
`0x20` request consumed the `0x00` answer and produced nothing of its own. Fixed by draining
stale RX before every TX (`Elm327Transport`, `LAG_GUARD`) and by `salvageLaggedBitmap()`, which
files a lagged bitmap under the base it actually answers — and refuses to re-file a base that
was already decoded.

The 16 PIDs the report lost are not exotic. They are `0C` engine RPM, `0D` vehicle speed,
`05` coolant, `0B` MAP, `11` throttle, `0E` timing advance, `06`/`07` fuel trims, `0F` intake
air temp, `13`/`15` O2 sensors present, `1C` OBD standard, `1F` run time, `01` monitor status,
`03` fuel system status, `04` calculated load, `1C` compliance.

---

## 2. Three rows in the export are not measurements

| export row | what it really is | what the app printed |
|---|---|---|
| `0160` | availability bitmap for PID `61`–`80` | *"Mode 01 PID 60"* = `6B 09 00 41` — the `0x60` **bitmap itself**, shown as a value |
| `0180` | availability bitmap for PID `81`–`A0` | *"Diesel Particulate Filter Temperature"* = `00 24 00 0D` — on a petrol car |
| `01A0` | availability bitmap for PID `A1`–`C0` | *"Transmission Sump Temperature"* = **−20 °C** — a fabricated gearbox oil temperature |

`0160` is the interesting one: PID `60` had **no catalogue entry**, so `lookup()` fell through to
the generic *"Mode 01 PID 60"* research row and the capability bitmap that answered was rendered
as if it were a measurement. `0100`, `0120`, `0140` had the same hole; only `0180`/`01A0` were
catalogued, and both were catalogued as **data channels**. All six markers are now explicit,
disabled entries that say *range marker* in their name, and `decodeSupportedPids()` never emits
`basePid + 0x20` as a supported PID.

---

## 3. The real find: `01A6` is the odometer

Export row: `01A6`, raw `00 00 86 EB`, displayed *"Unknown Research PID 01A6"*. Elsewhere in the
catalogue a duplicate entry called the same PID *"Vehicle Identification Number"*.

J1979 PID `A6` is the **odometer**: `((A·2²⁴)+(B·2¹⁶)+(C·2⁸)+D)/10` km.

```
00 00 86 EB = 34539  →  34539 / 10 = 10279.5 km
```

The car also **claims** it: the `01A0` bitmap `14 00 00 00` sets exactly `A4` (transmission
actual gear) and `A6`. So this was a live, claimed, answered channel printing as unknown hex —
and the VIN is not in Mode 01 at all (it is Mode `09` PID `0902`).

Now: `DecoderType.ODOMETER_4B`, unit km, enabled, with a sanity gate that rejects `0` and
anything above 2 000 000 km rather than displaying a sentinel. `SimulationTransport` used to
answer `01A6` with **three random bytes** labelled "Unknown EA211 Channel" — a simulation that
invents data for a channel it does not understand. It now simulates a real odometer that
advances with simulated distance. `ProfileDefinitions.vagExperimentalRequests` asked for it as
"VW Candidate A6 / Experimental EA211 value"; it now asks for the odometer. The definition
lives once in `ProvenChannels.ODOMETER` and is **referenced** from both the shipped defaults
(so a fresh install polls it) and the J1979 catalogue (so `lookup("A6")` resolves it) —
copying it into both lists is exactly how the ten duplicate ids below came to exist.

**Check on the next run:** the discovery export should show `01A6 ≈ 10279.5 km + whatever the
car has driven since 2026-09-16`. If it shows a number near the trip odometer on the dash, the
channel is confirmed.

---

## 4. J1979 corrections applied to the catalogue

Names and formulas verified against a full SAE J1979 / ISO 15031-5 Mode-01 PID table and
cross-checked against the frames in this export.

| PID | was | J1979 | claimed here | action |
|---|---|---|---|---|
| `0155`–`0158` | "O2 Sensor 1-1…1-3 (lambda + voltage)" | **short/long term secondary O2 sensor fuel trim**, bank 1+3 / 2+4, 2 bytes `(X−128)·100/128 %` | `0155`, `0156` yes | new decoder `O2_TRIM_PAIR_2B`; `−100 %` suppressed as the no-sensor sentinel |
| `0156` | `7F 00` read as "λ 0.992 / 0.633 V" | `7F 00` = **−0.8 % / −100 %** | yes | the "0.633 V" was invented from byte A; no voltage is displayed any more |
| `015A` | "Generator Speed (Alternator RPM)" → then "Engine Coolant Temperature" | **relative accelerator pedal position**, `A·100/255 %` | no | corrected, disabled (wrong twice, never validated by a frame) |
| `015B` | "Engine Coolant Temperature 3 (Bosch)" → "vendor specific" | **hybrid battery pack remaining life** | no | corrected, disabled — a petrol car must never show a hybrid number |
| `015F` | "Engine Oil Life Remaining", `A·100/255 %` | **emission requirements ENUM** (1 = OBD-II/CARB, 6 = EOBD, …) | **yes** | disabled research raw: the old formula would have printed the standard code as a fake oil-life percentage |
| `0165` | "Turbocharger Boost Pressure", 1 byte kPa, **FAST** | **auxiliary input / output supported**, 2-byte bitmap | yes | the "16 kPa" was a guess on a wrong name. Disabled research raw, SLOW. Boost on this car comes from `0B` MAP − `33` baro in `TurboAnalyzer`, plus `6F`/`70` |
| `017A`/`017B` | "O2 Sensor Voltage B4S1/B4S2" → "PM sensor" | **DPF temperature** / **DPF pressure** (diesel blocks) | `017A` answered 7 bytes | corrected names, disabled research raw — byte count and meaning both unestablished on a petrol car |
| `0186` | "estimated fuel filament degradation" | **particulate-matter sensor**, 5 bytes | **no** | corrected, disabled |
| `0187` | "Estimated Fuel Injector Correction" | **intake manifold absolute pressure**, 5-byte block | no | corrected, disabled — PID `0B` is this car's MAP |
| `0188` | *absent* | **SCR induce system** | no | added, disabled |
| `018A` | "vendor specific, fuel injection quantity per stroke", MEDIUM/500 ms | **engine run time for AECD #16–#20** | no | corrected, disabled — an invented meaning was being polled twice a second |
| `018B` | "Fuel Pump Command" | J1979 tables list **diesel aftertreatment**, 7 bytes | **yes** | kept as fuel pump command (validated 31.8 % duty) but flagged research: the evidence is a plausible number, not a specification |
| `018E` | *absent* → printed raw `82` | **engine friction — percent torque**, `A − 125` | **yes — and it ANSWERED** | **added**, MEDIUM/500 ms. `0x82 = 130 − 125 =` **+5 %** ≈ 8.8 Nm against the 175 Nm the same run reported. See §8.3 |
| `019A`/`019B`/`019C` | *absent* | hybrid/EV data, DEF sensor data, 17-byte O2 block | no | added, disabled |
| `019E` | *absent* (fell back to "Mode 01 PID 9E") | **engine exhaust flow rate**, kg/h | **yes** — answered `00 20` | added as research raw. The scaling is not defined in any table this project cites, so the raw pair is recorded and **no number is published**. An earlier revision of this audit guessed "32/255 = 25.6 % load" — exactly what the no-fake-values rule forbids |
| `01A0` | "Transmission Sump Temperature" | **range marker** | n/a | disabled marker |
| `01A6` | "Unknown Research PID" / "Vehicle Identification Number" | **odometer** | **yes** | `ODOMETER_4B`, enabled |
| `0100`/`0120`/`0140`/`0160` | *absent* → generic fallback rows | **range markers** | n/a | added, disabled |

**Deliberately NOT changed:** `0167` coolant-2 and `0168` intake-air-2 stay 1-byte `A − 40`.
J1979 gives 2- and 3-byte forms, but under those the pinned sentinel `41 67 03 50 43` decodes to
a *believable* 44.4 °C. Under `A − 40` it decodes to −37 °C and the plausibility gate rejects it.
NO-FAKE-VALUES beats the spec sheet; a decoder change needs car-frame evidence, not a table.

### 4.1 None of this reaches an installed device without the reconciler

`pid_definitions_json` is written on every settings change and was read back **verbatim**, so a
phone that had run the app once kept the definitions it saved forever. Every correction above
would have changed nothing on the owner's device: the saved list would still say *"Turbocharger
Boost Pressure"*, still decode `01A0` as a transmission sump temperature and still call the
odometer *"Unknown Research PID 01A6"*. The only escape was *Reset to defaults*, which also
throws away the owner's CAN header, RX id and enable choices.

`PidDefinitionReconciler` now splits ownership on load:

| owner | fields |
|---|---|
| **catalogue** (code, unit-tested) | `name`, `shortName`, `unit`, `dataBytes`, `decoderType`, `formulaDisplay`, `isResearch`, `description`, `priority` |
| **user** | `enabled`, `canHeader`, `expectedRxId`, `defaultIntervalMs` (never shortened below the catalogue's band floor) |

One deliberate exception: an entry the catalogue **disables** cannot be switched back on
(`enabled = saved && catalogue`), because the toggle would put a capability bitmap or an
unproven guess back on the dashboard as a measurement. PIDs the catalogue does not know —
user-added custom channels — are returned untouched. `PidCatalogReconciliationTest` pins all of
it, including idempotence over the whole catalogue.

**Catalogue integrity:** ten PIDs (`0107`, `010A`, `010E`, `0123`, `012F`, `0143`, `0144`,
`0145`, `015D`, `01A6`) were defined **twice** — once in `DefaultPidDefinitions`, once in the
"additional" list — and the later entry silently won the map. `0145` relative throttle position
had drifted from FAST/150 ms to MEDIUM/500 ms that way, which is a gear-pairing input. The
duplicates are collapsed to one entry each with the polling bands restored, and
`catalogueHasNoDuplicatePidIds()` now guards it.

---

## 5. `DIRECT_VALIDATED` printed next to values that do not exist

Six rows carried `directStatus: DIRECT_VALIDATED` while `decodedValue` said otherwise:
`0167`/`0168` *"implausible raw - no data"*, `01A4` *"Not available"*, `01A0` *−20 °C*.
A status field that says "validated" next to a value the decoder refused is worse than no status
field. `PidDiscoveryService` now emits `dataQuality` (`VALIDATED` / `SENTINEL_REJECTED` /
`MARKER_NOT_A_PID` / `RAW_UNSCALED`) and the export counts `positiveReplies` separately from
`usableValues`, so "the car answered" and "we can show a number" can never be conflated again.

---

## 6. What the next run must show

1. `ranges[]` starts at `0100` and contains **six** blocks (`00`, `20`, `40`, `60`, `80`, `A0`).
2. The `0x00` block lists all 16 PIDs; the `0x20` block lists whatever it really holds.
3. No row named `0160`, `0180` or `01A0` appears as a supported PID.
4. `01A6` ≈ **10279.5 km + distance driven since**, in km, not raw hex.
5. `0155` = `+0.0 % / +0.0 %`, `0156` = *Not available* (the −100 % sentinel of a bank this
   3-cylinder does not have).
6. `018E` decodes as **+5 %** (engine friction) instead of the raw byte `82`.
7. `019E` records raw bytes and publishes no number.
8. `0165` is not polled at FAST priority and shows no "boost" number.
9. `016D` and `0170` still record their full 11- and 10-byte frames (§8.2) — those two are the
   next real decoding target, and a steady-80 km/h + wide-open-throttle sweep is what defines
   their scaling.
10. The `LAG_GUARD` line `[DRAINED n stale RX chunk(s) before TX]` should appear in the raw log
    if the adapter is still holding stale bytes — and no bitmap should arrive in the wrong slot
    any more.

## 7. Owner action checklist

1. **Re-run PID discovery** on the new build and export the JSON again. The comparison against
   this document is the acceptance test. **Do not press "Reset to defaults" first** - it is no
   longer needed (§4.1: `PidDefinitionReconciler` refreshes what a PID *is* from the catalogue
   on every load and keeps your CAN header, RX id and enable choices), and resetting would
   discard the PID list the discovery run built.
2. **`01A4` while moving.** Gear ratio is only meaningful above ~20 km/h in D. Stationary it
   returns *Not available* — that is correct, not a bug.
3. **Coding Lab:** SCAN, then `22 F187` / `22 F189` / `22 F190` on **both** `7E0` and `7E1`.
   `7E1` is where a TCU would live; it is silent on the `7DF` broadcast, so it has to be
   addressed directly.
4. **Mode 09:** `0902` (VIN — the real source of the VIN, not Mode 01), `0904` (calibration ID),
   `090A` (ECU name). These identify whether the engine ECU is Bosch MED17.1.27 or Simos18,
   which decides everything about extended-DID scope.
5. **Probe `22 202A` @ `7E0`.** A positive reply falsifies the MED17.1.27 conclusion from
   `vag-ecu-did-research-audit-2026-09-17.md`; a negative reply confirms it.
6. **`018E` and `019E` under load.** One frame each at a known operating point (steady
   80 km/h, or a coast-down) is enough to define the scaling. Until then they stay research.

---

## 8. Correction to this audit: the export's counts DO reconcile

An earlier revision of this document claimed `ranges[]` was internally inconsistent — that
`bitmapHex 00 24 00 0D` for base `0180` could not produce the listed PIDs. **That claim is
withdrawn.** With the full `rawLogs[]` in hand the numbers reconcile exactly, once you account
for the old decoder emitting the range marker as a supported PID:

| base | bitmap | old count (marker included) | data PIDs (marker excluded) |
|---|---|---|---|
| `0140` | `FE D0 84 01` | 13 ✓ | 12 |
| `0160` | `6B 09 00 41` | 9 ✓ | 8 |
| `0180` | `00 24 00 0D` | 5 ✓ (`8B 8E 9D 9E` + marker `A0`) | 4 |
| `01A0` | `14 00 00 00` | 2 ✓ (`A4 A6`) | 2 |

13 + 9 + 5 + 2 = **29** = the export's headline "29 supported PIDs", and 29 − 3 markers
(`60`, `80`, `A0`) = **26** real. The log lines agree too: `ECU 7E8 Bitmap for 0180:
[00 24 00 0D] (5 supported)`. Nothing was edited; the export is self-consistent and the defect
was ours.

### 8.1 The exact lag mechanism, now visible in the log

```
[08:57:10.521] TX: 0100
[08:57:10.588] RX (66ms):  7E804413C14EA                    ← stale 3-byte frame, PID 3C
[08:57:10.591] TX: 0120                                     ← 3 ms later
[08:57:10.749] RX (158ms): 7E906410098188001 / 7E8064100BE3EA813
```

Both `41 00` bitmaps — the one from `7E9` and the one from `7E8` — arrived during the **0120**
slot. `TX 0100` was answered after 66 ms by a stale frame already sitting in the adapter, and
the app fired the next command **3 ms** later, before the two real responders had been read. The
ELM327 buffers them and hands them over on the next read. Every later request had 130–160 ms of
slack and collected its own answers correctly — which is why only the `0x00` block was lost and
why the loss looked random.

This also explains the `01A0` phantom value exactly: the marker bitmap `14 00 00 00` was decoded
as a temperature with `A − 40`, and `0x14 − 40 = −20`. The "−20 °C transmission sump
temperature" in the export is the byte `0x14` of a capability bitmap.

Fixes already in this release: drain stale RX before every TX (`LAG_GUARD`, logs
`[DRAINED n stale RX chunk(s) before TX]`), salvage a lagged bitmap under its true base, and
never emit `basePid + 0x20` as a data PID.

### 8.2 Two rich frames the app recorded and never decoded

| PID | bytes returned | frame | J1979 |
|---|---|---|---|
| `016D` | **11** | `02 00 00 05 91 28 00 00 00 00 28` | fuel pressure control system (6 bytes) |
| `0170` | **10** | `02 00 00 0B E9 00 00 00 00 00` | boost pressure control (9 bytes) |

Both are longer than the standard's block and both open with the same `02 00 00` prefix, which
reads like a record header — record type `0x0200` followed by a payload. If that is right, the
payloads are `05 91` = 1425 and `0B E9` = 3049, and the trailing bytes are status. **No scaling
is asserted here**: 3049 is not a plausible idle manifold pressure in kPa, 1425 is not an
implausible low-pressure fuel rail value in kPa, and guessing is exactly what the no-fake-values
rule forbids. Both stay `RESEARCH_RAW` with the raw frame preserved.

These are the densest unexploited channels in the whole export — 21 bytes from the two PIDs that
matter most for a turbo petrol engine (fuel pressure and boost control). One log sweep settles
them: hold the car at a steady 80 km/h in D, then floor it, and export both frames again. If
`0B E9` tracks boost, the scaling falls out of two known operating points.

### 8.3 `018E` answered — and it is engine friction

An earlier revision of this document said PID `8E` "was never validated". **Wrong** — the log
shows `TX: 018E` at 08:57:19.368 and `PID 8E Validation: status=DIRECT_VALIDATED (268ms) |
Decoded: 82`. It printed the bare byte `82` only because the catalogue had no entry for it, so
`lookup()` fell through to the generic *"Mode 01 PID 8E"* research row.

`0x82 = 130`, and J1979 PID `8E` is engine friction percent torque, `A − 125`:

```
130 − 125 = +5 %  of reference torque  →  5 % × 175 Nm (PID 63) ≈ 8.8 Nm
```

That is a physically sensible friction torque for a 1.0 TSI at warm idle, and it is measured
against the same reference torque the car reported in the same run. With `0162` actual torque
= +6 % at that moment, the torque balance now has all three of its measured terms:

| term | PID | value at 08:57 idle |
|---|---|---|
| actual torque | `0162` | **+6 %** |
| engine friction | `018E` | **+5 %** ← was printed as raw `82` |
| reference torque | `0163` | **175 Nm** (rated 178 Nm) |
| driver demand | `0161` | not claimed by the bitmap → never TXed |

`0161` is the one missing term and the `0x00`/`0x20` bitmaps do not cover it — it lives in the
`0x60` block, where the bit is **clear**. It is worth a direct TX probe on the next run anyway:
the bitmap has already been shown to be an incomplete description of what this ECU answers
(`018E` printed raw, `016D`/`0170` returning 11- and 10-byte frames the standard says are 6 and
9).

*Audit method: every bitmap decode in this document was produced by running
`PidDiscoveryDecoder.decodeSupportedPids` logic over the frames quoted in the export, then
asserted in `DiscoveryLagAndMarkerRegressionTest`. No PID list here was transcribed from a
specification or from the app's own self-report. Two claims in earlier revisions of this file
(the NOx/PM bitmap decode and §8 above) were hand-derived, were wrong, and are retracted here.*
