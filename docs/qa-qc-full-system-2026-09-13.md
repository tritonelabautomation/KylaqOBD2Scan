# Full-system QA & QC pass — 2026-09-13 (owner mandate: "consider everything, do a proper QA & QC")

Method: every bug class that ever reached the owner was turned into a codebase-wide
grep/audit pattern; each hit was triaged as BUG / ACCEPTABLE / CLEAN. Fixes shipped
same day with guard tests where the behaviour is pure JVM.

## Audit patterns and findings

| # | Pattern (derived from a past owner report) | Hits | Verdict / action |
|---|---|---|---|
| 1 | `toDoubleOrNull()` / regex-strip on unit-carrying display strings (AC card OUT bug class) | AiDoctorScreen live score (volt/coolant), DrivingDashboard hero text | AiDoctor: BUG → numeric view + stale fallback. HUD hero strips units for DISPLAY only (values never negative) → ACCEPTABLE |
| 2 | `?: 0.0` fabricating zeros from nullable telemetry (Insights sawtooth bug class) | Insights trend (fixed earlier), money sums, chart min/max guards | Remaining hits are currency sums or post-empty-guard → CLEAN |
| 3 | Collapsed-by-default UI hiding live data (always-expand mandate) | telemetry cards (fixed earlier); all remaining `mutableStateOf(false)` are user-initiated dialogs/sheets | CLEAN |
| 4 | Locale-dependent `String.format` decimals (comma-decimal devices, CSV exports) | 148 single-line + 2 multi-line formats across 22 files | BUG class → all pinned to `Locale.US` |
| 5 | FAB obscuring content (T-2 bug class) | global FAB on 7 main-tab routes with zero content clearance | BUG → 96 dp bottom content padding on Dashboard, Insights, Recordings, AI Doctor (3 tabs), Raw Monitor, Console |
| 6 | Poll-interval inheritance (250 ms default; AC/ambient lurch root cause) | 114/153 catalogue entries | Fixed earlier via priority floors; guard test scans catalogue |
| 7 | Idle-model constant vs owner telemetry (F-6 oracle rule) | `IDLE_FUEL_LH = 0.8` sat at bottom of measured 0.77-1.35 L/h band | BUG → recalibrated to 1.05 L/h (owner median), analyzer copy synced, sync-guard test added, Recordings card text now reads the constant |
| 8 | Duplicate/competing FABs | TripsScreen own FAB | CLEAN (sub-route, outside mainTabRoutes - never doubles) |
| 9 | Cross-screen source consistency (HUD vs dashboard vs Auto vs AI Doctor) | numeric vs decoded views | AiDoctor fixed (pattern 1); HUD/Auto already numeric → CLEAN |
| 10 | Stale/dead code mandate | bottomNavItems retained but ONLY for FAB route gating; no orphan composables found | CLEAN |

## Verified consistent (no action)

* Idle constant consumers (CoastNeutralDetector, DrivingCoach, FuelSavingsCoach,
  TripTrendAnalyzer) all reference the single constant; sync test now locks it.
* Catalogue spot-check of all 32 dashboard-tile PIDs: decoders/units match the
  reference-log verification tests (0133 baro = RAW_A_KPA, 95 kPa in owner shots ✓).
* 01BD duplicate ambient remains lookup-catalogue only (never polled).
* Insights/SectionCards are static (no collapse); trip-detail tabs are navigation, not hiding.

## Known-open (documented, not bugs)

* T-5: GPS altitude live but not persisted into trip samples (parked additive).
* AUTO ×0.75 compressor modulation remains an assumption until per-ride learned
  ON/OFF economy accumulates in both AUTO states (evidence rule).
* J1979 exposes no compressor PID - hardware identity lives in
  docs/reference/kylaq-ac-compressor-hardware.md instead.

---

## Addendum 2026-09-14 — explicit polling intervals for EVERY PID

Owner: *"enabled PID's have no explicit polling interval — can you check all PID's have polling interval?"*

