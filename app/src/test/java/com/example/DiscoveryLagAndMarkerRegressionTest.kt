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
 * The capability bitmaps that run recorded, decoded with the shipped decoder:
 *
 *     0x00  BE 3E A8 13 -> 01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F  (marker 20 set)
 *     0x40  FE D0 84 01 -> 41 42 43 44 45 46 47 49 4A 4C 51 56            (marker 60 set)
 *     0x60  6B 09 00 41 -> 62 63 65 67 68 6D 70 7A                        (marker 80 set)
 *     0x80  00 24 00 0D -> 8B 8E 9D 9E                                    (marker A0 set)
 *     0xA0  14 00 00 00 -> A4 A6                                          (no next block)
 *
 * That is 42 data PIDs, and the export listed 26 of them plus THREE rows that are the range
 * markers themselves (0160, 0180, 01A0) - PID A0 was catalogued as "Transmission Sump
 * Temperature" and printed -20 degC. The whole 0x00 block (16 PIDs the app polls on every
 * single trip) was lost to ELM327 buffer lag, and the 0x20 block was never decoded at all.
 *
 * Three defects are pinned here:
 *
 *  1. ELM327 buffer lag. TX 0100 got a stale garbled frame and the valid 41 00 bitmap
 *     arrived one command late, during TX 0120. Root fix: drain stale RX before every TX,
 *     and salvage a lagged bitmap under its TRUE base instead of dropping the block.
 *
 *  2. Range markers treated as data PIDs. Bit 32 of each block is the "next block exists"
 *     marker, but it was emitted as a supported PID, then TXed and decoded as a measurement.
 *
 *  3. Catalogue names invented rather than read from J1979, and one real channel thrown away:
 *     the car answered `41 A6 00 00 86 EB` = 10279.5 km and the app printed
 *     "Unknown Research PID 01A6".
 *
 * NOTE ON THIS FILE'S OWN HISTORY: the first revision asserted that the 0180 bitmap
 * `00 24 00 0D` decodes to PID 83 / 85 / 86 (NOx sensor, NOx reagent, PM sensor). That was a
 * hand-decoding error - bit numbering done on paper instead of by the decoder. The real
 * decode is 8B, 8E, 9D, 9E, and this car never claimed a NOx or PM PID. CI caught it. The
 * assertions below are generated from the same code the app runs, not from a spec sheet.
 */
class DiscoveryLagAndMarkerRegressionTest {

    private fun pid(
        id: String,
        dec: DecoderType = DecoderType.RESEARCH_RAW,
        bytes: Int = 2,
        research: Boolean = false,
        unit: String = ""
    ) =
        PidDefinition(
            id = id, service = "01", pid = id.removePrefix("01"),
            name = "Test $id", shortName = id, unit = unit,
            dataBytes = bytes, decoderType = dec, isResearch = research,
            defaultIntervalMs = 500L
        )

    // ── 1. The bitmaps the car actually sent, decoded by the shipped decoder ──
    @Test
    fun realKylaq0180BitmapYieldsTheFourPidsItClaimsAndNotItsOwnMarker() {
        // Frame from the owner export: TX 0180 -> RX 7E80641800024000D
        val bitmap = byteArrayOf(0x00, 0x24, 0x00, 0x0D)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(0x80, bitmap)

        assertEquals(listOf(0x8B, 0x8E, 0x9D, 0x9E), supported)
        assertFalse("the 01A0 range marker is not a data PID", supported.contains(0xA0))
        assertTrue("bit 32 promises the next block, so 01A0 must be queried",
            PidDiscoveryDecoder.hasNextRange(bitmap, 0x80))
        // The NOx / PM readings this bitmap was once claimed to contain do not exist here:
        // 83, 85 and 86 are diesel-emissions PIDs and every one of those bits is clear.
        assertFalse(supported.contains(0x83))
        assertFalse(supported.contains(0x85))
        assertFalse(supported.contains(0x86))
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
        assertEquals(listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E,
            0x0F, 0x11, 0x13, 0x15, 0x1C, 0x1F), s00)
        assertFalse(s00.contains(0x20))
        assertTrue(PidDiscoveryDecoder.hasNextRange(b00, 0x00))

