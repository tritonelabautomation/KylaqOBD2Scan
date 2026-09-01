package com.example

import com.example.ai.PrivacyFilter
import com.example.ai.RuleBasedAnalysisEngine
import com.example.data.ZipExporter
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.TripEntity
import com.example.model.*
import com.example.protocol.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleUnitTest {

    @Test
    fun testContinuousCanFrameParsing_RealEcuLog() {
        // 010C -> 7E904410C0000 / 7E804410C0000
        val frameRpm7E9 = CanFrameParser.parseFrame("7E904410C0000")
        assertEquals("7E9", frameRpm7E9.canId)
        assertEquals(IsoTpPciType.SINGLE_FRAME, frameRpm7E9.pciType)
        assertEquals(listOf(0x41, 0x0C, 0x00, 0x00), frameRpm7E9.payloadBytes)

        val frameRpm7E8 = CanFrameParser.parseFrame("7E804410C0000")
        assertEquals("7E8", frameRpm7E8.canId)
        assertEquals(IsoTpPciType.SINGLE_FRAME, frameRpm7E8.pciType)
        assertEquals(listOf(0x41, 0x0C, 0x00, 0x00), frameRpm7E8.payloadBytes)

        // 010D -> 7E803410D00
        val frameSpeed = CanFrameParser.parseFrame("7E803410D00")
        assertEquals("7E8", frameSpeed.canId)
        assertEquals(listOf(0x41, 0x0D, 0x00), frameSpeed.payloadBytes)

        // 010B -> 7E803410B5E (MAP: 0x5E = 94 kPa)
        val frameMap = CanFrameParser.parseFrame("7E803410B5E")
        assertEquals("7E8", frameMap.canId)
        assertEquals(listOf(0x41, 0x0B, 0x5E), frameMap.payloadBytes)

        // 0111 -> 7E80341112B (Throttle: 0x2B = 43 -> 16.9%)
        val frameThrottle = CanFrameParser.parseFrame("7E80341112B")
        assertEquals("7E8", frameThrottle.canId)
        assertEquals(listOf(0x41, 0x11, 0x2B), frameThrottle.payloadBytes)

        // 0105 -> 7E80341058C (Coolant: 0x8C = 140 -> 100 °C)
        val frameCoolant = CanFrameParser.parseFrame("7E80341058C")
        assertEquals("7E8", frameCoolant.canId)
        assertEquals(listOf(0x41, 0x05, 0x8C), frameCoolant.payloadBytes)

        // 010F -> 7E803410F63 (IAT: 0x63 = 99 -> 59 °C)
        val frameIat = CanFrameParser.parseFrame("7E803410F63")
        assertEquals("7E8", frameIat.canId)
        assertEquals(listOf(0x41, 0x0F, 0x63), frameIat.payloadBytes)

        // 0142 -> 7E9044142308E / 7E80441422FE4
        val frameVolt7E9 = CanFrameParser.parseFrame("7E9044142308E")
        assertEquals("7E9", frameVolt7E9.canId)
        assertEquals(listOf(0x41, 0x42, 0x30, 0x8E), frameVolt7E9.payloadBytes)

        val frameVolt7E8 = CanFrameParser.parseFrame("7E80441422FE4")
        assertEquals("7E8", frameVolt7E8.canId)
        assertEquals(listOf(0x41, 0x42, 0x2F, 0xE4), frameVolt7E8.payloadBytes)
    }

    @Test
    fun testSpaceSeparatedCanFrames() {
        val frame = CanFrameParser.parseFrame("7E8 04 41 0C 0F A0")
        assertEquals("7E8", frame.canId)
        assertEquals(IsoTpPciType.SINGLE_FRAME, frame.pciType)
        assertEquals(listOf(0x41, 0x0C, 0x0F, 0xA0), frame.payloadBytes)
    }

    @Test
    fun testPidDecoding_EcuTelemetryValues() {
        val pids = DefaultPidDefinitions.getDefaults().associateBy { it.id }

        // Test RPM: 0x0FA0 = 4000 -> 4000 / 4 = 1000 RPM
        val rpmDef = pids["010C"]!!
        val decodedRpm = PidDecoder.decode(rpmDef, listOf(0x41, 0x0C, 0x0F, 0xA0))
        assertEquals(1000.0, decodedRpm.numericValue!!, 0.01)
        assertEquals("1000", decodedRpm.displayValue)

        // Test MAP: 0x5E = 94 kPa
        val mapDef = pids["010B"]!!
        val decodedMap = PidDecoder.decode(mapDef, listOf(0x41, 0x0B, 0x5E))
        assertEquals(94.0, decodedMap.numericValue!!, 0.01)
        assertEquals("94", decodedMap.displayValue)

        // Test Coolant: 0x8C = 140 -> 100 °C
        val coolantDef = pids["0105"]!!
        val decodedCoolant = PidDecoder.decode(coolantDef, listOf(0x41, 0x05, 0x8C))
        assertEquals(100.0, decodedCoolant.numericValue!!, 0.01)
        assertEquals("100", decodedCoolant.displayValue)

        // Test Voltage: 0x2FE4 = 12260 -> 12.26 V
        val voltDef = pids["0142"]!!
        val decodedVolt = PidDecoder.decode(voltDef, listOf(0x41, 0x42, 0x2F, 0xE4))
        assertEquals(12.26, decodedVolt.numericValue!!, 0.01)
        assertEquals("12.26", decodedVolt.displayValue)
    }

    @Test
    fun testIsoTpMultiFrameReassembly() {
        val lines = listOf(
            "7E8 10 14 49 02 01 54 4D 42",
            "7E8 21 41 41 31 32 33 34 35",
            "7E8 22 36 37 38 39 00 00 00"
        )
        val messages = IsoTpParser.reassembleLines(lines)
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals("7E8", msg.canId)
        assertTrue(msg.isComplete)
        assertEquals(20, msg.totalExpectedLength)
        assertEquals(0x49, msg.reconstructedBytes[0])
        assertEquals(0x02, msg.reconstructedBytes[1])
    }

    @Test
    fun testSafetyValidator_RejectsWriteCommands() {
        assertTrue(SafetyValidator.validateCommand("010C") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("0170") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATSP6") is ValidationResult.Allowed)
        assertTrue(SafetyValidator.validateCommand("ATSH7DF") is ValidationResult.Allowed)

        // Block Mode 04 (Clear DTCs)
        assertTrue(SafetyValidator.validateCommand("04") is ValidationResult.Rejected)
        // Block UDS write / actuator test
        assertTrue(SafetyValidator.validateCommand("2E 01 02") is ValidationResult.Rejected)
        assertTrue(SafetyValidator.validateCommand("2F 01 02") is ValidationResult.Rejected)
        assertTrue(SafetyValidator.validateCommand("31 01") is ValidationResult.Rejected)
    }

    @Test
    fun testRuleBasedAiDoctor_Analysis() = runBlocking {
        val trip = TripEntity(
            id = "test_trip_1",
            title = "Test Drive",
            vehicleName = "Škoda Kylaq 1.0 TSI",
            adapterName = "OBDLink CX",
            protocolName = "ISO 15765-4 (CAN 11/500)",
            startTimeUtc = "2026-09-01T05:00:00Z",
            endTimeUtc = "2026-09-01T05:20:00Z",
            durationSeconds = 1200L,
            maxRpm = 4500.0,
            maxSpeedKmh = 105.0,
            maxCoolantC = 92.0,
            avgVoltageV = 14.2,
            sampleCount = 100,
            rawLogCount = 500,
            status = "COMPLETED",
            healthScore = 95
        )

        val samples = listOf(
            TelemetrySampleEntity(
                tripId = "test_trip_1",
                timestamp = 1000L,
                timestampUtc = "2026-09-01T05:00:01Z",
                pid = "010C",
                parameterName = "Engine RPM",
                rawHex = "410C1388",
                numericValue = 1250.0,
                displayValue = "1250",
                unit = "RPM",
                ecuCanId = "7E8"
            ),
            TelemetrySampleEntity(
                tripId = "test_trip_1",
                timestamp = 2000L,
                timestampUtc = "2026-09-01T05:00:02Z",
                pid = "0105",
                parameterName = "Coolant Temp",
                rawHex = "410584",
                numericValue = 92.0,
                displayValue = "92",
                unit = "°C",
                ecuCanId = "7E8"
            ),
            TelemetrySampleEntity(
                tripId = "test_trip_1",
                timestamp = 3000L,
                timestampUtc = "2026-09-01T05:00:03Z",
                pid = "0142",
                parameterName = "Voltage",
                rawHex = "41423850",
                numericValue = 14.2,
                displayValue = "14.2",
                unit = "V",
                ecuCanId = "7E8"
            )
        )

        val doctor = RuleBasedAnalysisEngine()
        val report = doctor.analyzeTrip(trip, samples, emptyList())
        assertNotNull(report)
        assertEquals("NORMAL", report.overallHealth)
        assertTrue(report.healthScore >= 90)
        assertTrue(report.engineBehavior.contains("nominal", ignoreCase = true) || report.engineBehavior.contains("4500"))
        assertTrue(report.temperatureBehavior.contains("92"))
    }

    @Test
    fun testPrivacyFilter_Anonymization() {
        val trip = TripEntity(
            id = "test_trip_priv",
            title = "Commute",
            startTimeUtc = "2026-09-01T05:00:00Z",
            durationSeconds = 600L,
            maxRpm = 3000.0,
            maxSpeedKmh = 60.0,
            maxCoolantC = 90.0,
            avgVoltageV = 14.1
        )
        val samples = listOf(
            TelemetrySampleEntity(
                tripId = "test_trip_priv",
                timestamp = 1000L,
                timestampUtc = "2026-09-01T05:00:01Z",
                pid = "010C",
                parameterName = "Engine RPM",
                rawHex = "410C1388",
                numericValue = 1250.0,
                displayValue = "1250",
                unit = "RPM",
                ecuCanId = "7E8"
            )
        )

        val json = PrivacyFilter.anonymizeTripData(trip, samples)
        assertNotNull(json)
        assertTrue(json.has("vehicleProfile"))
        assertTrue(json.has("sampledTelemetry"))
        assertFalse(json.toString().contains("test_trip_priv"))
    }

    @Test
    fun testZipExporter_CreatesValidArchive() {
        val tempDir = File.createTempFile("obd_test_zip", "").apply {
            delete()
            mkdirs()
        }

        val file1 = File(tempDir, "file1.txt").apply { writeText("Hello OBD") }
        val file2 = File(tempDir, "file2.csv").apply { writeText("Timestamp,RPM\n1000,1200") }
        val zipFile = File(tempDir, "bundle.zip")

        ZipExporter.createTripZip(zipFile, listOf(file1, file2))
        assertTrue(zipFile.exists())
        assertTrue(zipFile.length() > 0)

        val zip = ZipFile(zipFile)
        assertNotNull(zip.getEntry("file1.txt"))
        assertNotNull(zip.getEntry("file2.csv"))
        zip.close()

        tempDir.deleteRecursively()
    }
}
