# Kylaq OBD2 Scan — Bug Fix Summary

**Date:** 2026-09-07
**Branch:** `arena/01a07c37-kylaqobd2scan`
**Ground truth:** raw ELM327 capture from a working build of this app, cross-checked against
`app/src/test/resources/traces/kylaq/f39f1ebd_raw.txt` (Škoda Kylaq 1.0 TSI, EA211, 2026-09-05).

---

## 1. What the reference capture proves

The log is the specification. Everything below was derived from it, not assumed:

| Observation in the log | Consequence for the code |
|---|---|
| `ATSH 7E1` / `ATSH 7E0` then `01xx` | header switching must be cached, re-sent on change, and reset when polling (re)starts |
| poll order `010C 010D 0111 [0142]` on 7E1, `010B 0105 010F` on 7E0, periodic `0902` | the scheduler's priority tiers must actually run in this order |
| **every** request is answered by `7E8` **and** `7E9` (`7E804410C0F28` + `7E904410C0F34`) | multi-ECU: keep both samples, display one deterministic primary |
| `7E810144902014D4558` / `7E8214B504550433254` / `7E82247303238383535` | ISO-TP multi-frame VIN = `MEXKPEPC2TG028855`, 20 bytes, first frame **not** duplicated |
| one `[TIMEOUT / NO RX]` after `010B`, polling continues | a timeout is a transport failure — never "PID not supported", never a dead dashboard |
| unsolicited coolant frames inside a `010C` window | frames whose echoed PID ≠ requested PID must be ignored, not decoded |

Decodes used as fixtures: `7E804410C0F28`→970 rpm, `7E904410C0F34`→973 rpm, `7E803410D00`→0 km/h,
`7E803410B24`→36 kPa, `7E80341056D`→69 °C, `7E803410F51`→41 °C, `7E8044142337C`→13.18 V,
`7E903411125`→14.5 %.

---

## 2. Bugs fixed

### P0 — dashboard showed "Not available" for every live PID

**Symptom.** Frames were flowing, decoders were correct, yet the whole dashboard rendered
`Not available` / `--`, driving state stayed `UNKNOWN`, fuel economy and gear estimate stayed `—`.

**Root cause.** A previous "per-ECU telemetry isolation" change made `ObdScheduler.updateTelemetry()`
publish **only** composite keys (`"7E8_010C"`). Every consumer reads plain PID keys:
`TelemetryDashboardContent`, `DrivingDashboardScreen`, `AiDoctorScreen`, `PidDetailScreen`,
Android Auto's `ObdDashboardScreen`, the AI diagnostic context and
`ObdScheduler.onTelemetrySignalUpdated()`. `liveDecodedMap["010C"]` was permanently `null`, and the
header counter reported 19 "active PIDs" for 7 real ones (7 ECU-suffixed + 12 plain leftovers).

**Fix.** New `scheduler/LiveTelemetryStore.kt` — single source of truth keeping **both** views:

* `telemetryMap` — ECU-aware (`"ECU_PID"`), one entry per responding ECU, so 7E8 and 7E9 never
  overwrite each other;
* `decodedMap` / `numericMap` — plain PID keys for the UI and the powertrain engines, always sourced
  from one deterministic primary ECU.

Primary selection: quality tier → preferred ECU (capability evidence) → engine ECU `7E8` →
alphabetical ECU id. Quality tiers are `fresh+valid` > `fresh failure marker` > `stale value` >
`stale marker`, which yields the behaviour the log demands:

* arrival order is irrelevant (`7E9` then `7E8`, or the reverse → same displayed value, no jitter);
* a timeout on the preferred ECU does **not** blank a PID the secondary ECU still answers;
* when the preferred ECU goes stale, the still-answering secondary takes over;
* when everything is stale, the display says `Not available (stale)` and the numeric value is
  withdrawn rather than served as if it were live.

`ObdScheduler` now delegates all telemetry writes/reads to the store (`primaryNumeric()`), resets
`currentCanHeader` on start/stop/reset, and `resetCounters()` clears every view.

### P1 — capability bitmaps silently dropped when a data byte was `7F`