        // The 0x20 block was never decoded in that run (its bitmap was consumed one command
        // late and rejected), so it is asserted as UNKNOWN here, not as an empty promise.
        // 7E8 41 40 FE D0 84 01 -> 12 real PIDs
        val b40 = byteArrayOf(0xFE.toByte(), 0xD0.toByte(), 0x84.toByte(), 0x01.toByte())
        val s40 = PidDiscoveryDecoder.decodeSupportedPids(0x40, b40)
        assertEquals(listOf(0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C,
            0x51, 0x56), s40)
        assertFalse(s40.contains(0x60))

        // 7E8 41 60 6B 09 00 41 -> 8 real PIDs incl. torque 62/63 and the 65/67/68/70/7A block
        val b60 = byteArrayOf(0x6B, 0x09, 0x00, 0x41)
        val s60 = PidDiscoveryDecoder.decodeSupportedPids(0x60, b60)
        assertEquals(listOf(0x62, 0x63, 0x65, 0x67, 0x68, 0x6D, 0x70, 0x7A), s60)
        assertFalse(s60.contains(0x80))

        // 7E8 41 A0 14 00 00 00 -> gear A4 and ODOMETER A6, and no further block
        val bA0 = byteArrayOf(0x14, 0x00, 0x00, 0x00)
        val sA0 = PidDiscoveryDecoder.decodeSupportedPids(0xA0, bA0)
        assertEquals(listOf(0xA4, 0xA6), sA0)
        assertFalse("0x14 00 00 00 has bit 32 clear: there is no 01C0 block",
            PidDiscoveryDecoder.hasNextRange(bA0, 0xA0))

        // 42 data PIDs across the five bitmaps the run captured - the honest total.
        assertEquals(42, s00.size + s40.size + s60.size +
            PidDiscoveryDecoder.decodeSupportedPids(0x80, byteArrayOf(0x00, 0x24, 0x00, 0x0D)).size +
            sA0.size)
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
    fun odometerDecodesTheOwnerFrameAsTenThousandKmNotAsUnknownResearchHex() {
        // Export row: 01A6 raw "00 00 86 EB", displayed as "Unknown Research PID 01A6".
        // J1979 PID A6 is the odometer: ((A*2^24)+(B*2^16)+(C*2^8)+D)/10 km.
        val r = PidDecoder.decode(pid("01A6", DecoderType.ODOMETER_4B, bytes = 4, unit = "km"),
            listOf(0x41, 0xA6, 0x00, 0x00, 0x86, 0xEB))
        assertEquals(10279.5, r.numericValue!!, 0.001)
        assertEquals("10279.5", r.displayValue)
        assertEquals("km", r.unit)
        assertTrue("a channel the car answers must not be flagged research", r.isKnown)
    }

    @Test
    fun odometerRejectsSentinelsInsteadOfPrintingZeroOrGarbage() {
        val zero = PidDecoder.decode(pid("01A6", DecoderType.ODOMETER_4B, bytes = 4),
            listOf(0x41, 0xA6, 0x00, 0x00, 0x00, 0x00))
        assertNull(zero.numericValue)
        assertEquals("Not available", zero.displayValue)

        val huge = PidDecoder.decode(pid("01A6", DecoderType.ODOMETER_4B, bytes = 4),
            listOf(0x41, 0xA6, 0xFF, 0xFF, 0xFF, 0xFF))
        assertNull(huge.numericValue)

        val short = PidDecoder.decode(pid("01A6", DecoderType.ODOMETER_4B, bytes = 4),
            listOf(0x41, 0xA6, 0x00, 0x00))
        assertNull(short.numericValue)
        assertEquals("NO DATA", short.displayValue)
    }