Audit result: the 39-entry poll set (`DefaultPidDefinitions.getDefaults()`) was fully explicit
(sub-task I), but **114 of 153 catalogue entries** — the whole `StandardPidCatalog` "additional"
J1979 set (113) plus the `lookup()` generic discovery fallback (1) — declared **no interval at all**
and inherited the 250 ms constructor default with an implicit MEDIUM priority. These entries feed
`PidDiscoveryService` → the PID Scanner screen, and any of them can be promoted into polling via
PID Config, carrying the undeclared 250 ms with them.

Fix (species level, so it cannot regress):

1. **Constructor default removed** — `defaultIntervalMs: Long` is now a REQUIRED parameter of
   `PidDefinition`. The compiler rejects any future PID (catalogue, discovery fallback, custom,
   test) that does not declare its own polling interval.
2. **All 114 entries given explicit priority + interval** inside the documented bands
   (FAST 100–250 ms → 150, MEDIUM 400–800 ms → 500, SLOW 2000–5000 ms → 3000), assigned by
   J1979 semantics: O2 sensor voltages/STFT/throttle positions FAST; rail pressure, EGR, lambda,
   load, timing MEDIUM; monitors, counters, VIN/calibration IDs, catalyst/DPF/SCR temperatures,
   tank level SLOW. Discovery fallback for uncatalogued PIDs: MEDIUM/500 ms (matches the
   PID Config dialog + persisted-JSON fallback of 500 ms).
3. **One pre-existing band violation found and fixed:** poll-set research PID 01A6 declared
   SLOW but 1500 ms (below the 2000 ms floor — the scheduler was silently clamping it). Now 2000 ms.
4. **Guard tests** (`PidCatalogIntervalTest`): every poll-set and catalogue entry must declare an
   interval ≥ its tier floor and ≤ 5000 ms; uncatalogued fallback must be MEDIUM/500; the 01BD
   ambient duplicate must stay SLOW ≥ 2000 ms. Local pre-check: 152 literal intervals, 0 violations.
5. Test constructors in `PidDecoderAdversarialTest` (5) and `DecoderRealWorldVerificationTest` (1)
   updated for the required parameter.

Known-open list: T-5 altitude persistence; AC AUTO ×0.75 assumption; no J1979 compressor PID (doctrine).

---

## Addendum 2026-09-14 (2) — NO fabricated readings without an OBD link

Owner (bedroom screenshots, adapter never paired, engine never started): the AC & Climate card
printed **COMPRESSOR 1.43/1.90 kW, FUEL COST +0.53/+0.71 L/h, BLOWER 0.15 kW** and the AI Doctor
printed a perfect **100/100 VEHICLE HEALTH SCORE** — all with `OUT --` and every link dot grey.
Root cause: both features computed *model output* from *assumed inputs* and rendered it in the
same visual language as measured telemetry.
- AC card: `compressorLoadKw()` falls back to `FALLBACK_DELTA_C = 8 °C` when PID 0146 ambient is
  null; nothing checked the connection state (DashboardScreen had `isConnected` at line 96 but
  never passed it to the card).
- AI Doctor: live score started at 100 and only deducted on bad LIVE readings → no data = 100.

Fixes (honesty gate):
1. `AcClimateModel.liveCompressorLoadKw(connected, …)` returns **null** without a link; the card
   takes `connected` from DashboardScreen and prints `--` for kW and L/h, plus an explicit line
   "NO OBD LINK - kW and L/h print only from live telemetry…". State chips and the hardware/
   nameplate notes remain (they are facts, not readings).
2. Connected but ambient absent (0146 unsupported car): numbers stay model output but the card
   now says so: "Model estimate - ambient not reporting, assumed delta-T 8 C."
3. New `ai/AiDoctorScoring.liveHealthScore(connected, volt, coolantC): Int?` — null unless
   CONNECTED **and** ≥1 live sample; hero card shows `--`, badge shows `NO DATA`.
