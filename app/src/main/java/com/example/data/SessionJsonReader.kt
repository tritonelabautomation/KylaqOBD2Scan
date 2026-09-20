package com.example.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.example.model.RecordingMetadata
import java.io.File
import java.io.Reader
import java.io.Writer

/**
 * Streaming reader/writer for session JSON - the fix for the owner's 2026-09-20 crash
 * log: `java.lang.OutOfMemoryError` at the 256 MB heap, thrown on the UI thread while the
 * telemetry dashboard recomposed (`MetricRowWithSource` -> `TelemetryDashboardContent` ->
 * `DashboardScreen` in a Choreographer frame).
 *
 * That stack is where the LAST allocation failed, not the cause. The cause: session JSON
 * carries the whole drive - a `transactions` array with every OBD row, tens of thousands
 * of objects - and [RecordingManager.loadSavedRecordings] opened EVERY session file with
 * `readText()` + `JSONObject` on each app start, materialising entire drives at once
 * (tens of MB of text, hundreds of MB of object graph). Once trips piled up, opening the
 * app filled the heap on the IO thread and the next UI allocation threw the OOM - which
 * is also why the app "crashed when opened" in a loop before the crash journal existed.
 *
 * [JsonReader] streams: `sessionMetadata` is captured field by field, the transactions
 * array is `skipValue()`d, so peak memory is one row, not one drive. The transaction
 * count comes from counting the transactions CSV's data rows - which is exactly what the
 * count always represented. [writeRenamedSession] streams a rename the same way, so
 * renaming a long trip no longer re-materialises it either.
 */
object SessionJsonReader {

    /**
     * Reads only `sessionMetadata`, streaming. Null when the document is absent,
     * unreadable or carries no metadata object - callers skip such a session exactly as
     * the old JSONObject path's catch did.
     */
    fun readMetadata(input: Reader): RecordingMetadata? =
        try {
            var meta: RecordingMetadata? = null
            JsonReader(input).use { jr ->
                jr.beginObject()
                while (jr.hasNext()) {
                    if (jr.nextName() == "sessionMetadata") meta = readMetaObject(jr) else jr.skipValue()
                }
                jr.endObject()
            }
            meta
        } catch (_: Exception) {
            null
        }

    /** Data-row count of a transactions CSV without holding any row: header excluded. */
    fun countTransactionRows(file: File): Int {
        if (!file.isFile) return 0
        var rows = 0
        var first = true
        try {
            file.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (first) {
                        first = false
                        continue
                    }
                    if (line.isNotBlank()) rows++
                }
            }
        } catch (_: Exception) {
            return 0
        }
        return rows
    }

    /**
     * Copies the whole document stream-for-stream, replacing `sessionMetadata.sessionName`
     * with [newName]. Peak memory is one token: renaming a 90-minute trip no longer builds
     * its object graph (the old path did `readText()` + `JSONObject` + `toString(2)`).
     */
    fun writeRenamedSession(input: Reader, out: Writer, newName: String) {
        JsonReader(input).use { jr ->
            JsonWriter(out).use { jw ->
                jw.setIndent("  ")
                copyValue(jr, jw, newName, insideMeta = false)
            }
        }
    }

    private fun readMetaObject(jr: JsonReader): RecordingMetadata {
        var sessionId = ""
        var sessionName: String? = null
        var vehicle: String? = null
        var profile: String? = null
        var adapter: String? = null
        var protocol: String? = null
        var canBitrate: String? = null
        var startTimeUtc = ""
        var endTimeUtc: String? = null
        var appVersion: String? = null
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "sessionId" -> sessionId = nextStringOrNull(jr) ?: ""
                "sessionName" -> sessionName = nextStringOrNull(jr)
                "vehicle" -> vehicle = nextStringOrNull(jr)
                "profile" -> profile = nextStringOrNull(jr)
                "adapter" -> adapter = nextStringOrNull(jr)
                "protocol" -> protocol = nextStringOrNull(jr)
                "canBitrate" -> canBitrate = nextStringOrNull(jr)
                "startTimeUtc" -> startTimeUtc = nextStringOrNull(jr) ?: ""
                "endTimeUtc" -> endTimeUtc = nextStringOrNull(jr)
                "appVersion" -> appVersion = nextStringOrNull(jr)
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        // Defaults mirror the old JSONObject loader exactly, so the UI reads identically.
        return RecordingMetadata(
            sessionId = sessionId,
            sessionName = sessionName ?: "Session $sessionId",
            vehicle = vehicle ?: "Škoda Kylaq 1.0 TSI",
            profile = profile ?: "India-Market 1.0 TSI",
            adapter = adapter ?: "ELM327 v1.5",
            protocol = protocol ?: "ISO 15765-4",
            canBitrate = canBitrate ?: "500 kbps",
            startTimeUtc = startTimeUtc,
            endTimeUtc = endTimeUtc,
            appVersion = appVersion ?: "1.0"
        )
    }

    private fun nextStringOrNull(jr: JsonReader): String? =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            null
        } else {
            jr.nextString()
        }

    private fun copyValue(jr: JsonReader, jw: JsonWriter, newName: String, insideMeta: Boolean) {
        when (jr.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                jw.beginObject()
                while (jr.hasNext()) {
                    val name = jr.nextName()
                    jw.name(name)
                    if (insideMeta && name == "sessionName") {
                        jr.skipValue()
                        jw.value(newName)
                    } else {
                        // The child object IS the metadata object exactly when its key is
                        // sessionMetadata - passing anything else (e.g. AND-ing with the
                        // parent's flag) never enters it and the rename silently no-ops,
                        // which is exactly what the regression test caught on first run.
                        copyValue(jr, jw, newName, insideMeta = name == "sessionMetadata")
                    }
                }
                jw.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                jw.beginArray()
                while (jr.hasNext()) copyValue(jr, jw, newName, insideMeta)
                jw.endArray()
            }
            JsonToken.STRING -> jw.value(jr.nextString())
            JsonToken.NUMBER -> {
                val raw = jr.nextString()
                raw.toLongOrNull()?.let { jw.value(it) } ?: jw.value(raw.toDouble())
            }
            JsonToken.BOOLEAN -> jw.value(jr.nextBoolean())
            JsonToken.NULL -> {
                jr.nextNull()
                jw.nullValue()
            }
            else -> jr.skipValue()
        }
    }
}