`PidDiscoveryDecoder.extractBitmapsByCanId()` skipped any line *containing* the substring `"7F"`.
A perfectly valid Kylaq bitmap — `7E8 06 41 00 BF BF 7F 00` — contains `7F` as **data**, so the whole
ECU's capability bitmap was discarded and every PID looked unsupported.

**Fix.** Structural test `isNegativeResponseLine()`: a line is a negative response only when its
payload starts with `0x7F` **and** the second byte is a known service id (0x01–0x0F, 0x22, 0x3E).
`decodeAllEcuResponses()` now attributes negative responses to the CAN id that actually sent them
(it used `startsWith("7F")` on the raw line, which blamed every positive ECU).

### P1 — DTC scan decoded nothing

`ScanCoordinator` fed raw ELM327 lines straight into `DtcDecoder.extractDtcs()` without ISO-TP
reassembling, in three separate copy-pasted blocks, so multi-frame DTC responses were never decoded.

**Fix.** One `collectDtcs()` helper: `IsoTpParser.reassembleLines()` → `extractDtcs()` per message,
with a per-line raw fallback (never all lines glued together — two ECUs answering would otherwise be
decoded as one long payload). `MainViewModel` uses the same path.

**Second bug found while verifying the first:** `DtcDecoder.extractDtcs()` ignored the SAE J1979 DTC
**count byte**. A real answer to `>03` is `7E8 06 43 02 01 04 01 08` = ack `43`, **count `02`**, then
two DTCs (`P0104`, `P0108`). Decoding the body as a flat pair list starts at the count byte and
invents codes (`P0201`, `P0401`, …). The decoder now strips the count when it is self-consistent
(count exactly accounts for the remaining bytes, or accounts for a prefix with `00` frame padding
after it) and otherwise keeps the old count-less behaviour that fixtures and some callers rely on.
Prefix offsets were corrected at the same time — the ISO-TP PCI byte is **two** hex characters, so the
tolerated prefixes are `0 / 2 / 3 / 5 / 8 / 10` (payload, PCI, 11-bit id, 11-bit id + PCI, 29-bit id,
29-bit id + PCI), not `0 / 3 / 4 / 8 / 9`. All 31 mirror cases pass
(`tools/trace_sim/dtc_mirror.py`), including every pre-existing fixture.

### P1 — negative responses leaked into the dashboard as values

`PidDecoder`'s permissive research-PID fallback rendered `7F 01 11` as a live raw value.
**Fix.** `0x7F` in the first payload byte returns `INVALID_RESPONSE` before any decoding strategy runs.

### P1 — late / unsolicited frames poisoned telemetry

The capture shows coolant frames (`7E90341056F`, `7E80341056F`) arriving inside a `010C` read window.
Decoding them as engine speed returns `INVALID_RESPONSE`, which — once published — overwrote the good
sample for that ECU and blanked the dashboard.

**Fix.** `PidDefinition.isLateFrameForOtherPid(bytes)` (positive mode-01 response whose echoed PID
differs from the requested one) + a guard in `ObdScheduler`: such frames are recorded as
`ResponseStatus.IGNORED_LATE_FRAME` and skipped — they never touch telemetry or capability state.
`PidDiscoveryService` already filtered on the expected service/PID, so it was immune.

### P2 — remaining fixes

| # | Bug | Fix |
|---|---|---|
| 1 | `BluetoothManager` required the literal `ATSP6`; any other user-selected protocol (`ATSP0/7/8/9`) reported "Adapter failed initialization: ATSP6 failed" | accepts any `ATSP*` command; a sequence without one is not a protocol failure; the message names the command that actually failed |
| 2 | `VinDecoder` had no WMI for `MEX`, so this very car's VIN decoded to `manufacturerCandidate = null` | added `MEX`→Škoda (plus `MGR`/`MGF`→MG, `MCB`/`MC2`→Renault, `MAT`→Tata Motors) and accent-insensitive `brandMatches()`, because the catalog stores "Škoda" while the decoder returns ASCII "Skoda" |
| 3 | `SettingsRepository` never persisted/restored the polling `priority`, and one corrupt enum value (`DecoderType.valueOf` throwing) discarded **all** user PID customisations | priority is persisted and restored; `parseDecoderType()`/`parsePriority()` fall back to the shipped default per PID |
| 4 | Mojibake ("Å koda") in `ScanCoordinator` and `MainViewModel` | restored to "Škoda Kylaq" |

