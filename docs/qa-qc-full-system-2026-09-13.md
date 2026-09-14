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
