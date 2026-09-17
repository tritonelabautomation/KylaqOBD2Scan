package com.example

import com.example.model.DecoderType
import com.example.model.PidDefinition
import com.example.model.StandardPidCatalog
import com.example.protocol.PidDecoder
import com.example.protocol.PidDiscoveryDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests built from the owner's exported PID-discovery JSON of the real Kylaq
 * (VIN MEXKPEPC2TG028855, run 2026-09-16 08:57 IST, exported 2026-09-17).
 *
 * That export is ground truth for what the car said, and it exposed three separate defects
 * that all reported themselves as "29 supported PIDs":
 *
 *  1. ELM327 buffer lag. TX 0100 got a stale garbled frame, TX 0120 got the 41 00 bitmap,
 *     TX 0180 got the 41 A0 bitmap. The 0x00 and 0x20 blocks vanished from the report, so
 *     24 PIDs the car answers on every trip (rpm, speed, coolant, MAP, timing, fuel rate...)
 *     were missing from a report about the car. Root fix: drain stale RX before every TX.
 *
 *  2. Range markers treated as data PIDs. Bit 32 of each block is the "next block exists"
 *     marker, but it was emitted as a supported PID - so the export listed PID 60 and PID 80,
 *     TXed them in the validation phase and decoded the answering bitmap as a measurement:
 *     "Mode 01 PID 60 = 6B 09 00 41" and "Diesel Particulate Filter Temperature =
 *     00 24 00 0D" on a petrol car.
 *
 *  3. That mis-attribution ate the real 0180 answer, so PID 83 (NOx sensor), 85 (NOx
 *     reagent) and 86 (PM sensor) - all set in the car's real 0180 bitmap 00 24 00 0D - were
 *     never discovered and never validated. They are the genuine new finds of this audit.
 */
class DiscoveryLagAndMarkerRegressionTest {

    private fun pid(id: String, dec: DecoderType, bytes: Int = 2, research: Boolean = false) =
        PidDefinition(
            id = id, service = "01", pid = id.removePrefix("01"),
            name = "Test $id", shortName = id, unit = "",
            dataBytes = bytes, decoderType = dec, isResearch = research,
            defaultIntervalMs = 500L
        )

    // ── 1. The car's real 0180-block bitmap, decoded correctly ────────────────
    @Test
    fun realKylaq0180BitmapYieldsTheThreePidsTheRunNeverDiscovered() {
        // Frame from the owner export: TX 0180 -> RX 7E80641800024000D
        val bitmap = byteArrayOf(0x00, 0x24, 0x00, 0x0D)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x80, bitmap)

