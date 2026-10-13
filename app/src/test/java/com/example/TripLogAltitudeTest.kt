package com.example

import com.example.data.CsvExporter
import com.example.data.GpsData
import com.example.data.JsonExporter
import com.example.data.SessionTime
import com.example.model.RecordingMetadata
import com.example.model.SynchronizedSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GPS altitude in the trip log (owner 2026-09-15: "did you add altitude info from GPS into
 * trip log?").
 *
 * Altitude was already captured per trip and persisted to the database (trips.maxAltitudeM /
 * minAltitudeM, MIGRATION_9_10) and shown on the trip summary, but it never reached the trip
 * LOG FILES: the session JSON carried no altitude window and the samples CSV had no elevation
 * column. So the data survived on the phone but not a backup - and the very next thing the
 * owner does is backup -> uninstall -> import, which would have silently stripped elevation
 * from every historical trip.
 *
 * These tests pin the round trip, plus the two honesty rules that go with it:
 *  - no altitude is recorded as JSON null / empty CSV cell, never as 0.0 (a fabricated
 *    "0 m" reads as sea level, and the summary must keep showing "-- m");
 *  - an imported trip keeps the time window it was recorded in, instead of being stamped
 *    "now, 60 seconds long" (duration feeds average speed, idle share and L/h trends).
 */
class TripLogAltitudeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun metadata(
        maxAltitudeM: Double? = null,
        minAltitudeM: Double? = null
    ) = RecordingMetadata(
        sessionId = "2df90142",
        sessionName = "Kylaq Run 2026-09-15 07:26",
        startTimeUtc = "2026-09-15T07:26:04.123Z",
        endTimeUtc = "2026-09-15T08:11:39.456Z",
        maxAltitudeM = maxAltitudeM,
        minAltitudeM = minAltitudeM
    )

    private fun exportAndReadMetadata(meta: RecordingMetadata): JSONObject {
        val file = tmp.newFile("${meta.sessionId}.json")
        JsonExporter.exportToJson(file, meta, emptyList())
        val root = JSONObject(file.readText())
        return root.getJSONObject("sessionMetadata")
    }

    @Test
    fun `session json carries the trip altitude window`() {
        val metaObj = exportAndReadMetadata(metadata(maxAltitudeM = 612.4, minAltitudeM = 498.1))

        assertEquals(612.4, JsonExporter.nullableDouble(metaObj, "maxAltitudeM")!!, 1e-9)
        assertEquals(498.1, JsonExporter.nullableDouble(metaObj, "minAltitudeM")!!, 1e-9)
    }

    @Test
    fun `session json records missing altitude as null, never as zero`() {
        val metaObj = exportAndReadMetadata(metadata())

        assertTrue("key must exist so an import can tell 'no data' from 'old file'", metaObj.has("maxAltitudeM"))
        assertTrue(metaObj.isNull("maxAltitudeM"))
        assertTrue(metaObj.isNull("minAltitudeM"))
        assertNull(JsonExporter.nullableDouble(metaObj, "maxAltitudeM"))
        assertNull(JsonExporter.nullableDouble(metaObj, "minAltitudeM"))
    }

    @Test
    fun `nullableDouble does not invent a value for absent keys`() {
        // Backups written before this change have no altitude keys at all.
        val legacy = JSONObject("""{"sessionId":"2df90142","appVersion":"1.0"}""")

        assertNull(JsonExporter.nullableDouble(legacy, "maxAltitudeM"))
        assertNull(JsonExporter.nullableDouble(legacy, "minAltitudeM"))
        assertFalse(legacy.has("maxAltitudeM"))
    }

    @Test
    fun `samples csv has an altitude column and blanks where gps had none`() {
        val file = tmp.newFile("2df90142_samples.csv")
        val samples = listOf(
            SynchronizedSample(
                timestampUtc = "2026-09-15T07:26:05.000Z",
                timestampMonotonic = 1000L,
                rpm = 970.0,
                speedKmh = 0.0,
                altitudeM = 511.2
            ),
            SynchronizedSample(
                timestampUtc = "2026-09-15T07:26:06.000Z",
                timestampMonotonic = 2000L,
                rpm = 1010.0,
                speedKmh = 4.0,
                altitudeM = null
            )
        )

        CsvExporter.exportSynchronizedSamplesToCsv(file, samples)
        val lines = file.readLines()

        assertTrue("altitude_m must be the last column", lines[0].endsWith(",altitude_m"))
        assertTrue("existing column order must stay stable", lines[0].startsWith("timestamp_utc,RPM,speed_kmh,"))
        assertTrue("measured altitude is written", lines[1].endsWith(",511.2"))
        assertTrue("absent altitude stays an empty cell, not 0.0", lines[2].endsWith(","))
        assertEquals(3, lines.size)
    }

    @Test
    fun `gps fix without altitude is not published as zero metres`() {
        val noFix = GpsData()
        assertFalse(noFix.isAvailable)
        assertFalse("the 0.0 default must be flagged as 'no altitude'", noFix.hasAltitude)

        val fixWithAltitude = GpsData(
            speedKmh = 62.0f,
            altitudeMeters = 512.75,
            hasAltitude = true,
            isAvailable = true
        )
        assertTrue(fixWithAltitude.hasAltitude)
        assertEquals(512.75, fixWithAltitude.altitudeMeters, 1e-9)
    }

    @Test
    fun `imported trip keeps the recorded time window instead of now and 60 seconds`() {
        val start = SessionTime.parseMillis("2026-09-15T07:26:04.123Z")
        val end = SessionTime.parseMillis("2026-09-15T08:11:39.456Z")

        assertTrue(start != null && end != null)
        assertEquals(123L, start!! % 1000L)
        assertEquals(2735L, SessionTime.durationSeconds("2026-09-15T07:26:04.123Z", "2026-09-15T08:11:39.456Z"))
    }

    @Test
    fun `session time parses stamps without milliseconds`() {
        val withMillis = SessionTime.parseMillis("2026-09-15T07:26:04.123Z")
        val withoutMillis = SessionTime.parseMillis("2026-09-15T07:26:04Z")

        assertTrue(withMillis != null && withoutMillis != null)
        assertEquals(123L, withMillis!! - withoutMillis!!)
    }

    @Test
    fun `session time refuses garbage instead of guessing`() {
        assertNull(SessionTime.parseMillis(null))
        assertNull(SessionTime.parseMillis(""))
        assertNull(SessionTime.parseMillis("   "))
        assertNull(SessionTime.parseMillis("not-a-timestamp"))
        assertNull(SessionTime.parseMillis("2026-13-45T99:99:99Z"))
    }

    @Test
    fun `duration is null when the window is unknown or inverted`() {
        assertNull(SessionTime.durationSeconds("2026-09-15T07:26:04.123Z", null))
        assertNull(SessionTime.durationSeconds(null, "2026-09-15T08:11:39.456Z"))
        assertNull(
            "a corrupt file must not produce a negative trip duration",
            SessionTime.durationSeconds("2026-09-15T08:11:39.456Z", "2026-09-15T07:26:04.123Z")
        )
        assertEquals(0L, SessionTime.durationSeconds("2026-09-15T07:26:04.123Z", "2026-09-15T07:26:04.123Z"))
    }

    @Test
    fun `altitude window survives a metadata copy unchanged when absent`() {
        // RecordingManager re-copies metadata at stop; a trip with no GPS fix must stay null
        // all the way into the JSON rather than picking up a default.
        val copied = metadata().copy(sessionName = "Renamed run")

        assertNull(copied.maxAltitudeM)
        assertNull(copied.minAltitudeM)
        assertEquals("Renamed run", copied.sessionName)
    }
}
