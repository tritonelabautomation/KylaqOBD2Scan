# QA/QC Audit — Fuel PIDs & Dashboard Fuel Lineage (2026-09-13)

**Requested by owner:** "Do a detailed QA & QC regarding fuel PID's etc in Dashboard etc do a detailed analysis."
**Method:** Karpathy-skills discipline (think-before-coding, surgical changes, goal-driven with verify checks) +
the standing **lineage checklist** from the 2026-09-12 post-mortem: every displayed fuel metric must prove
(a) source PID → (b) poll/validation path → (c) decoder math vs J1979 → (d) stored-key format match → (e) loud empty state.
Spec sources: SAE J1979 / ISO 15031-5 PID table (Wikipedia OBD-II PIDs, fetched 2026-09-13), J1979 table mirror
(slideshare/pdfcoffee), everything.explained.today PID table.

## 1. Scope — the fuel family rendered on the Dashboard (TelemetryDashboardContent.kt:198-206) + trims

| PID | Tile label | Unit | J1979 formula | Repo decoder | Verdict |
|---|---|---|---|---|---|
| 015E | Engine Fuel Rate (Volume) | L/h | (256A+B)/20 | `FUEL_RATE_20` ÷20 | ✅ correct |
| 019D | Engine Fuel Rate (Mass) | g/s | (256A+B)/10 (4 data bytes, C/D reserved) | `FUEL_RATE_MASS_10` ÷10 | ✅ catalog correct — **profile diverged (F-1)** |
| 010A | Fuel Pressure (Low Gauge) | kPa | 3A | `FUEL_PRESSURE_3_KPA` | ✅ correct |
| 0123 | Fuel Rail Pressure (Direct Inj) | kPa | 10×(256A+B) gauge | `FUEL_RAIL_PRESSURE` ×10 | ✅ formula correct — **profile description wrong (F-3)** |
| 015D | Fuel Injection Timing | ° | (256A+B)/128 − 210 | `INJECTION_TIMING_128` ((raw−26880)/128) | ✅ algebraically identical (26880/128=210) |
| 012F | Fuel Tank Level | % | 100A/255 | `PERCENT_255` | ✅ correct |
| 0151 | Fuel Type | enum | table | `FUEL_TYPE_ENUM` (1=Gasoline…) | ✅ correct |
| 0152 | Ethanol Fuel % | % | 100A/255 | `PERCENT_255` | ✅ correct |
| 0103 | Fuel System Status | bitfield | A bit-encoded (1/2/4/8/16) | `FUEL_SYSTEM_STATUS` | ✅ correct |
| 0106/0107 | STFT/LTFT Bank 1 | % | (A−128)×100/128 | `FUEL_TRIM` | ✅ correct |

Catalog hygiene: all 11 entries `enabled=true` (PidDefinition.kt), priorities MEDIUM (015E/019D/010A/0123/015D/0106/0107)
and SLOW (012F 3000 ms, 0151/0152 5000 ms, 0103 3000 ms) — intervals vs stale thresholds (5 s medium / 15 s slow,
ObdScheduler.staleThresholdMsFor) leave no staleness gap.

## 2. Findings (all fixed in this commit)