        assertEquals(listOf(0x83, 0x85, 0x86, 0x8B, 0x8E), supported)
        assertTrue("NOx sensor bit is set on this car", supported.contains(0x83))
        assertTrue("NOx reagent system bit is set on this car", supported.contains(0x85))
        assertTrue("PM sensor bit is set on this car", supported.contains(0x86))
        assertFalse("the 01A0 range marker is not a data PID", supported.contains(0xA0))
        assertFalse("no next block: bit 32 of 00 24 00 0D is clear",
            PidDiscoveryDecoder.hasNextRange(bitmap, 0x80))
    }

    @Test
    fun rangeMarkerIsNeverATestedDataPid() {
        assertFalse(PidDiscoveryDecoder.allTestedPidsForRange(0x00).contains(0x20))
        assertFalse(PidDiscoveryDecoder.allTestedPidsForRange(0x20).contains(0x40))
        assertFalse(PidDiscoveryDecoder.allTestedPidsForRange(0x80).contains(0xA0))
        assertFalse(PidDiscoveryDecoder.allTestedPidsForRange(0xA0).contains(0xC0))
        // 0x01-0x1F are still all tested, and the last block still ends at 0xFF
        assertEquals(31, PidDiscoveryDecoder.allTestedPidsForRange(0x00).size)
        assertEquals(0x1F, PidDiscoveryDecoder.allTestedPidsForRange(0x00).last())
        assertEquals(0xFF, PidDiscoveryDecoder.allTestedPidsForRange(0xE0).last())
    }

    @Test
    fun ownerExportBitmapsNeverListTheirOwnRangeMarker() {
        // 7E8 41 00 BE 3E A8 13 -> 16 real PIDs, 0x20 is only the marker
        val b00 = byteArrayOf(0xBE.toByte(), 0x3E.toByte(), 0xA8.toByte(), 0x13.toByte())
        val s00 = PidDiscoveryDecoder.decodeSupportedPids(0x00, b00)
        assertEquals(16, s00.size)
        assertFalse(s00.contains(0x20))
        assertTrue(s00.contains(0x0C) && s00.contains(0x0D))
        assertTrue(PidDiscoveryDecoder.hasNextRange(b00, 0x00))

        // 7E8 41 20 BE 3E A8 13 is not in the run (it was lagged away); use the 0140 block
        // 7E8 41 40 FE D0 84 01 -> 12 real PIDs
        val b40 = byteArrayOf(0xFE.toByte(), 0xD0.toByte(), 0x84.toByte(), 0x01)
        val s40 = PidDiscoveryDecoder.decodeSupportedPids(0x40, b40)
        assertEquals(12, s40.size)
        assertFalse(s40.contains(0x60))

        // 7E8 41 60 6B 09 00 41 -> 8 real PIDs incl. torque 62/63 and boost 65
        val b60 = byteArrayOf(0x6B, 0x09, 0x00, 0x41)
        val s60 = PidDiscoveryDecoder.decodeSupportedPids(0x60, b60)
        assertEquals(listOf(0x62, 0x63, 0x65, 0x67, 0x68, 0x6D, 0x70, 0x7A), s60)

        // 7E8 41 A0 14 00 00 00 -> sump temperature A0 and gear A4
        val bA0 = byteArrayOf(0x14, 0x00, 0x00, 0x00)
        val sA0 = PidDiscoveryDecoder.decodeSupportedPids(0xA0, bA0)
        assertEquals(listOf(0xA0, 0xA4), sA0)
    }

    // ── 2. Salvage must not resurrect a block already decoded ────────────────
    @Test
    fun laggedBitmapForAnAlreadyRecordedBaseIsNotSalvagedAgain() {
        // The 01A0 bitmap arriving during a later request: 0xA0 is already recorded, so the
        // salvage must refuse it rather than record the same block twice.
        val lines = listOf("7E80641A014000000")
        val salvaged = PidDiscoveryDecoder.salvageLaggedBitmap(0x80, lines, setOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0))
        assertNull("a bitmap whose base is already decoded is not salvaged again", salvaged)
    }

    @Test
    fun laggedBitmapForAnUnrecordedBaseIsStillSalvagedUnderItsTrueBase() {
        // TX 0120 receiving the 41 00 bitmap of TX 0100 - the frame that made the whole base
        // block disappear from the owner export.
        val lines = listOf("7E906410098188001", "7E8064100BE3EA813")
        val salvaged = PidDiscoveryDecoder.salvageLaggedBitmap(0x20, lines, emptySet())
        assertNotNull(salvaged)
        assertEquals(0x00, salvaged!!.basePid)
        assertTrue(salvaged.supportedPids.contains(0x0C))
        assertTrue(salvaged.supportedPids.contains(0x0D))
    }

    // ── 3. Decoders corrected against the frames the car actually sent ───────
    @Test
    fun boostPressureDecodesTheOwnerFrameAs16KpaNotAsRawHex() {
        // PID 65 answered "10 10" in the export and was displayed verbatim. J1979 PID 65 is
        // ONE byte in kPa, so A = 0x10 = 16 kPa at warm idle - plausible; the 2-byte reading
        // (16*256+16 = 4112 kPa) is not.
        val r = PidDecoder.decode(pid("0165", DecoderType.RAW_A_KPA, bytes = 1),
            listOf(0x41, 0x65, 0x10, 0x10))
        assertEquals(16.0, r.numericValue!!, 0.001)
        assertEquals("16", r.displayValue)
        assertEquals("kPa", r.unit)
    }

    @Test
    fun o2SensorFiftySixDecodesLambdaAndVoltageFromTheOwnerFrame() {
        // The export printed the raw byte "7F" for PID 56. J1979: A = lambda in 1/128,
        // B = voltage in 1/128 -> 0x7F 0x00 = lambda 0.992 / 0.633 V, i.e. closed loop at
        // stoichiometry, which independently agrees with PID 44 = 1.000 in the same run.
        val r = PidDecoder.decode(pid("0156", DecoderType.LAMBDA_SENSOR_VOLTAGE),
            listOf(0x41, 0x56, 0x7F, 0x00))
        assertEquals(0.992, r.numericValue!!, 0.001)
        assertTrue(r.displayValue.contains("0.992"))
        assertTrue(r.displayValue.contains("0.633"))
        assertTrue("the value must be known, not research raw", r.isKnown)
    }

    @Test
    fun o2SensorFiftyFiveDecodesLambdaAndShortTrim() {
        val r = PidDecoder.decode(pid("0155", DecoderType.LAMBDA_STFT_PAIR, bytes = 4),
            listOf(0x41, 0x55, 0x80, 0x00, 0x80, 0x00))
        assertEquals(1.0, r.numericValue!!, 0.001)
        assertTrue(r.displayValue.contains("1.000"))
        assertTrue(r.displayValue.contains("+0.0"))
    }

    @Test
    fun lambdaDecodersRefuseShortFramesInsteadOfInventingValues() {
        val short56 = PidDecoder.decode(pid("0156", DecoderType.LAMBDA_SENSOR_VOLTAGE),
            listOf(0x41, 0x56, 0x7F))
        assertNull(short56.numericValue)
        assertEquals("NO DATA", short56.displayValue)

        val short55 = PidDecoder.decode(pid("0155", DecoderType.LAMBDA_STFT_PAIR, bytes = 4),
            listOf(0x41, 0x55, 0x80, 0x00))
        assertNull(short55.numericValue)
        assertEquals("NO DATA", short55.displayValue)
    }

    @Test
    fun coolantTwoSentinelIsStillRejectedNotTurnedIntoAPlausibleNumber() {
        // Real frame from the owner run: 41 67 03 50 43. The ECU is returning a no-sensor
        // sentinel. Under A - 40 it decodes to -37 degC and the plausibility gate rejects it.
        // The J1979 2-byte form would yield (3*256+80)/10-40 = 44.4 degC: a believable
        // number invented out of a sentinel. NO-FAKE-VALUES beats the spec sheet.
        val r = PidDecoder.decode(pid("0167", DecoderType.TEMP_MINUS_40, bytes = 1),
            listOf(0x41, 0x67, 0x03, 0x50, 0x43))
        assertNull(r.numericValue)
        assertEquals("implausible raw - no data", r.displayValue)
    }

    // ── 4. Catalogue integrity ───────────────────────────────────────────────
    @Test
    fun catalogueHasNoDuplicatePidIds() {
        val all = StandardPidCatalog.getAllKnownPids()
        val ids = all.map { it.hexPid.uppercase() }
        assertEquals("duplicate PID definitions would make lookup order decide the decoder",
            ids.size, ids.distinct().size)
    }

    @Test
    fun rangeMarkersAreDisabledAndHonestlyNamed() {
        val marker = StandardPidCatalog.lookup("80")
        assertFalse("PID 80 is the availability bitmap for 81-A0, never a data channel",
            marker.enabled)
        assertTrue(marker.name.contains("range marker"))
        assertFalse(marker.name.contains("Diesel"))
    }

    @Test
    fun dieselOnlyInventionsAreGoneFromAPetrolCatalogue() {
        val all = StandardPidCatalog.getAllKnownPids()
        for (def in all) {
            assertFalse("PID ${def.hexPid} still claims a diesel-only meaning: ${def.name}",
                def.name.contains("Diesel Particulate Filter"))
            assertFalse("PID ${def.hexPid} still claims an invented meaning: ${def.name}",
                def.name.contains("Fuel Filament"))
            assertFalse("PID ${def.hexPid} still claims an invented meaning: ${def.name}",
                def.name.contains("Generator Speed"))
        }
    }

    @Test
    fun j1979NamesAreUsedForThePidsThisCarClaims() {
        assertTrue(StandardPidCatalog.lookup("83").name.contains("NOx Sensor"))
        assertTrue(StandardPidCatalog.lookup("85").name.contains("NOx Reagent"))
        assertTrue(StandardPidCatalog.lookup("86").name.contains("Particulate Matter"))
        assertTrue(StandardPidCatalog.lookup("87").name.contains("Intake Manifold Absolute Pressure"))
        assertTrue(StandardPidCatalog.lookup("5A").name.contains("Engine Coolant Temperature"))
        // The three the car claims but the 2026-09-16 run never validated stay research-only
        // until a real response defines their bit layout.
        assertTrue(StandardPidCatalog.lookup("83").isResearch)
        assertTrue(StandardPidCatalog.lookup("85").isResearch)
        assertTrue(StandardPidCatalog.lookup("86").isResearch)
    }

    @Test
    fun uncataloguedPidsFallBackToSlowResearchNotToTheFastPoll() {
        val unknown = StandardPidCatalog.lookup("9E")
        assertTrue(unknown.name.startsWith("Mode 01 PID"))
        assertTrue("unknown meaning must be flagged as research", unknown.isResearch)
        assertTrue("an unknown channel must not take a fast poll slot",
            unknown.defaultIntervalMs >= 3000L)
    }

    @Test
    fun noCatalogueEntryInventsABankThatThisThreeCylinderEngineDoesNotHave() {
        for (def in StandardPidCatalog.getAllKnownPids()) {
            assertFalse("PID ${def.hexPid} still uses the pre-J1979 O2 bank naming: ${def.name}",
                def.name.contains("O2 Sensor Voltage"))
            assertFalse("PID ${def.hexPid} still claims DPF soot/ash: ${def.name}",
                def.name.contains("DPF Soot") || def.name.contains("DPF Ash"))
        }
        assertTrue(StandardPidCatalog.lookup("78").name.contains("Exhaust Gas Temperature"))
        assertTrue(StandardPidCatalog.lookup("7B").name.contains("Particulate Matter"))
        assertTrue("a PID with no J1979 definition must not invent one",
            StandardPidCatalog.lookup("5B").name.contains("not in J1979"))
        assertFalse(StandardPidCatalog.lookup("5B").enabled)
    }

    @Test
    fun o2SensorFiftyFiveIsCataloguedAsAFourByteJ1979Pair() {
        val def = StandardPidCatalog.lookup("55")
        assertEquals(4, def.dataBytes)
        assertEquals(DecoderType.LAMBDA_STFT_PAIR, def.decoderType)
    }

    @Test
    fun boostIsTheOnlyFastPriorityTurboChannelAndItIsEnabled() {
        val boost = StandardPidCatalog.lookup("65")
        assertTrue(boost.enabled)
        assertEquals(1, boost.dataBytes)
        assertEquals(DecoderType.RAW_A_KPA, boost.decoderType)
        assertEquals("kPa", boost.unit)
    }
}
