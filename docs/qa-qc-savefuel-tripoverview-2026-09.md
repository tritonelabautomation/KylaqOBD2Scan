# QA/QC audit — Save Fuel playbook + Trip Overview (incl. simulation-mode reactivity)

**Date:** 2026-09-13 · **Auditor:** senior-android-dev pass over commits `9d33254` (Save Fuel) and
`6581e14` (Trip Overview) · **Trigger:** owner challenge — *"you're sure right these features are
implemented? Do a detailed QA & QC when simulates these will update as well?"*
**Method:** grep/file:line evidence → code trace of every data chain → pure unit tests → CI.
No claim below lacks a verifiable anchor.

## 1. Reachability & navigation (both screens)

| Check | Evidence |
|---|---|
| Drawer entries | `MainActivity.kt:232` (`Screen.FuelSavings`), `:233` (`Screen.TripsOverview`) |
| Routes + titles/icons | `MainActivity.kt:88` (`fuel_savings`, "Save Fuel", Savings icon), `:97` (`trip_overview`, "Trip Overview", CalendarMonth) |
| NavHost composables | `MainActivity.kt:623-629` (TripsOverviewScreen w/ onBack + onOpenTrip), `:630+` (FuelSavingsGuideScreen w/ onBack + 3 CTAs) |
| Back navigation | Both screens: TopAppBar `ArrowBack` → `navController.popBackStack()` |
| Trip card → detail | `TripsOverviewScreen` chevron → `trip_detail/$tripId`, matches `Screen.TripDetail` route pattern `MainActivity.kt:79-80` (`createRoute`) |
| Guide CTAs | Fuel & Costs / DTC Scanner / Maintenance navigate targets all exist as Screen routes |

## 2. Data chains (what feeds each number)

**Save Fuel:** baseline km/L + trend ← `FuelLogRepository.entries()` (**fresh SharedPreferences
read on every call**, `FuelLogRepository.kt:263-268`) → `FuelLogCodec.intervals()` partial-fill rule
(partials add litres, never anchor) → `FuelSavingsCoach.baseline()`; idle ₹/h ←
`PowertrainModel.IDLE_FUEL_LH` (0.8 L/h) × latest logged price; AC L/h ← `AcClimateModel`
(1.1 kW + 0.10 kW/Δ°C, cap 4.0, AUTO ×0.75, ÷2.6916); sweet-spot/band text = model constants.

**Trip Overview:** `TripRepository.recentTrips(300)` + `getSamplesForTrip(id)` →
`TripFuelSummary.summarize()` (**same SamplePoint mapping as TripDetailScreen**, `TripDetailScreen.kt:71-73`)
→ `WeeklyTripOverview.overview()` (bands from speed histogram + idle; documented 5-weight score;
Monday-start weeks; prev-week window).

## 3. Simulation-mode chain (the owner's exact question)

`MainActivity.kt:737 onStartSimulation` → `MainViewModel.startSimulationMode()` `:653` →
`BluetoothManager.startSimulationMode()` `:225-242` ("Škoda Kylaq Simulator (EA211)") →
`SimulationElmTransport` (`bluetooth/SimulationTransport.kt:23`) → **the same RecordingManager
pipeline real drives use** → Room `trips` + `telemetry_samples` → Trip Overview reads exactly these
tables. Fuel logs remain manual entries (trip card / Fuel & Costs) — same store Save Fuel reads.
**Conclusion: simulated drives flow into both screens through the identical production path.**

## 4. Findings & fixes (this audit)

| # | Sev | Finding | Fix (this commit) |
|---|---|---|---|
| F-1 | MED | Trip Overview loaded once per `weekOffset` — a trip completing **while the screen is open** (e.g. simulated drive finishing) stayed invisible until re-entry | Key the loader on a live signature: `repo.allTripsFlow.map { size to newestStart }` collected as state; `LaunchedEffect(weekOffset, tripSignature)` |
| F-2 | MED | Save Fuel baseline cached in `remember {}` — Compose Navigation **retains back-stack state**, so returning from Fuel & Costs after adding a fill-up showed the stale baseline | `FuelLogRepository.changeTick: StateFlow<Long>` bumped in `add`/`delete`/`replaceAll`; screen recomputes in `LaunchedEffect(changeTick)` |
| F-3 | LOW | "Extreme cooling" AC figure used the unknown-temps fallback Δ8 (0.71 L/h) — mislabelled; model's true extreme is Δ27 | Extreme now computed at 45 °C ambient / 18 °C set = 3.8 kW → **1.41 L/h**, copy says "Hyderabad-summer max-cool"; guard assert added |
| F-4 | INFO | Verified clean: fresh prefs read per call; partial rule intact; chevron route matches TripDetail pattern; Monday week bounds; loud empty states; score dial null-safe (`--` when no trips) | no change needed |

## 5. Test matrix

- `FuelSavingsCoachTest` — 6 tests: empty log, 4-tank window, partial fold-in + −5 % trend, idle ₹,
  AC typical/auto/blower/off **+ new extreme 1.41 L/h** (all hand-computed in-file).
- `WeeklyTripOverviewTest` — 5 tests: band split, score 93/Good, week totals + prev window,
  empty week, time-of-day titles.
- CI (this commit): suites/tests digest on PR #1 — see run comment.

## 6. Verdict

Both features are **implemented, reachable, back-navigable, and now reactive**: any new fuel log
(real or typed after a simulated trip) updates Save Fuel immediately via the change tick; any new
or completed trip — **including a simulated EA211 drive** — updates Trip Overview live via the trips
flow, plus week navigation and re-entry. Simulation is not a second-class path: it writes through
the same RecordingManager → Room pipeline both screens read.