4. Guard tests: `AcClimateModelTest` (no link → null for AC/BLOWER/OFF; link restores 2.2/1.9/0.0)
   and new `AiDoctorScoringTest` (no link → null even with alarming values; connected-no-samples
   → null; deductions 15/25).
5. Unchanged by design: Fuel Savings Guide worked examples carry explicit example temperatures
   (35/24, 45/18) and name the model — documentation, not readings. Driving HUD already renders
   `--` from the live map when disconnected.

### Same-day sweep, remaining findings
- **J1979 0xFFFF error sentinel on EQUIVALENCE_RATIO** (λ PIDs 0144/0124-012B): unguarded it
  decoded the not-available indicator as a plausible λ 2.000. Now `numericValue = null`,
  display "no data (J1979 0xFFFF)". Guard tests in PidDecoderAdversarialTest.
- **Verified already-guarded (no action):** persisted-CSV parses (try/catch → null), RawLogManager
  bounded+synchronized buffer, duration divisions (`coerceAtLeast` in TripDriveAnalysis /
  WeeklyTripOverview), chart empty/single-point guards (XyPlot), ELM327 read framing
  (accumulator until terminator), no SimpleDateFormat locale holes.
- **Honest coverage map:** 59 of 143 main classes referenced by unit tests. The 84 unreferenced are
  Compose screens, Android framework wrappers (BT manager, GPS, audio, Auto service) and Room
  DAOs - not JVM-unit-testable; their logic cores (decoders, models, analyzers, stores, codecs)
  are the tested 59. UI behaviour is covered by the on-device verification passes instead.

### Same-day sweep (3) — Rev Theater reachability + audible-on-silent-phone
Owner: drawer screenshot showed no RevHeadz entry; asked for a thorough check incl.
"does it really play sound when connected with OBD".
1. **Drawer was an unscrollable Column** (24 items): on a phone the tail entries
   (Rev Theater, PID Scanner, Coding Lab, Settings, About) fell below the fold and were
   unreachable. Now wrapped in `verticalScroll(rememberScrollState())`.
2. **AudioTrack used CONTENT_TYPE_SONIFICATION** - the system-sounds stream, which
   silent/vibrate profiles mute on many skins (owner's status bar showed vibrate).
   Now CONTENT_TYPE_MUSIC: follows media volume, silent-profile-proof.
3. **On-device proof-of-audio lamp**: Rev Theater shows "AUDIO: rendering <profile> ·
   buffers N · media-volume controlled" while running (buffersWritten counter from the
   render thread). If the counter climbs and nothing is heard, media volume/DND is the
   cause - diagnosable without a debugger. LIVE-OBD path re-verified by reading:
   START -> AudioTrack thread; loop pushes PID 010C rpm / 0104 load when
   ConnectionState.CONNECTED && rpm>0; falls back to labelled MANUAL sliders otherwise.

### Same-day sweep (4) — Rev Theater: owner pack request + audio-path truth
Owner verified Rev Theater on device (AUDIO lamp climbing = render thread alive) and asked:
(a) real car sounds? (b) add Dodge Charger/Challenger/SRT/Hellcat + European & Japanese
sports packs, (c) does it play through the CAR speakers via OBD?
1. **Six new physics packs** (12 total): 6.4 HEMI V8 (Charger/Challenger), 6.2 SC HEMI
   (SRT Hellcat), 4.0 Flat-6 (Stuttgart GT3), 4.0 Twin-Turbo V8 (Euro GT), 3.8 Twin-Turbo
   V6 (Godzilla), 2.0 VTEC Turbo (Type R). Chip rows now `chunked(3)` so any pack count
   lays out. Guard test asserts 12 packs + firing-Hz physics for HEMI/Hellcat/flat-6.
2. **Honesty banner states the audio path**: sound comes from the PHONE (or its paired
   BT / Android Auto media output). OBD-II carries telemetry only - it can never feed
   car speakers. Supercharger whine explicitly labelled NOT modelled.
3. Recordings remain out of scope: RevHeadz's catalogue is licensed/paid; this app ships
   synthesis only (no-paywall mandate + copyright).
