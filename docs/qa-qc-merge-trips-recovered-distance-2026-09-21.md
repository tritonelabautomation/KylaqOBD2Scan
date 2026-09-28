# QA/QC — merge trips + recovered distance via odometer (2026-09-21, second report same day)

## Owner report (same day, second half)

After the one-drive-one-trip fix (1.0.514) shipped, the owner opened the trip that had been shredded:

- Screenshots:
  - Trip list: `Kylaq Run 07:36` (3 tx), `Kylaq Run 07:36 (recovered)` (13 717 tx), `Recovered Run 08:00` (45 957 tx) — one 31 km drive, three rows.
  - Recovered detail: **45957 transactions but 0.1 km / 0 min / bands 0m / coasting 0s / idling 0s / fuel 0.03L 20.2 L/100km**, avg 17 / max 31.
  - Cluster: odo 3735 km.

Owner: "Recovered is not showing actual kms also there is no option make two trips to merge and make it one trip".

Two bugs in one screenshot:

1. **No stitching UI** — recovery saved the fragments, but the UI had no way to join them back into the single drive the cluster shows.
2. **Distance 0.1 km** — 45k transactions must be ~30 km, not 0.1 km. Duration 0 min also wrong, indicating timestamp collapse in the recovered summary (speed integration dt=0).

## Root cause

- **Shred already happened** before 1.0.514 — resume window prevents future shreds, but history stays shredded.
- **Distance** — `TripFuelSummary` integrated speed (PID 010D) over dt. If dt collapses to 0 (identical wall millis for all samples, or MAX_GAP filter), distance stays 0. Odometer PID 01A6 (proven on this car, `ProvenChannels.ODOMETER`) was available in the same trip and shows the cluster's own kilometres, but was not used as a fallback.
- **Merge missing** — `RecordingManager` had `deleteRecording` but no `mergeSessions`.

## Fix shipped (1.0.519)

### 1. `RecordingManager.mergeSessions(ids, newName?)`

- Loads each `session_<id>/<id>_transactions.csv` via `CsvExporter.readTransactionsFromCsv`.
- Wall-anchors every row: `SessionRecoveryPolicy.wallEpochMs(stamp, mono)` — journal files store uptime in monotonic column.
- Sorts combined list by true instant (wall millis), not file order — fragments may overlap or be out of order.
- Replays wide rows via same pure fold as live: `replaySamples { mergeSample().copy(altitudeM) }`.
- Finalizes as new trip: `Merged Run <display> (N trips, M tx)` with new UUID, then deletes old dirs + `tripRepository.deleteTrip(oldId)` + `journal.discard(oldId)`, reloads list.
- Returns `SavedRecording?` — null when <2 valid trips.

### 2. ViewModel + UI

- `MainViewModel`: `_isMerging`, `isMerging`, `_mergeNotice`, `mergeNotice`, `clearMergeNotice()`, `mergeRecordings(ids)`.
- `RecordingsScreen`:
  - Selection mode: `selectedIds: Set<String>`, `selectionMode`.
  - Long-press card starts selection, tap toggles, checkbox + cyan border/background when selected.
  - Top banner (CyberCyan 0.14) with `CallMerge` icon (Merge icon does not exist in material), text "31 km fragments become one", Merge button enabled `>=2 && !isMerging`, Cancel button.
  - `RecordingItemCard` now `@OptIn(ExperimentalFoundationApi)` with `combinedClickable` onClick/onLongClick, `isSelected`, `selectionMode`, `onToggleSelect`.
  - Toast on `mergeNotice` clears selection.

### 3. `TripFuelSummary` odometer fallback

- Reads `01A6` series, sorts by timestamp, diff = last - first.
- If diff in 0.1..1000 km, that IS distance (cluster's own), `odoDistanceUsed=true`, speed integration skipped for distance.
- Preserves movingSeconds/idle/coast/histogram from speed series even when odo used.
- Result: recovered 45 957-tx trip that previously showed 0.1 km now shows ~31 km (odo diff), fuel L/100km correct, even if speed dt collapsed.

## Verification

- CI: 100 suites / 809 tests / 0 failed / 1 skipped @ 4f5690f (push + PR both GREEN).
- Manual: select the two recovered fragments (13 717 + 45 957) + 3-tx stub → Merge → one trip with ~59k tx, distance from odometer (~31 km), duration from earliest to latest wall time, fuel/trends from replayed rows.
- Future drives stay one trip via resume window (1.0.514); this handles already-shredded history.
- No duplicate zip entry regression: `ZipExporter` dedup set still in place (1.0.518).

## Honest boundaries

- Merge deletes old trips — files + Room + journal. No undo, but old trips were fragments of same drive.
- Odometer fallback only when 01A6 answered and diff plausible. If car does not support 01A6, speed integration remains.
- Duration still from samples min/max; if timestamps collapsed, merged trip fixes it because combined transactions span true window. Single recovered trip with collapsed timestamps still shows 0 min until merged — acceptable because merge is the intended repair for shredded history.
- One-drive-one-trip guarantee still holds: resume window + deferred sweep, plus manual merge for pre-fix history.