    @Test
    fun secondaryO2TrimsDecodeTheOwnerFramesAndSuppressTheMissingBank() {
        // 41 55 80 80 -> +0.0 % / +0.0 %: closed loop, post-cat trims at zero.
        val st = PidDecoder.decode(pid("0155", DecoderType.O2_TRIM_PAIR_2B),
            listOf(0x41, 0x55, 0x80, 0x80))
        assertEquals(0.0, st.numericValue!!, 0.001)
        assertEquals("+0.0 % / +0.0 %", st.displayValue)

        // 41 56 7F 00 -> A = -0.8 %, B = -100.0 % = the no-sensor sentinel of the bank this
        // 3-cylinder does not have. Publishing -100 % as a trim would be a fake number.
        val lt = PidDecoder.decode(pid("0156", DecoderType.O2_TRIM_PAIR_2B),
            listOf(0x41, 0x56, 0x7F, 0x00))
        assertNull(lt.numericValue)
        assertEquals("Not available", lt.displayValue)
    }

    @Test
    fun twoByteLambdaDecoderPublishesNoVoltageTheFrameDoesNotContain() {
        // The same bytes read as the J1979 lambda half: 0x7F00 / 32768 = 0.992, which agrees
        // with PID 44 (commanded equivalence ratio) = 1.000 in the same run. An earlier
        // revision printed "lambda 0.992 / 0.633 V" - the 0.633 V was invented from byte A.
        val r = PidDecoder.decode(pid("0134", DecoderType.LAMBDA_2B),
            listOf(0x41, 0x34, 0x7F, 0x00))
        assertEquals(0.992, r.numericValue!!, 0.001)
        assertTrue(r.displayValue.contains("0.992"))
        assertFalse("no voltage field was transmitted, so none may be displayed",
            r.displayValue.contains("V"))
        assertTrue(r.isKnown)

        val short = PidDecoder.decode(pid("0134", DecoderType.LAMBDA_2B),
            listOf(0x41, 0x34, 0x7F))
        assertNull(short.numericValue)
        assertEquals("NO DATA", short.displayValue)
    }

    @Test
    fun engineFrictionDecodesTheOwnerFrameAsPlusFivePercentNotAsTheRawByte() {
        // Log line: TX 018E -> "PID 8E Validation: status=DIRECT_VALIDATED (268ms) | Decoded: 82".
        // The bare byte was printed because the catalogue had NO entry for PID 8E, so lookup()
        // fell through to the generic "Mode 01 PID 8E" research row. J1979 PID 8E is engine
        // friction percent torque, A - 125: 0x82 = 130 -> +5 % of the reference torque that the
        // same run measured at 175 Nm (PID 63), i.e. about 8.8 Nm of friction at warm idle.
        val r = PidDecoder.decode(pid("018E", DecoderType.TORQUE_PCT, bytes = 1),
            listOf(0x41, 0x8E, 0x82))
        assertEquals(5.0, r.numericValue!!, 0.001)
        assertEquals("+5", r.displayValue)
        assertEquals("%", r.unit)
        assertTrue("a measured physical quantity is not research", r.isKnown)

        val def = StandardPidCatalog.lookup("8E")
        assertEquals(DecoderType.TORQUE_PCT, def.decoderType)
        assertTrue("the car claims and answers PID 8E, so it must be polled", def.enabled)
        assertTrue(def.name.contains("Friction"))
    }

    @Test
    fun ambientTemperatureUsesTheJ1979OffsetAndMatchesTheOwnerFrame() {
        // The export shows PID 46 = 31 degC. J1979 PID 46 is ambient air temperature, A - 40,
        // so the frame carried A = 0x47 = 71 and 71 - 40 = 31 degC for a September morning run.
        // A * 100 / 255 would have printed 27.8 from the same byte; a raw byte would have
        // printed 71. The catalogue already had this one right - pinned so it cannot drift.
        val def = StandardPidCatalog.lookup("46")
        assertEquals(DecoderType.TEMP_MINUS_40, def.decoderType)
        val r = PidDecoder.decode(def, listOf(0x41, 0x46, 0x47))
        assertEquals(31.0, r.numericValue!!, 0.001)
        assertEquals("31", r.displayValue)
    }

