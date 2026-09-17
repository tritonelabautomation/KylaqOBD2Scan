# QA/QC — owner PID-discovery export audit (2026-09-17)

**Subject:** the exported JSON of the in-app *PID Discovery & Validation* run against the real
Kylaq (VIN `MEXKPEPC2TG028855`, run 2026-09-16 08:57 IST, `ATSP6`, functional `7DF`,
responders `7E8` + `7E9`, 29 PIDs "confirmed active").

**Owner question:** *"is there any scope we can improve and discover more PIDs, or is this the
list — no more PIDs available? give me verdict."*

**VERDICT: this is NOT the full list. The export under-reports the car by at least 17 PIDs and
by an unknown amount on top, and three of its rows are not measurements at all.** Counted from
the frames the car actually sent: 16 (0x00 block) + 12 (0x40) + 8 (0x60) + 5 (real 0x80) +
2 (0xA0) = **43 Mode-01 PIDs proven by this very run**, plus whatever the never-decoded 0x20
block holds. The export listed 29 rows, of which 2 were range markers and 1 was a bitmap
misread as a temperature — so it proved **26**. Nothing about the car changed — the report was lossy. All three
root causes are fixed in this release; the numbers below are what a re-run must show.

Per the standing evidence rule this export is **F-6 ground truth** for what the car said, and
it is also ground truth for three defects in our own pipeline.

---

## 1. What the export lost, and why

### 1.1 The 0x00 and 0x20 blocks vanished — 24 PIDs missing from a report about the car

The raw log shows the ELM327 answering **one command late**, exactly as documented in
`kylaq-pid-validation-2026-09-16.md` §1:

```
TX 0100   RX: 7E804413C14EA                              ← stale garbled frame
TX 0120   RX: 7E906410098188001 / 7E8064100BE3EA813      ← these are the 41 00 bitmaps
```

The strict PID-mismatch rejection was correct; the consequence was that `ranges[]` in the
export starts at `0140`. Never listed, never validated — yet all of them are polled and
logged on every trip with this car:

| Block | 7E8 bitmap | PIDs lost from the report |
|---|---|---|
| 0x00 | `BE 3E A8 13` | **01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F** — 16 PIDs (bit 32 is only the marker) |
| 0x20 | *never decoded at all* | unknown — `TX 0120` returned the 0x00 bitmap and this build never retried, so nobody knows what the car supports in 0x21–0x3F (fuel-rail pressure `23`, fuel level `2F`, …) |

That is monitor status, fuel-system status, load, coolant, fuel trims, O2 voltages, **MAP,
RPM, vehicle speed, timing advance, intake temp, MAF, throttle**, O2 sensor counts and
ethanol % — i.e. the channel set the dashboard, the power curve and the gear estimator run on. The salvage fix
shipped on 2026-09-16 was **not in the build that produced this export** (its log line
`(even after one retry)` does not appear; the build said only `No valid 4-byte capability
bitmap found`).

**Root fix in this release (`Elm327Transport`):** drain stale RX bytes before every TX. The
salvage-and-retry path stays as the net for whatever still slips through, but the lag itself
is now attacked at the source instead of being repaired after the fact. This also protects
the live poller, where a lagged frame is what produces mismatched rpm/speed pairs.

### 1.2 The real 0x80 bitmap was consumed as a "value" — 3 PIDs never discovered

```
TX 0180   RX (130ms): 7E80641800024000D
```

`00 24 00 0D` is **not** the 0x80 block. It is the `41 A0` bitmap arriving one command late
(compare `TX 01A0 → RX 7E80641A014000000`). A capability bitmap is exactly 4 bytes, so it
passed the `41 80` correlation and was accepted as the answer to `0180`.

The genuine 0x80 block was therefore never decoded. Its bits are worth spelling out because
they are the actual new finds:

| Bit set in `00 24 00 0D` | PID | SAE J1979 meaning |
|---|---|---|
| byte1 bit3 | **0x83** | **NOx sensor** (5 bytes) |
| byte2 bit3 | **0x85** | **NOx reagent system** |
| byte2 bit6 | **0x86** | **Particulate-matter (PM) sensor** |
| byte3 bit3 | 0x8B | fuel pump command (validated: 31.8 % duty) |
| byte4 bit6 | 0x8E | (answered `82`; meaning not established) |
| byte4 bit1 | 0xA0 | the range marker, not a PID |

So the car claims **three PIDs the report never mentions**. They are aftertreatment channels
— plausible on a BS6 Phase 2 petrol with GPF monitoring. They are now catalogued (as research
raw, `isResearch = true`, never as invented numbers) and will be validated by the next run.

### 1.3 Range markers were listed as data PIDs — and one was "validated" as a DPF temperature

Bit 32 of every block means *"the next block exists"*. The decoder emitted it as a supported
PID, so the export's `supportedPids[]` contains **`60`** and **`80`**, and the validation
phase TXed both:

