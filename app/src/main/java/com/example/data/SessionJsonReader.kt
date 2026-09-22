package com.example.data

import android.util.JsonReader
import android.util.JsonToken
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
 * [readMetadata] streams with [JsonReader]: `sessionMetadata` is captured field by field
 * and the transactions array is `skipValue()`d, so peak memory is one row, not one drive.
 * The transaction count comes from counting the transactions CSV's data rows - exactly
 * what `txArray.length()` used to report.
 *
 * [writeRenamedSession] avoids materialising too, by surgery instead of a token copier:
 * [JsonExporter] writes `sessionMetadata` as the FIRST root key, so a bounded 64 KiB head
 * window always contains the `sessionName` field; it is replaced in that window and the
 * rest of the file is copied through byte-for-byte. (A JsonWriter token copier was tried
 * first and proved silently wrong in CI - the regression test kept the honesty.)
 */
object SessionJsonReader {

    private const val HEAD_WINDOW_CHARS = 65_536

    private val SESSION_NAME_FIELD = Regex("\"sessionName\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"")

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
     * Rewrites the document with `sessionMetadata.sessionName` replaced by [newName].
     * Peak memory is the head window plus a small copy buffer - renaming a 90-minute
     * trip no longer builds its object graph (the old path did `readText()` +
     * `JSONObject` + `toString(2)` on the whole drive).
     *
     * @return false only if no `sessionName` field sat inside the head window, i.e. a
     * document [JsonExporter] did not write; callers treat that as "nothing renamed"
     * and the file is left exactly as it was.
     */
    fun writeRenamedSession(input: Reader, out: Writer, newName: String): Boolean {
        val window = CharArray(HEAD_WINDOW_CHARS)
        var filled = 0
        while (filled < window.size) {
            val r = input.read(window, filled, window.size - filled)
            if (r < 0) break
            filled += r
        }
        val head = String(window, 0, filled)
        val replaced = SESSION_NAME_FIELD.replaceFirst(head, "\"sessionName\":" + quote(newName))
        if (replaced == head) return false
        out.write(replaced)
        if (filled == window.size) {
            val rest = CharArray(8192)
            while (true) {
                val r = input.read(rest)
                if (r < 0) break
                out.write(rest, 0, r)
            }
        }
        out.flush()
        return true
    }

    /** Minimal JSON string quoting: escapes quote, backslash and control characters. */
    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun readMetaObject(jr: JsonReader): RecordingMetadata {
        var sessionId = ""
        var sessionName: String? = null
        var vehicle: String? = null
        var profile: String? = null
        var adapter: String? = null
        var protocol: String? = null
        var canBitrate: String? = null
        var vehicleId: String? = null
        var startTimeUtc = ""
        var endTimeUtc: String? = null
        var appVersion: String? = null
        var maxAltitudeM: Double? = null
        var minAltitudeM: Double? = null
        var minVoltageV: Double? = null
        var maxVoltageV: Double? = null
        var startFuelLevelPct: Double? = null
        var endFuelLevelPct: Double? = null
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "sessionId" -> sessionId = nextStringOrNull(jr) ?: ""
                "sessionName" -> sessionName = nextStringOrNull(jr)
                "vehicle" -> vehicle = nextStringOrNull(jr)
                "vehicleId" -> vehicleId = nextStringOrNull(jr)
                "profile" -> profile = nextStringOrNull(jr)
                "adapter" -> adapter = nextStringOrNull(jr)
                "protocol" -> protocol = nextStringOrNull(jr)
                "canBitrate" -> canBitrate = nextStringOrNull(jr)
                "startTimeUtc" -> startTimeUtc = nextStringOrNull(jr) ?: ""
                "endTimeUtc" -> endTimeUtc = nextStringOrNull(jr)
                "appVersion" -> appVersion = nextStringOrNull(jr)
                "maxAltitudeM" -> maxAltitudeM = nextDoubleOrNull(jr)
                "minAltitudeM" -> minAltitudeM = nextDoubleOrNull(jr)
                "minVoltageV" -> minVoltageV = nextDoubleOrNull(jr)
                "maxVoltageV" -> maxVoltageV = nextDoubleOrNull(jr)
                "startFuelLevelPct" -> startFuelLevelPct = nextDoubleOrNull(jr)
                "endFuelLevelPct" -> endFuelLevelPct = nextDoubleOrNull(jr)
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        // Defaults mirror the old JSONObject loader exactly, so the UI reads identically.
        //
        // DATA-LOSS FIX (owner 2026-09-22): `endTimeUtc` was parsed into a local and then dropped
        // on the floor, and the altitude / voltage windows were skipped outright, so every trip
        // the app listed from disk looked like a drive that never ended and never climbed. That
        // is not cosmetic - it is what the merge review reads to work out the gap between two
        // fragments, and a fragment with no end stamp cannot be reviewed at all. The fuel-level
        // pair is read for the same reason it is written.
        return RecordingMetadata(
            sessionId = sessionId,
            sessionName = sessionName ?: "Session $sessionId",
            vehicle = vehicle ?: "Škoda Kylaq 1.0 TSI",
            vehicleId = vehicleId?.ifBlank { null },
            profile = profile ?: "India-Market 1.0 TSI",
            adapter = adapter ?: "ELM327 v1.5",
            protocol = protocol ?: "ISO 15765-4",
            canBitrate = canBitrate ?: "500 kbps",
            startTimeUtc = startTimeUtc,
            endTimeUtc = endTimeUtc?.ifBlank { null },
            appVersion = appVersion ?: "1.0",
            maxAltitudeM = maxAltitudeM,
            minAltitudeM = minAltitudeM,
            minVoltageV = minVoltageV,
            maxVoltageV = maxVoltageV,
            startFuelLevelPct = startFuelLevelPct,
            endFuelLevelPct = endFuelLevelPct
        )
    }

    private fun nextStringOrNull(jr: JsonReader): String? =
        if (jr.peek() == JsonToken.NULL) {
            jr.nextNull()
            null
        } else {
            jr.nextString()
        }

    /**
     * A nullable number. Every token shape is CONSUMED before returning, including the ones this
     * app never writes: a reader left parked on an unexpected token desynchronises the whole
     * metadata object, and a null window is a far cheaper mistake than a misread one.
     */
    private fun nextDoubleOrNull(jr: JsonReader): Double? =
        when (jr.peek()) {
            JsonToken.NULL -> {
                jr.nextNull()
                null
            }
            JsonToken.NUMBER -> jr.nextDouble().takeIf { !it.isNaN() }
            JsonToken.BOOLEAN -> {
                jr.nextBoolean()
                null
            }
            JsonToken.STRING -> jr.nextString().toDoubleOrNull()
            else -> {
                jr.skipValue()
                null
            }
        }
}
