# Powertrain and telemetry engines

Seven pure Kotlin engines with deterministic JUnit traces in `AllFeaturesTestSuite`.
These are reusable analysis APIs, not new dashboard cards or an automatic vehicle-control
system. The live scheduler/UI is not wired to these engines by this change.

## Input contract

- Feed synchronized, freshly decoded samples, not independently cached PID values.
- `timeMs` is a nonnegative monotonic millisecond clock (zero is a valid start).
  Duplicate/out-of-order timestamps leave state unchanged. Call `reset()` (or valet
  `start()`) for a new session/clock; never feed epoch and monotonic times together.
- Nullable telemetry means unavailable, not zero. Non-finite/out-of-range samples cannot
  establish a qualifying condition. Missing samples break continuous evidence.
- Integration only credits adjacent qualifying samples at most 5 seconds apart.
  Acceleration requires at most 1 second; cranking requires at most 2 seconds.
- Instances are session-scoped and intended for one serialized caller. They do not poll
  OBD, increase bus load, persist data, or collect location.

## APIs and interpretation

| Engine | Main API | Interpretation |
| --- | --- | --- |
| `TurboCooldownGuardian` | `update(timeMs, boostBar, rpm, coolantC)` | ECM 7E8 gauge boost >0.5 bar, RPM >2500 and coolant >95 C starts a 15 s precaution. Continued episodes accumulate one load-second per second, capped at 60. Valid low-load running samples discharge it. Positive recommendations are 15–60 s; zero means no outstanding modeled load. No cooling is credited with the engine off. |
| `TransmissionSlipAnalyzer` | Constructor takes measured tyre circumference in metres; `update` takes validated actual gear, source ECU, engine RPM, road speed, lockup status and optional ATF temperature. | Variant ratios 4.044/2.371/1.556/1.159/0.852/0.672, final drive 3.867. Expected RPM = wheel RPM × final drive × gear ratio. Signed slip % = (engine − expected)/expected × 100. Above ±10% is flagged only with confirmed lockup. Standstill gives no road-based slip. This is not a measured turbine-speed slip sensor or a fault diagnosis. Existing adaptive `Aq250GearModel` is unchanged. |
| `DfcoCoach` | Constructor takes local ₹/L and idle baseline L/h; `update` takes decoded 019D mass fuel g/s, RPM, throttle and validated in-gear status. | Requires exact zero mass fuel, RPM >1200, zero throttle and in gear. Conservative continuous-duration integration. Estimated avoided-idling litres = seconds × baseline / 3600; live ₹/minute is zero when not active. No invented mass-to-volume conversion. |
| `OctaneRoiAnalyzer` | `analyze(List<FuelLog>)` | Mileage = total distance/total litres; cost/km = total actual spend/total distance. Timing and trims are distance-weighted over measured values only. Positive premium savings means XP95 costs less/km; missing either fuel returns null ROI. Break-even premium price = regular ₹/km × premium km/L. Use comparable route/load/AC conditions; these labels do not establish ethanol content or octane causation. |
| `AccelerationPerformanceMeter` | `update(timeMs, speedKmh, boostBar)` | A stationary observation arms launch. First movement starts timing at the preceding stationary observation (no rollout). Linear interpolation gives 60 and 100 km/h crossings; peak boost uses measured samples during the run. Gaps, invalid speed, return to rest or >120 s abort. Complete/aborted runs require explicit reset. Closed course only; timing uncertainty is at least the telemetry interval. |
| `BatteryCrankProfiler` | `update(timeMs, voltage, rpm)` | Fresh precrank engine-off 11–13.2 V evidence, cranking 0<RPM<500, then running RPM≥500. Tracks the minimum 0142 ECU supply voltage; score is linearly 0 at ≤9.6 V to 100 at ≥11 V, warning below 40. Failed/>10 s cranks and missing evidence yield no score. Completed results are retained until reset. Running supply voltage is not proof of alternator health. |
| `ValetSurveillanceManager` | `start`, `update`, `events`, `stop` | Explicit opt-in; cold rev >3200 RPM with coolant <70 C; speeding >55 km/h. Audits episode start/last-observed timestamp and peak. Independent channels; unknown data and reconnects split episodes. Last 500 events retained by default, immutable snapshots returned. Start clears the prior audit; stop retains it. Caller owns consent and persistence. |

ATF stress integrates measured degrees above 100 C using trapezoids, including stops;
it is a cumulative degree-second exposure proxy, never estimated oil temperature,
energy, remaining life or a service interval. Battery scores are ambient-dependent
screening heuristics, not manufacturer-certified EFB/AGM capacity/CCA measurements.
No calculations use an assumed tank size: the vehicle's 50.0 L capacity remains unchanged.

## Verification

```sh
python3 tools/check_kotlin_structure.py
gradle testDebugUnitTest -PciExcludeRobolectric=1
```

The structural checker is not a Kotlin compiler. GitHub Actions performs actual Kotlin
compilation, the full JVM regression suite and the APK build. The existing workflow
publishes successful push builds under the rolling release tag `latest`, not a new
version-specific tag for each build. The APK version is determined by the CI run number.
