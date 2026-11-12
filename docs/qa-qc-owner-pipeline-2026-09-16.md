# Owner pipeline, 2026-09-16 (8 tasks, one at a time, each CI-tested)

Owner brief: 8 tasks "run individually tested by tomorrow morning 7am don't be hurry if it
fail retry multiple times never stop."

| # | Task | Delivery | Tests |
|---|------|----------|-------|
| 1 | Multiple signals on the same trend | Trend chips toggle up to 4; tap order = axis priority (1st left axis + envelope/area, 2nd labelled right axis, 3rd/4th scaled-to-fit flagged "fit"); per-signal colours; crosshair bubble lists EVERY signal with its own unit | TrendAxisMathTest (4) + ChartSamplingTest (8 existing) |
| 2 | Pinch zoom in/out | Two-finger pinch zooms time around the centroid, two-finger pan slides (1:1), one-finger still scrubs; zoom re-bucketizes the VISIBLE slice so detail appears; HH:mm:ss ticks under 10 min; 1%-span floor; "full span (viewing Xm)" reset chip | TrendZoomTest (7) |
| 3 | Voltage min/max recording | Per-trip extremes WITH timestamps: Summary.voltageExtremes (amber card row), trips-DB columns (MIGRATION_10_11, v11), session JSON explicit nulls, backup->import round trip | VoltageStatsTest (4) |
| 4 | Engine load based on AC on/off | AcLoadAnalyzer attributes engine-running (rpm>=400) observations to measured AC regimes; card row "+X.X pts mean load with AC (on vs off) • +L/h fuel • +rpm"; thin regimes never fabricate deltas | AcLoadAnalyzerTest (7) |
| 5 | AC during start-stop stalls: battery? | ANSWER: blower/fans yes (off the 12 V battery), cooling no (7VL compressor is belt-driven - cannot spin with engine off). Measured: stall windows (now exposed by StartStopAnalyzer) sliced out of 0142: mean/min V in stalls vs charging baseline, sag labelled as proxy (no battery-current PID exists), stalls overlapping measured AC-on counted | StartStopWindowsTest (5) + StopBatteryAnalyzerTest (6) |
| 6 | Engine torque calculations | Trip side: Trends channel "Torque" (Nm) = PID 0162 % x reference (ECU's 0164 when answered, else factory 178 Nm, reference printed); card row mean/peak Nm while running; silent 0162 -> nothing shown | TorqueTripTest (5) |
| 7 | With & without AC analysis in SHARED trips | `<tripId>_analysis.md` generated fresh at ZIP time from stored samples: totals, measured AC, WITH vs WITHOUT AC load/rpm/fuel (or explicit NOT COMPARABLE), extremes, start-stop + stall battery, torque; evidence-gated sections | TripAnalysisReportTest (5) |
| 8 | Settings consistency, updates last | Android-style section headers: YOUR GARAGE (6 nav cards together) -> CONNECTION & VEHICLE (profile, PIDs, Android Auto) -> APPEARANCE & UNITS -> DATA & BACKUP (export, Google, retention) -> ABOUT & UPDATES LAST | compile-gated reorder, zero logic change |

Honesty rules applied throughout: measured values only, estimates labelled, evidence floors
hide thin sections, gaps skipped not interpolated (no-fake-values rule, owner 2026-09-14).
