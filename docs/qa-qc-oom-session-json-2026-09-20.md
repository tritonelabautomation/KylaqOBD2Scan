# QA/QC — the 256 MB OOM: session JSON was materialised whole at every start (2026-09-20)

## Owner crash log (summarised from the four shared `crash_*.txt` records)

- App v1.0.490, moto edge 20, Android 13.
- `java.lang.OutOfMemoryError`: heap limit 256 MB exhausted, <1% free.
- Thrown on the UI/render thread inside a Choreographer frame callback, during Compose
  recomposition: `MetricRowWithSource` → `TelemetrySectionCard` →
  `TelemetryDashboardContent` → `DashboardScreen`.

## Reading the stack honestly

An OOM thrown *during recomposition* names the allocation that failed, not the memory
that was missing. Something else already held the heap. The dashboard rows build tiny
strings; they are the last straw, not the elephant.

## The elephant, code-proven

Session JSON carries the WHOLE drive: `sessionMetadata` plus a `transactions` array with
every OBD row (a TX and an RX/error record per query — tens of thousands of rows on a
long drive). `RecordingManager.loadSavedRecordings()` — which runs on **every app start**
and after every recovery — opened each session with:

```kotlin
val jsonStr = jsonFile.readText()      // entire file as one String (UTF-16: 2x bytes)
val root = JSONObject(jsonStr)         // entire object graph: one HashMap+String per row
```

Peak per session ≈ file size × (1 string + ~10 object-graph) — a 90-minute drive alone
can exceed the 256 MB heap; with the owner's trip pile (including recovered runs) the
start-time walk filled the heap on the IO thread, and the next UI allocation — Compose
building a dashboard row — threw the OOM. This is also the most probable identity of the
original "again app is crashing when I open it" loop: it grew with the trip count, not
with any single drive.

`renameRecording()` had the same shape (`readText()` + `JSONObject` + `toString(2)`) on
user action.

## Fix shipped

`SessionJsonReader` (android.util.JsonReader/JsonWriter, streaming):

- `readMetadata` — captures `sessionMetadata` field by field, `skipValue()`s the
  transactions array and everything else. Peak memory: one row.
- `countTransactionRows` — counts the transactions CSV's data rows line by line without
  holding any row; that count is exactly what `txArray.length()` used to report.
- `writeRenamedSession` — token-by-token copy with `sessionMetadata.sessionName`
  replaced, so renaming a long trip no longer re-materialises it.
- `loadSavedRecordings` and `renameRecording` now use only these; defaults mirror the old
  JSONObject loader's so every screen reads identically.

## Regression test

`KilledSessionRecoveryTest.sessionJsonIsStreamedNotMaterialisedAndRenameSurvives`
(Robolectric): recover a drive, then assert the streamed metadata equals what the session
holds, the streamed count equals the frame count, `savedRecordings` reports the same
count through the fixed loader, and a streamed rename keeps the new name with every
transaction still on disk.

## Honest limits

- Files on disk are unchanged (still whole-drive JSON); only reading/writing is streamed.
  Shrinking what finalize writes (metadata + counts, with the CSVs as the data) is a
  follow-up that must keep `ZipImporter` working off the CSVs first.
- `ZipImporter` still parses a session JSON whole on import — user-initiated, one file,
  and bounded by what a backup zip carries; noted for the same follow-up.
- If another crash log shows a different stack (three more records were shared), that
  cause is separate and will be fixed on its own evidence.
