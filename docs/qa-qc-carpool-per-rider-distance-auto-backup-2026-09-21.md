# QA/QC — car-pool per-rider distance + auto backup on entry (2026-09-21, third report)

## Owner report

Screenshots after merge fix (Merged Run 2026-09-21 07:36, 3 trips, 59677 tx) show correct trip:

- Trip summary: 31.1 km / 1h43 / 18 avg 75 max
- Fuel: 4.00 L / 31.1 km / 7.8 km/L / 12.9 L/100km, coasting 185s idling 389s engine-off 1023s, 15 stalls, battery extremes, torque 45/194 Nm
- Car-pool dialog: Date IST 2026-09-21 Time 07:40 Shared distance 30.0 km, Rider1 Harsha 210, Rider2 Tanuja 147, Rider3 Pavan 123
- Trips & Recordings: 3 selected → Merging… → merge banner "your 31 km fragments become one"

Owner feedback:

1. "Problem is you're assuming all guys joined at same location you're not showing individual rider distance shared so that you can calculate cost /km for each rider."
   - Before: one `distanceKm` for whole ride, all riders assumed same pickup.
   - Example: Harsha boarded at 0 km for 10 km, Tanuja at 10 km for 20 km, Pavan at 20 km for 30 km — all shown as 30 km, cost/km wrong.

2. "as soon as an entry is made automatically it should trigger backup and save info."

## Fix shipped (1.0.520+)

### Per-rider distance (CarpoolCodec v2)

- `Rider`: added `distanceKm: Double? = null` — individual shared distance. `effectiveDistance(fallback)` returns own if >0 else trip's total.
- `FORMAT_VERSION = 2`, codec:
  - Encode: `c2|idMs|dateUtc|tripDistance|tripId|name~amount~dist;...` — dist omitted when null (backward compat).
  - Decode: accepts `c1` (old, 2-part rider) and `c2` (2 or 3 parts). Old lines: distance null → fallback to trip distance.
  - Escaping still via `\p \n \s \w` for `| \n ; ~`.
- Monthly roll-up keeps trip distance as total; per-rider distance only for ₹/km display.
- Tests: `CarpoolCodecTest.roundTripSurvivesHostileNamesAndFourRiders` now includes distances + cost/km assertion, plus `oldFormatWithoutRiderDistanceStillDecodes` (c1 line → fallback).

UI:

- `CarpoolViews.CarpoolDialog`:
  - Added `riderDistances` SnapshotStateList, per-rider "Rider km (optional)" field with supporting text "Leave blank = same as trip".
  - Live ₹/km preview next to field: `₹amount / distance`.
  - Total trip field renamed "Total trip distance km" with hint "Trip's full distance; per-rider below may differ".
  - Save builds `Rider(name, amount, riderDistKm)` — amount >0 required, distance optional >0.

- `CarpoolEntryRows` (trip card):
  - Shows "Trip shared X km" then per rider: name bold, amount, second line "Y km shared, ₹Z/km".

- `CarpoolScreen` list:
  - Shows "₹amount (Y km, ₹Z/km)" per rider, so monthly view also shows individual.

Result: Harsha 210 for 10 km = ₹21/km, Tanuja 147 for 20 km = ₹7.35/km, Pavan 123 for 30 km = ₹4.1/km — honest, not averaged.

### Auto backup on car-pool entry

- Before: only `FuelCostsScreen` called `triggerCloudBackupIfEnabled` after fuel save. Car-pool saves wrote `carpool_prefs` but Drive snapshot stayed stale until daily gate.
- Fix:
  - `MainViewModel.saveCarpool`: after linking + `carpoolRepository.save`, launches:
    - If `driveTreeUri` set (OAuth-free SAF folder linked), `DriveBackupClient.sendBackup` immediately — full ZIP: trips, raw logs, fuel ledger, car-pool ledger, expenses, settings.
    - Plus `cloudBackupManager.performAutoBackupIfNeeded` (Google Drive API path).
    - Best-effort, never blocks save.
  - Added `deleteCarpool(id)` with same backup trigger.
  - `TripDetailScreen` now calls `viewModel.saveCarpool` (was repo directly) so linking + backup both happen.
  - `CarpoolScreen` delete uses `deleteCarpool`.

Owner request satisfied: as soon as Harsha/Tanuja/Pavan entry saved, Drive backup contains new `app_data_snapshot.json` with `carpool_prefs` — kill-proof.

## Verification

- CI @ 8cb2f6d: push + PR both `completed/success`, digest: `100 suite(s), 810 test(s): 0 failed, 1 skipped` (was 809, +1 for old-format compat test).
- Manual: create ride with per-rider km 10/20/30, save → trip card shows per-rider km + ₹/km, monthly shows same, prefs file contains `c2|...|Harsha~210.00~10.00;...`, old `c1` line still loads.
- Backup: after save, `cloud_drive_backup` or Drive folder gets new `kylaq-obd-backup-*.zip` containing `app_data_snapshot.json` with updated `carpool_log`.