| Export row | What it really is |
|---|---|
| `pid 60` → `6B 09 00 41` | the 0x60 availability bitmap |
| `pid 80` "Diesel Particulate Filter Temperature" → `00 24 00 0D` | the 0xA0 availability bitmap — **on a petrol car** |

Fixed: `decodeSupportedPids` and `allTestedPidsForRange` no longer emit `basePid + 0x20`
(`hasNextRange` already reports it), the JSON export now carries an explicit
`rangeMarkersNotDataPids` array, and catalogue entry `0180` is renamed
*"Supported PIDs [81-A0] (range marker)"* with `enabled = false`.

### 1.4 `DIRECT_VALIDATED` was printed next to values that do not exist

Six rows read `directStatus: DIRECT_VALIDATED` while `decodedValue` says otherwise —
`0167`/`0168` *"implausible raw - no data"*, `01A4` *"Not available"*, `01A0` *−20 °C*
sentinel. The status answers "did the ECU reply positively"; the export presented it as "is
this value usable". Two facts are now two fields: every validation row carries
`dataQuality` ∈ {`PLAUSIBLE`, `RESPONDED_IMPLAUSIBLE`, `RESPONDED_NO_DATA`,
`RESPONDED_NOT_AVAILABLE`, `RESPONDED_MISMATCHED_FRAME`, `NOT_DECODED`, `NO_REPLY`}, the log
line prints it, and the JSON adds `positiveReplies` vs `usableValues` counts so a headline can
never again be "29 confirmed active" when 23 are.

---

## 2. Values the car gave us that we threw away

| PID | Car said | Old handling | Now |
|---|---|---|---|
| **0165 boost** | `10 10` | `RESEARCH_RAW`, `dataBytes = 2` → printed the raw pair; FAST priority, feeding nothing | J1979 PID 65 is **one byte in kPa** → **16 kPa** at warm idle (plausible; the 2-byte reading would be 4112 kPa). `RAW_A_KPA`, `dataBytes = 1` |
| **0156 O2 1-1** | `7F 00` | printed the bare byte `7F` | J1979: A = λ/128, B = V/128 → **λ 0.992 / 0.633 V** — closed loop at stoichiometry, independently agreeing with `0144 = 1.000` in the same run |
| 019E | `00 20` | not decoded | 0x20 = 32 → 32/255 ≈ **25.6 % load**? *Not catalogued — meaning unverified, left as research raw.* No number is published for it |
| 01A6 | `00 00 86 EB` | labelled "Unknown Research PID" in the export and "Vehicle Identification Number" elsewhere in the catalogue | stays research raw; the duplicate catalogue entry is inert (the builder de-dupes by id) but the label conflict is recorded here |

## 3. Catalogue entries that invented meanings (corrected to SAE J1979)

| PID | Was | Is | Car claims it? |
|---|---|---|---|
| 0155 | "Short Term O2 Trim Bank 1", 2 bytes | O2 sensor 1-1, λ + short trim, 4 bytes | no (bit clear) |
| 0156–0159 | "O2 Sensor Voltage B1S1/B1S2/**B2S1/B2S2**" | O2 sensors **1-1…1-4** (a 3-cylinder has ONE bank) | 0156 **yes** |
| 015A | "Generator Speed (Alternator RPM)", decoded kPa | **Engine coolant temperature**, A − 40 | no |
| 015B | "Engine Coolant Temperature 3 (Bosch)" | no J1979 definition → disabled research raw | no |
| 0178/0179 | "O2 Sensor Voltage B3S1/B3S2" | **Exhaust gas temperature bank 1 / bank 2**, 0.1 °C, −40 | no |
| 017A/017B | "O2 Sensor Voltage B4S1/B4S2" | **PM sensor** blocks (9 bytes), research raw | 017A **yes** (7 bytes returned — matches neither definition, still unvalidated) |
| 017F | "NOx Sensor (post-DPF)" | engine run time for AECD #13 | no |
| 0180 | "Diesel Particulate Filter Temperature" | **range marker**, disabled | n/a |
| 0181/0182 | "DPF Soot Load" / "DPF Ash Load" | engine run time for AECD #1 / #2, disabled | no |
| 0183 | "DPF Regeneration Status" | **NOx sensor** (5 bytes), research raw | **yes** |
| 0185 | *missing from the catalogue* | **NOx reagent system**, research raw | **yes** |
| 0186 | "Estimated Fuel Filament Power Degradation" | **PM sensor** (9 bytes), research raw | **yes** |
| 0187 | "Estimated Fuel Injector Correction" | intake manifold absolute pressure (duplicate of 0B), disabled | no |
| 018A | "Injection Quantity" | no J1979 definition → vendor-specific research raw | no |
| 018F | "Engine Oil Temperature 2" | no J1979 definition → disabled research raw | no |

