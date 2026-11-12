# QA/QC — Restart-refuel detection and the receipt popup (2026-09-19)

Owner, verbatim: *"my refueling i need to stop the engine. So when restart my car after refuel
obviously you can scan what is fuel % before start and after start easily can determine whether
real fuel up is done also. You can popup in the app fuel change detection do you want to add
receipt. So there are intelligent ways to do it you're not doing it well."*

He is right about the moment. The fill happens engine-off, so the **restart** is the first
instant the car can prove it: level % before stop (the persisted stamp of the previous session's
last 012F row) vs level % on the first 012F row after start. Until now that comparison only ran
at session **finalize** — correct, silent, and hours late. Now it runs the moment the engine
comes back, and it asks.

## What ships

1. **Live restart check** (`RecordingManager.maybeFlagRestartRefuel`, armed in `startRecording`,
   fired by the first valid 012F row in `recordTransaction`, exactly once per session):
   reads `lastLevelStamp`, runs the same pure `RefuelEventDetector.crossSession` (≥ 4 pt rise,
   `firstTs > prevTs`) the finalize pass uses, and on a hit **writes the event immediately** —
   the popup offers a receipt against an event that already exists in the ledger, and a session
   that dies again still keeps the detection.
2. **The popup** (`MainActivity`, hosted above the NavHost so it finds the owner on whatever
   screen he lands on): "Fuel change detected — tank went from X% to Y% while the engine was
   off — about Z L (estimated from the level rise, not measured). Add the pump receipt?"
   - **Add Receipt** → navigates to Fuel & Costs and opens the existing entry dialog
     **prefilled**: estimated litres, previous-session odometer, date/time pinned to the restart
     instant in IST (`RecordTime.format`, closest observed time to the engine-off fill). Saving
     runs the existing `calibrateAfterFuelEntry` loop: pump litres replace the estimate by
     |Δodo| ≤ 3 km match and re-teach tank capacity.
   - **Not Now** → the estimate stays in the ledger; a receipt entered any time later still
     matches and calibrates it.
   - Either answer sets `restart_refuel_dismissed_ms` for that event id: **one popup per
     refuel**, never a nag on every restart. No popup if the event already carries pump litres.
3. **REPLACE-idempotence fix** (`insertEventCarryCalibration`, shared by restart write, finalize
   write and backfill): both writes of the same between-sessions refuel produce the same id
   (window end = first level row's ts — `RestartRefuelIdempotenceTest`), so finalize *upgrades*
   the row with the session's own first 01A6 odometer instead of duplicating it — and the
   existing `calibratedPumpL` is now carried across every rewrite. Before this, a finalize or
   recovery rewrite could wipe a receipt calibration matched earlier in the session.

## Verification

- Unit: `RestartRefuelIdempotenceTest` — restart copy and finalize copy are the same event id;
  odometer fallback (prev 3500.0) vs upgrade (session 3501.2) both inside the match window.
  Existing 16 detector/scanner tests unchanged and green.
- CI census at commit: see PR digest (0 failed).
- Field check for the owner: fill up engine-off as usual, restart, and the popup should appear
  within one polling cycle of the first fuel-level row (a few seconds after OBD link-up), with
  your real numbers (e.g. 37.6 → 93.7 %, ~28 L est on a 50 L tank).

## What is NOT claimed

- The popup does not fire while the app is closed and the car is not started — detection needs a
  session (the fill itself is invisible to OBD by physics: engine off, no bus traffic). The
  finalize path and the one-time backfill still cover everything the live path misses.
- Litres shown in the popup are the level-rise **estimate**, labelled as such everywhere
  (no-fake-values rule); pump truth only ever comes from the receipt.
- If the OBD adapter is not linked at restart (pairing delay), the first 012F row simply arrives
  later — the check waits for the first *valid* level row, not for a timer.
- A rise below 4 pts (~2 L in a 50 L tank) is treated as slosh/sensor noise, as before; a
  topping-off of 1–2 L will not prompt. This is deliberate: the owner's rule is auto-cut fills.
