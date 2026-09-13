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

## Addendum 2026-09-13 (evening): dashboard update-consistency audit, always-expanded board, trends UI/UX (owner batch 2, 32 live screenshots 20:45-20:48 IST)

Owner report: "inconsistency in dashboard board update ... keep dashboard always expand mode so i no need to click and see data ... Trends are not proper".

### T-7 Always-expanded dashboard (owner directive)
`TelemetrySectionCard` was collapsible; Combustion & Trim, Temperatures, Air & Turbo and GPS & Telemetry started COLLAPSED, hiding live telemetry behind taps (visible in screenshots as folded headers with chevrons). The toggle, chevron and AnimatedVisibility were removed entirely (not defaulted open) so no state or accidental header tap can fold the board again. All eight sections now render their rows unconditionally.

### T-8 Dashboard update inconsistency - root cause and fix
Screenshot evidence: at 20:47 Engine RPM + Vehicle Speed show red "Not available" while Engine Load / torque in the SAME card are live; AI Doctor at the same moment shows "Engine RPM: Not available (stale)" beside a live 63 °C coolant; at 20:45 Baro/Throttle/Pedal-D are "Not available" and live again at 20:48.
Root cause: polling is a SERIAL round-robin - each cycle walks every due PID with a full CAN round-trip, so a PID's real refresh gap is the whole cycle (3-8 s with 30+ enabled PIDs), while the fixed staleness budgets were 2.5 s (fast tier). Healthy tiles therefore aged into "stale" BETWEEN two successful refreshes, and the old display policy replaced a stale-but-valid number with the blank "Not available (stale)" which `formatLiveValue` then red-flagged. Three coordinated fixes:
1. `LiveTelemetryStore.adaptiveStaleThresholdMs(tierFloor, ewmaGap)` - budget = max(tier floor, 2 x EWMA(observed per-PID query gap) + 1.5 s). `ObdScheduler` measures the gap between consecutive query attempts per PID (EWMA 3:1) and feeds it to the staleness supervisor. A real dropout still surfaces once it outlives the adaptive budget.
2. Stale-but-valid samples keep their NUMBER with an honest marker: "970 RPM (stale)" (replaces "Not available (stale)"). `numericMap` still drops stale entries so integrators/gauges never consume aged values.
3. `formatLiveValue`/`isLiveError` treat the "(stale)" suffix as data, not error - no red flip-flop on live tiles.
Guard tests: `DashboardUpdateConsistencyTest` (adaptive budget math, stale marker visibility, formatter policy) + updated `LiveTelemetryStoreTest`.

### T-9 Trends UI/UX (owner: "Trends are not proper")
Screenshot evidence (Insights 20:47): yellow boost spikes drawn BELOW the plot box; green fuel line sawtoothing to zero.
1. `InsightsScreen.TrendCard`: `fuelLh ?: 0.0` / `boostKpa ?: 0.0` fabricated physical zeros for every momentary NO_DATA in the 1 Hz log - the sawtooth was missing data, not engine behaviour. Series now use `mapNotNull` (gaps, not zeros).
2. `XyPlot`: hard-coded `minY = 0` clipped negative series outside the canvas (gauge boost at idle ~ -60 kPa) - the below-box spikes. Y range now spans the padded data minimum and a solid zero baseline is drawn when the window crosses zero; range labels follow.
3. `SimpleLineChart` (trip trend rows, HUD sparklines): bare min-max polyline amplified ±2 % wobble into full-height swings and showed no magnitudes. Now: 12 % range padding, dashed mid baseline, max/min value labels, point dots for sparse series, gradient fill. API unchanged (defaults).
4. `TripDetailScreen` Trends tab: plotted in time order, downsampled to <= 600 evenly spaced points via new pure `analysis.ChartSampling` (first/last sample always kept), y-axis max/mid/min labels in a right gutter, point dots for sparse trips, and a start/end time + span + sample-count row so trips of different lengths no longer look identical.

### T-10 Verified NOT bugs in this batch
Trip integration counters (0.017 L/12 s ... 0.283 L/206 s across the 20:45-20:48 shots) are monotonic - one continuous trip, no reset bug. Idle 3.87/4.35/6.77 L/h and -37 °C coolant-2 in these shots are the pre-F-6/T-3 build (already shipped in 3627b7d). Bottom nav bar in these shots likewise predates the T-1 removal.

## Addendum 2026-09-13 (night): freeze-frame display policy + unit-parse inconsistency (owner re-report of dashboard inconsistency)

### T-11 Freeze-frame: one lost CAN frame must never blank a tile
Remaining blink source after T-8: a fresh explicit failure (timeout / NO DATA) outranked a still-in-budget valid sample in primary-ECU selection, so a single lost frame showed "Not available" until the next cycle succeeded (visible in the 20:45-20:48 shots: Baro / Throttle / Pedal-D alternating). `LiveTelemetryStore.qualityTier` now orders: fresh valid > in-budget-or-stale valid (freeze-frame) > fresh failure > stale failure. A failure with NO in-budget value anywhere still shows its loud placeholder. Once the frozen value ages past its adaptive budget it carries "(stale)"; numericMap keeps serving integrators only while the winner is non-stale, and trip integration is driven by RX frame events (`onTelemetrySignalUpdated` is only invoked on the decoded-frame path), so frozen numerics can never double-count. Tests updated: `LiveTelemetryStoreTest.testTimeoutOnOnlyEcuFreezeFramesLastValueThenAgesToStale`, `...testFailureWithoutAnyValueReportsPlaceholderWithoutUnitSuffix`, `KylaqTraceReplayTest` 010F expectation.

### T-12 Decoded-string unit-parse bugs (AC card "OUT --" + dead AC learning)
`liveDecodedMap` values carry units ("25 °C", "12 km/h"). Two call sites parsed them with bare `toDoubleOrNull()`, which is ALWAYS null:
1. `DashboardScreen` ambientC (AC & Climate card) - the card showed "OUT -- / delta --" while Ambient Air Temp was live in the Temperatures card (owner screenshots 20:45-20:48): a visible cross-card inconsistency.
2. `DashboardScreen` acSpeed - stuck at 0.0, so the AC ON-vs-OFF economy learning gate (speed > 5 km/h) could NEVER open; the "LEARNED" row could never populate no matter how much the owner drove.
Both now read `viewModel.liveNumericMap` (unit-free numeric view, stale-excluded). AiDoctorScreen already stripped non-numeric characters and was unaffected.