    @Test
    fun theRangeMarkerPhantomValueIsReproducibleFromTheBitmapByte() {
        // The export printed "Transmission Sump Temperature = -20" for PID A0. That is not a
        // temperature: the 01A0 capability bitmap is `14 00 00 00` and 0x14 - 40 = -20. The
        // phantom value is byte A of the bitmap, decoded by the marker's old TEMP_MINUS_40 row.
        assertEquals(0x14 - 40, -20)
        val marker = StandardPidCatalog.lookup("A0")
        assertEquals(DecoderType.RESEARCH_RAW, marker.decoderType)
        assertFalse(marker.enabled)
        // and decoding the bitmap with the old row is no longer reachable at all
        val r = PidDecoder.decode(marker, listOf(0x41, 0xA0, 0x14, 0x00, 0x00, 0x00))
        assertNull("a capability bitmap must never yield a numeric value", r.numericValue)
    }

    @Test
    fun theTwoUndecodedMultiByteFramesArePreservedByteForByte() {
        // PID 6D returned 11 bytes and PID 70 returned 10, against J1979 blocks of 6 and 9.
        // Both open with `02 00 00`, which reads like a record header. No scaling is asserted:
        // 0x0BE9 = 3049 is not a plausible idle manifold pressure in kPa, and guessing is what
        // the no-fake-values rule forbids. The raw frame must survive intact for the sweep that
        // will define it.
        val frame6D = listOf(0x41, 0x6D, 0x02, 0x00, 0x00, 0x05, 0x91, 0x28, 0x00, 0x00, 0x00, 0x00, 0x28)
        val r6D = PidDecoder.decode(StandardPidCatalog.lookup("6D"), frame6D)
        assertNull(r6D.numericValue)
        assertEquals("416D02000005912800000000028".uppercase(), r6D.rawPayloadHex.uppercase())

        val frame70 = listOf(0x41, 0x70, 0x02, 0x00, 0x00, 0x0B, 0xE9, 0x00, 0x00, 0x00, 0x00, 0x00)
        val r70 = PidDecoder.decode(StandardPidCatalog.lookup("70"), frame70)
        assertNull(r70.numericValue)
        assertEquals("41700200000BE90000000000", r70.rawPayloadHex.uppercase())
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
        for (marker in listOf("60", "80", "A0")) {
            val def = StandardPidCatalog.lookup(marker)
            assertFalse("PID $marker is an availability bitmap, never a data channel", def.enabled)
            assertTrue("PID $marker must say what it is: ${def.name}",
                def.name.contains("range marker"))
        }
        assertFalse(StandardPidCatalog.lookup("A0").name.contains("Sump"))
        assertFalse(StandardPidCatalog.lookup("80").name.contains("Diesel"))
        assertFalse(StandardPidCatalog.lookup("60").name.contains("DPF"))
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
            assertFalse("PID ${def.hexPid} still invents an oil-life percentage: ${def.name}",
                def.name.contains("Oil Life"))
            assertFalse("PID ${def.hexPid} still invents a third coolant sensor: ${def.name}",
                def.name.contains("Coolant Temperature 3"))
        }
    }

    @Test
    fun j1979NamesAreUsedForThePidsThisCarClaims() {
        // The four the 0180 bitmap really claims
        assertTrue(StandardPidCatalog.lookup("8B").name.isNotEmpty())
        assertTrue(StandardPidCatalog.lookup("8E").name.contains("Friction"))
        assertTrue(StandardPidCatalog.lookup("9D").name.contains("Fuel Rate"))
        assertTrue(StandardPidCatalog.lookup("9E").name.contains("Exhaust Flow"))
        // The two the 01A0 bitmap really claims
        assertTrue(StandardPidCatalog.lookup("A4").name.contains("Gear"))
        assertTrue(StandardPidCatalog.lookup("A6").name.contains("Odometer"))
        assertEquals(DecoderType.ODOMETER_4B, StandardPidCatalog.lookup("A6").decoderType)
        // Diesel-emissions PIDs keep their J1979 names but this car never claimed them
        assertTrue(StandardPidCatalog.lookup("83").name.contains("NOx"))
        assertTrue(StandardPidCatalog.lookup("85").name.contains("NOx Reagent"))
        assertTrue(StandardPidCatalog.lookup("86").name.contains("Particulate Matter"))
        assertFalse("PID 86 is not claimed by the 0180 bitmap 00 24 00 0D",
            StandardPidCatalog.lookup("86").enabled)
    }

    @Test
    fun pidNamesFollowJ1979NotTheInventionsTheyReplaced() {
        assertEquals("Relative Accelerator Pedal Position", StandardPidCatalog.lookup("5A").name)
        assertTrue(StandardPidCatalog.lookup("5B").name.contains("Hybrid Battery"))
        assertTrue(StandardPidCatalog.lookup("5F").name.contains("Emission Requirements"))
        assertTrue(StandardPidCatalog.lookup("65").name.contains("Auxiliary Input"))
        assertTrue(StandardPidCatalog.lookup("7A").name.contains("DPF Temperature"))
        assertTrue(StandardPidCatalog.lookup("7B").name.contains("DPF Pressure"))
        assertTrue(StandardPidCatalog.lookup("78").name.contains("Exhaust Gas Temperature"))
        assertTrue(StandardPidCatalog.lookup("87").name.contains("Intake Manifold Absolute Pressure"))
        assertTrue(StandardPidCatalog.lookup("8A").name.contains("AECD"))
        assertTrue(StandardPidCatalog.lookup("55").name.contains("Secondary O2 Sensor"))
    }

    @Test
    fun unprovenChannelsAreDisabledSoTheyCanNeverPublishAGuess() {
        // 0165 answered "10 10". Under the old boost label that is 16 kPa, but J1979 PID 65 is
        // an auxiliary input/output bitmap, so the number was a guess. Disabled, research raw.
        val aux = StandardPidCatalog.lookup("65")
        assertFalse(aux.enabled)
        assertTrue(aux.isResearch)
        assertEquals(DecoderType.RESEARCH_RAW, aux.decoderType)

        // 019E answered "00 20" and the scaling is not defined in any table this project
        // cites. The raw bytes are recorded; no number is published.
        val exh = StandardPidCatalog.lookup("9E")
        assertTrue(exh.isResearch)
        assertEquals(DecoderType.RESEARCH_RAW, exh.decoderType)

        // 015F is an emission-standard ENUM this car claims; A * 100 / 255 would print the
        // code as a fake oil-life percentage.
        assertFalse(StandardPidCatalog.lookup("5F").enabled)
    }

    @Test
    fun secondaryO2TrimsAreCataloguedAsTwoByteTrimPairs() {
        for (hexPid in listOf("55", "56", "57", "58", "59")) {
            val def = StandardPidCatalog.lookup(hexPid)
            assertEquals("PID $hexPid is a two-byte trim pair", 2, def.dataBytes)
            assertEquals(DecoderType.O2_TRIM_PAIR_2B, def.decoderType)
            assertFalse("PID $hexPid must not claim a sensor voltage: ${def.name}",
                def.name.contains("Voltage"))
        }
    }

    @Test
    fun uncataloguedPidsFallBackToSlowResearchNotToTheFastPoll() {
        val unknown = StandardPidCatalog.lookup("9F")
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
            assertFalse("PID ${def.hexPid} still claims a bank 2 on a 3-cylinder: ${def.name}",
                def.name.contains("Bank 2") && def.hexPid.uppercase() in
                    setOf("15", "16", "17", "1B", "14"))
        }
    }

    @Test
    fun theFastPollBandIsReservedForChannelsThatFeedTheDashboard() {
        val fast = StandardPidCatalog.getAllKnownPids()
            .filter { it.enabled && it.priority == com.example.model.PollingPriority.FAST }
            .map { it.hexPid.uppercase() }
            .toSet()
        // rpm, speed, throttle, torque and gear pairing - nothing of unknown meaning
        assertTrue(fast.contains("0C"))
        assertTrue(fast.contains("0D"))
        assertTrue(fast.contains("11"))
        assertFalse("an unproven bitmap must never take a FAST slot", fast.contains("65"))
        assertFalse("a range marker must never be polled", fast.contains("A0"))
    }
}