### Test data that could never pass

* `DtcDecoderAdversarialTest` — `C0030` expected from bytes `80 30` (SAE J2012 bits 7..6 = `10` → **B**, so `B0030`); a mode-07 fixture labelled mode 03; `7F` expected to decode to a C-code. Corrected, plus **10 new tests**: U-code, mode mismatch, and the real count-byte wire format (single/multiple DTCs, raw ELM line with CAN id + PCI, spaces, frame padding, mode 07 with three pending codes, mode 0A ack `4A`).
* `KylaqRealWorldTraceIntegrationTest.testIsoTpVinFirstFrameNotDuplicated` — expected hex was 21 bytes with `43 54 32 54` instead of the real 20-byte `4902014D45584B50455043325447303238383535`; it now also asserts the VIN string.
* `VinDecoderValidationTestB.testDifferentCanIds_NotCombined` — asserted one complete VIN and called `.first()` on an empty list (`NoSuchElementException`); it now asserts 0 complete VINs and the malformed flag, which is the actual fail-closed intent.

### Infrastructure — why broken tests shipped

`.github/workflows/build-apk.yml` only ran `gradle assembleDebug`; unit tests were never executed.
A new `unit-tests` job runs `gradle testDebugUnitTest --continue`, uploads the HTML/XML reports and
reports its own commit status context (`ci/unit-tests`), and the workflow now also triggers on pull
requests. It is deliberately **not** a hard gate yet — add `needs: unit-tests` to the `build` job once
the suite is confirmed green in CI.

---

## 3. Files changed

New: `app/src/main/java/com/example/scheduler/LiveTelemetryStore.kt`,
`app/src/test/java/com/example/LiveTelemetryStoreTest.kt` (19 tests),
`app/src/test/java/com/example/KylaqTraceReplayTest.kt` (6 tests).

Modified: `ObdScheduler`, `ScanCoordinator`, `PidDecoder`, `PidDiscoveryDecoder`, `DtcDecoder`,
`VinDecoder`, `PidDefinition`, `TransactionRecord`, `SettingsRepository`, `BluetoothManager`,
`MainViewModel`, `.github/workflows/build-apk.yml`, and the three test files listed above.

`protocol/VinAuthority.kt` was **not** touched: `AUTHORITATIVE_ECUS = ["7E8", "7E1"]` is asserted by
`VinAuthorityTestA/B` and is intentional (VIN comes from the engine ECU / diagnostics requester, not
from every responder).

---

## 4. How this was verified (no JDK in the workspace)

The sandbox has no JDK, no Android SDK and no route to Maven Central / Google / GitHub release
assets, so `./gradlew test` cannot run here. Verification was therefore:

1. **Trace replay harness** — `tools/trace_sim/` contains a faithful Python port of
   `CanFrameParser`, `IsoTpParser`, `PidDecoder`, `PidCapabilityManager`, `DtcDecoder`
   (`dtc_mirror.py`, 31/31 cases green) and the new store semantics
   (`port.py`, `replay_trace.py`, `store_semantics.py`). Replaying the real capture:
   50 transactions → 52 decoded frames, 1 timeout, 0 malformed, 18 `ATSH`; shipped code renders all
   seven dashboard PIDs as "Not available", the fixed code renders RPM 976 / speed 0 / throttle 14.5 %
   / MAP 35 / coolant 69 / IAT 41 / 13.34 V. Replaying the bundled fixture through the *new* store
   semantics yields the end state asserted by `KylaqTraceReplayTest`: 33 PID queries, 50 published
   samples, 4 `NO DATA`, 2 ignored late frames, `010C` 977.5 rpm (7E9 carrying after 7E8 stopped
   answering), `010B` 35 kPa, `0105` 70 °C, `0142` 13.16 V, `010F` "Not available", 7 plain keys.
2. **Structural checker** — `tools/check_kotlin_structure.py` validates brace/paren/bracket balance
   and string/comment nesting for every Kotlin file: **109 files, 0 problems**.
3. **Line-by-line tracing** of each fix against the captured frames.

This is not a substitute for compiling. Before merging, run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The two new suites are pure JVM (no Robolectric), so they run in seconds and give a fast signal on
the store and on the trace-replay expectations.