| # | Sev | File:line | Finding | Fix | Guard |
|---|---|---|---|---|---|
| F-1 | **HIGH** | `ProfileDefinitions.kt:29` | 019D decoded with `FUEL_RATE_20` (÷20, L/h) while catalog uses `FUEL_RATE_MASS_10` (÷10, g/s). Profiles/diagnostic-test screen showed **half the value with the wrong unit**. Live dashboard unaffected (catalog path). | decoder → `FUEL_RATE_MASS_10`, name/description corrected | `FuelPidLineageTest.profile requests never diverge from catalog decoders` — fails on ANY id whose two definitions disagree (whole bug class) |
| F-2 | **HIGH** | `DecoderRealWorldVerificationTest.kt:93-98` | Test #7 hand-built a `pid("019D", FUEL_RATE_20)` definition and asserted 1.7 — **locking the wrong formula and masking F-1** from CI. | test now uses catalog formula: 41 9D 00 22 00 22 → 3.4 g/s (+ unit assert); new sibling test 41 5E 00 22 → 1.7 L/h | self-guarding |
| F-3 | LOW | `ProfileDefinitions.kt:30` | 0123 described as "Fuel rail pressure (manifold vacuum)" — that is PID $22's definition (×0.079); $23 is **gauge** pressure (×10). Formula in code was right, text misleading. | description → "Fuel rail gauge pressure (direct injection)" | lineage test |
| F-4 | **MED** | `TelemetryDashboardContent.kt` effectiveLive | A **validated** PID whose live query hit transient `NO_DATA`/`CAN_ERROR` fell to the else branch → tile kept the silent "Not available" (Batch-17 doctrine violation: failure states must be loud). | honest branches: `no data from ECU`, `CAN bus error` | `DashboardHonestLabelsTest.transient failure labels pass through verbatim…` |
| F-5 | **MED** | `ProfileDefinitions.standardObdRequests` | **015E absent** — the standard profile never tested the primary volumetric fuel-rate PID at all (only the mis-decoded 019D). | added `DiagnosticRequest("015E", …, FUEL_RATE_20)` | `FuelPidLineageTest.standard profile covers both fuel-rate PIDs…` |

## 3. Verified-correct (no change needed) — evidence

- **Validation path:** 015E/019D in both MainViewModel validation lists (:359, :607) and `DASHBOARD_PIDS`
  (ObdQuickConnect.kt:34) — the 2026-09-12 fix. All other fuel PIDs resolve via the progressive auto-probe
  (ObdScheduler lineage-fix block): one unresolved enabled PID per cycle → OK/NOT_SUPPORTED/TIMEOUT —
  coverage guard: `FuelPidLineageTest.every dashboard fuel tile PID is catalogued and enabled`.
- **Failure attribution:** executePidQuery failure branch records a TransactionRecord, marks capability
  (Rule 6: TIMEOUT/NO_DATA never collapsed into NOT_SUPPORTED), publishes `"Not available"` via
  `telemetryStore.publishUnavailable(pidId, ecuId=validatingEcu)` — per-ECU attribution keeps the dashboard
  alive when a secondary ECU times out. effectiveLive converts that string to the honest label.
- **Stored-key format:** RecordingManager stores 2-hex `tx.pid`; `TripFuelSummary.normalizePidKey`
  canonicalises ("9D"→"019D"); regression locked by TripFuelSummaryTest + the new mass-only test which
  deliberately feeds 2-hex keys.
- **Integration math (hand-checked):** litres = Σ rate×dt/3600, dt clamped to `MAX_GAP_MS=15 s`;
  coast = rate ≤ 0.15 L/h AND speed > 20 km/h; distance = Σ v₀×dt/3600; km/L only when litres > 0.05 AND
  km > 0.05 (no division noise); mass→volume = g/s × 3600 / 745 (ρ=0.745 kg/L) → 3.725 g/s ⇒ 18.0 L/h ⇒
  0.295 L over 59 s ✓ (asserted in `FuelPidLineageTest.mass-only 019D series integrates…`).
- **4-byte $9D responses:** decoder strips `41 9D` prefix and reads bytes 0/1 via `getOrNull` — trailing
  reserved bytes tolerated (test payload 41 9D 00 22 00 22 ✓).
- **UI loud states:** TripFuelLogCard (TripDetailScreen :815-868) shows real litres, km/L or "—" **plus**
  the guided fallback ("use refuel-log km/L in Fuel Costs instead"); MID-vs-Recorded explanation lives in
  AboutScreen :123 as the owner mandated.
- **AC-fuel coupling constants** (engine/AcClimateModel.kt:19-39): BLOWER 0.15 kW, AC_BASE 1.1 kW,
  0.10 kW/Δ°C, cap 4.0 kW, AUTO ×0.75, fallback Δ 8 K, 32.3 MJ/L, η 0.30 → L/h = kW ÷ 2.6916
  (3.6/(32.3×0.30) = 0.3715 L/kWh ⇒ ÷2.6916) ✓ matches the documented model, locked by AcClimateModelTest.
- **Honest-label passthrough:** formatLiveValue returns "NOT SUPPORTED BY ECU" / "no answer (timeout)" /
  "probing..." / "no data from ECU" / "CAN bus error" verbatim (none collide with the raw errorStates set),
  isLiveError=false so the message itself is the signal — contract locked by DashboardHonestLabelsTest (7 tests).

