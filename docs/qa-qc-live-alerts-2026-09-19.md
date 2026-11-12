# QA/QC — Live threshold alerts (gap-analysis build 1 of 3, 2026-09-19)

Owner approved the gap-analysis order ("Yes"): 1) live threshold alerts, 2) home-screen widget,
3) GPX export then map view. This is build 1.

## What ships

- **Settings → SAFETY ALERTS**: master switch (on by default) plus five editable limits -
  coolant warn (105 C), charging-voltage window (12.5-15.5 V engine running), fuel low (12 %),
  over-rev (5500 rpm). Persisted in prefs; unparseable input falls back to the safe default
  instead of saving garbage.
- **ObdKeepAliveService** evaluates the live PID map every 5 s while polling - the service
  outlives the UI, so alerts reach him screen-off and pocketed, which is the whole point mid-
  drive. Breaches post a HIGH-importance notification on a dedicated `vehicle_alerts` channel,
  once per metric per 2 minutes (cooldown map), auto-cancel.
- **Pure core**: `AlertRules.evaluate(values, thresholds)` - a function, so the firing AND the
  silence rules are unit-tested: coolant/voltage-both-sides/fuel/rpm fire at the limit (not
  above it), a healthy charging voltage says nothing, and an absent PID (car not answering)
  never alarms - honesty-first, no phantom warnings from a silent sensor.

## Why these five

Coolant and charging voltage are the two failures that destroy engines alternators quietly;
low fuel mirrors his brim-to-brim discipline (auto-cut fills, never run dry); over-rev protects
the 1.0 TSI on the highway. Everything else the car reports is either trend material or
already alarmed elsewhere (link silence, battery optimisation).

## Verification

- `AlertRulesTest` (4 tests) pins firing and silence vectors.
- CI census at commit: see PR digest.
- Field check: with the link live, Settings → SAFETY ALERTS, set "Fuel low (%)" to 90 for one
  drive - the notification should appear within ~5 s and repeat at most every 2 minutes; set it
  back to 12 afterwards. Coolant/voltage must stay silent on a healthy car (their defaults sit
  outside anything this engine produces in normal running).

## What is NOT claimed

- Alerts need the keep-alive service running (it starts with recording/auto-record) and the PID
  answered: no link, no alert - the notification never pretends.
- Thresholds are his numbers, not diagnoses: a coolant alert says stop and look, not which part
  failed.
- Widget (build 2) and GPX/map (build 3) are still open.