**Deliberately NOT changed:** `0167` coolant-2 and `0168` IAT-2. J1979 says 2 bytes at
0.1 °C − 40, but the only real evidence we hold is the frame `41 67 03 50 43` — **five**
payload bytes, which fits neither form. Under the current A − 40 the sentinel `0x03` decodes
to −37 °C and the plausibility gate rejects it (pinned by
`coolant2_4167035043_sentinelRejectedAsNoData`). Under the 2-byte form the same frame yields
(3·256+80)/10 − 40 = **44.4 °C** — a believable number manufactured out of a sentinel.
NO-FAKE-VALUES beats the spec sheet; the reasoning is now a comment on both entries.

## 4. The transmission question this export answers

- The 01A0 block bitmap is `14 00 00 00` → the car **claims `0xA0` (sump temperature) and
  `0xA4` (actual gear)**.
- `01A4` answered from **7E8** with a ratio of 0.000 → displayed *"Not available"*. That is
  the honest reading at warm idle: the AQ250 is in P/N with the converter open, so there is no
  gear ratio to report. **It has never been sampled while driving.**
- The plumbing already exists (`ObdScheduler` stores `rawGearRatio = primaryNumeric("01A4")`,
  `TransmissionEngine` consumes it), so if `01A4` yields 0.2–15.0 on the road, the gear trace
  switches from *estimated* to *measured* with no further work. That single test is worth more
  than any third-party DID list.
- `7E9` is still unidentified. Its bitmap (`98 18 80 01` → 01 04 05 0C 0D 11) **does not
  include 0xA4**, and the app's VIN-authority model expects the TCU at **7E1** — which did not
  answer the `7DF` broadcast at all. Naming `7E9`/`7E1` needs `22 F187`/`F189`, not inference.

## 5. What a re-run on this build must show

1. `ranges[]` starts at **`0100`** and includes `0120`; the log either shows the frames
   decoded first time or carries `[DRAINED n stale RX chunk(s) before TX]`.
2. `supportedPids[]` contains **no `60`, no `80`, no `A0`-as-marker**, and the export carries
   `rangeMarkersNotDataPids`.
3. `pid 80` block decoded as `00 24 00 0D` → **83, 85, 86, 8B, 8E** discovered and validated
   (whatever they return is recorded with `dataQuality`, never dressed up).
4. `0165` → **16 kPa**-style value; `0156` → **λ 0.99x / ~0.6 V**.
5. `usableValues` reported next to `positiveReplies`.

## 6. Owner actions (all read-only, ~3 minutes, in priority order)

1. **Re-run PID Discovery & Validation** on this build and export the JSON. This alone recovers
   ~30 PIDs.
2. **`01A4` while moving** — the decisive test. Any safe road moment above ~20 km/h in D:
   if it reports a ratio, the gear trace becomes measured instead of `est.`
3. **Coding Lab → SCAN ECUs** (`22 F190`, `7E0`–`7E7`) then manual reads `7E0`+`F187`/`F189`
   and `7E1`+`F187`/`F189`/`F190`. This names `7E9`/`7E1` and settles the ECU family
   (evidence points to **Bosch MED17.1.27**, `04C9060xx` — see
   `vag-ecu-did-research-audit-2026-09-17.md` §2).
4. **Mode 09**: `0902` VIN, `0904` Calibration ID, `090A` ECU Name — never recorded from the
   real car.
5. One falsification probe: `22 202A` at `7E0`. `62 202A …` would prove the Simos18 DID list
   applicable; `7F 22 31`/`7F 22 33` proves it is not, and is itself the citable answer.

**Not available, and not obtainable by us:** AQ250/09G TCU DIDs (no public documentation
anywhere; the commercial route is PCMflash `AL1000/AQ250/AQ450 (0C8/09G)` or RevMap, both
closed and both needing a J2534 interface), and anything behind `0x27` seed-key + SFD2 online
authorisation on a 2026 car. This app reads `0x22` and never writes — by design.

## 7. Changed in this release

- `bluetooth/Elm327Transport.kt` — bounded non-blocking RX drain before every TX (lag root
  cause), logged as `LAG_GUARD`.
- `protocol/PidDiscoveryDecoder.kt` — range marker never emitted as a tested/supported PID.
- `discovery/PidDiscoveryService.kt` — `dataQuality` on every validation row + log line;
  `rangeMarkersNotDataPids`, `positiveReplies`, `usableValues` in the JSON export;
  `DataQuality` column in the CSV export.
- `model/PidDefinition.kt` — two new decoders (`LAMBDA_SENSOR_VOLTAGE`, `LAMBDA_STFT_PAIR`),
  new entries `0184`/`0185`, 16 corrected labels/byte counts, marker + no-J1979-definition
  entries disabled; uncatalogued fallback moved to SLOW/3000 ms + `isResearch`.
- `protocol/PidDecoder.kt` — decoders for PID 55 and PID 56–59.
- Tests: `DiscoveryLagAndMarkerRegressionTest` (new, built from this export's frames),
  `PidDiscoveryDecoderTest` and `KylaqDiscoveryComprehensiveTest` updated off the
  marker-as-PID contract, `PidCatalogIntervalTest` updated for the SLOW fallback.