## 4. Test coverage matrix (fuel chain)

| Layer | Tests |
|---|---|
| Decoder math | PowertrainEnginesTest (015E/019D catalog), DecoderRealWorldVerificationTest #7/#7b (fixed), **FuelPidLineageTest §4 (9 PIDs, hand-computed)** |
| Cross-source lineage | **FuelPidLineageTest §1-3 (catalog↔profile↔tiles)** |
| Analysis/integration | TripFuelSummaryTest (2-hex regression, thresholds), **FuelPidLineageTest §5 (mass-only)**, TripTrendAnalyzerTest |
| UI labels | DashboardHonestLabelsTest (7), formatLiveValue passthrough |
| Fuel logs | FuelLogCodecTest, FuelLogParityTest |
| AC coupling | AcClimateModelTest |

## 5. Verdict

The live-dashboard fuel chain (catalog → validation/auto-probe → decoder → liveMap → honest tile → storage →
trip analysis → km/L card) is **sound and now fully guard-tested**. The defects found were all in *secondary*
surfaces (Profiles test screen, one masking test, transient-failure labels) — exactly the class the 2026-09-12
post-mortem predicted: nothing contradicted itself statically within a single file; the contradictions lived
*between* files. The new cross-source agreement test (FuelPidLineageTest §1) makes that class CI-visible for
every shared PID, not just fuel ones.

---

## Addendum 2026-09-13b — F-6: 0x9D resolution recalibrated against the REAL CAR (owner live telemetry)

**Trigger:** 33 on-car screenshots (idle, coolant 49-65 °C, AC on/off) showed `Engine Fuel Rate
(Mass) 0.80-1.40 "g/s"` and `Idle Fuel Rate 3.87-6.77 L/h` - physically impossible for a warm
1.0 TSI (idle mechanical power would imply ~10 kW).

**Cross-check (stoichiometric speed-density):** MAP 37 kPa, 978 rpm, IAT 30 °C, lambda 1.000 →
air ≈ 2.6 g/s (VE 0.75) → fuel ≈ **0.15-0.19 g/s**. Observed raw counts 8-14 match this ONLY at
**0.02 g/s per count (/50)** (≡ 0.1 L/h); the spec-mirror resolution /10 g/s is 4-5× off.
Secondary mirrors are leads, the car is the oracle (standing rule).

**Fix:** `FUEL_RATE_MASS_10` → `FUEL_RATE_MASS_50` (÷50) in PidDecoder + catalog + profile +
simulator byte scale; guards: owner-idle-frame test (raw 8 → 0.16 g/s → 0.77 L/h), and a
stoichiometric cross-check test locking the calibration ratio < 2× while rejecting the old scale.

## Addendum 2026-09-13c — live-telemetry UX triage (same screenshot batch)

| # | Observation | Verdict / action |
|---|---|---|
| T-1 | Bottom navigation bar visible on every screen (label even truncated "Dashboar d") | **FIXED** - bar removed; Batch-16 mandate is hamburger drawer only |
| T-2 | FAB (+) covered the "Test Profile" button on the dashboard profile card | **FIXED** - verification row padded clear of the FAB zone |
| T-3 | `Coolant Temp 2 (Radiator)` showed **-37 °C** (uninitialised raw) as a real number | **FIXED** - plausibility gates on TEMP_MINUS_40 / CATALYST_TEMP decoders: out-of-range raw → numeric null + "implausible raw - no data" (loud, per Batch-17) |
| T-4 | 015E "NOT SUPPORTED BY ECU", 0161 not supported, gear "Not available / Not detected" at standstill, VIN flaky between connects | **CORRECT behaviour** - honest labels; gear at standstill IS N/P (Range row shows P/N); VIN re-read button present |
| T-5 | GPS altitude live (465 m Hyderabad) but trip-detail altitude cells show honest "--" | **KNOWN gap** - altitude not persisted into telemetry samples; parked as additive follow-up (store GPSALT sample rows) |
| T-6 | Intermittent "Not available" flicker on 0146/0133/0163 between polls | **ACCEPTED** - scheduler rotation + capability honesty; values return on next cycle |
