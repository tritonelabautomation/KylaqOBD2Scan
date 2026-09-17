package com.example

import com.example.protocol.PidDiscoveryDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidDiscoveryDecoderTest {

    @Test
    fun testDecodePromptExampleBitmap() {
        // Example from prompt:
        // Request: 01 00
        // Response: 41 00 BE 3E B8 13
        // Bitmap: BE 3E B8 13
        //
        // BE = 1011 1110 -> 01, 03, 04, 05, 06, 07
        // 3E = 0011 1110 -> 0B, 0C, 0D, 0E, 0F
        // B8 = 1011 1000 -> 11, 13, 14, 15
        // 13 = 0001 0011 -> 1C, 1F, 20
        val bitmap = byteArrayOf(
            0xBE.toByte(),
            0x3E.toByte(),
            0xB8.toByte(),
            0x13.toByte()
        )

        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x00, bitmap = bitmap)

        // 2026-09-17: 0x20 here is the range marker, not a parameter - excluded.
        val expectedPids = listOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
            0x11, 0x13, 0x14, 0x15,
            0x1C, 0x1F
        )

        assertEquals(expectedPids, supported)
        assertTrue("Bit 0 of byte 3 is 1, so hasNextRange should be true", PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testAllSupportedBitmap() {
        val bitmap = byteArrayOf(
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte()
        )

        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x00, bitmap = bitmap)
        // 31 data PIDs: 0x01-0x1F. 0x20 is the range marker and is never emitted.
        assertEquals(31, supported.size)
        assertEquals(1, supported.first())
        assertEquals(0x1F, supported.last())
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testNoneSupportedBitmap() {
        val bitmap = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x00, bitmap = bitmap)
        assertTrue(supported.isEmpty())
        assertFalse(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testSinglePidSupported() {
        // Only bit 7 of byte 0 set -> PID 0x01
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x00, bitmap = bitmap)
        assertEquals(listOf(0x01), supported)
        assertFalse(PidDiscoveryDecoder.hasNextRange(bitmap))
    }

    @Test
    fun testRangeOffsets() {
        // Base PID 0x20 (Range 0120)
        // First bit set (0x21) plus bit 32, which is the 0x40 range marker
        val bitmap = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x01)
        val supported = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x20, bitmap = bitmap)
        assertEquals(listOf(0x21), supported)
        assertTrue(PidDiscoveryDecoder.hasNextRange(bitmap))

        // Base PID 0x40 with bit 0 of byte 3 cleared (no subsequent block)
        val bitmap2 = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00)
        val supported2 = PidDiscoveryDecoder.decodeSupportedPids(basePid = 0x40, bitmap = bitmap2)
        assertEquals(listOf(0x41), supported2)
        assertFalse(PidDiscoveryDecoder.hasNextRange(bitmap2))
    }

    @Test
    fun testExtractBitmapFromVariousFormats() {
        // Standard spaced response
        val lines1 = listOf("41 00 BE 3E B8 13")
        val b1 = PidDiscoveryDecoder.extractBitmap(0x00, lines1)
        assertNotNull(b1)
        assertEquals(0xBE.toByte(), b1!![0])
        assertEquals(0x3E.toByte(), b1[1])
        assertEquals(0xB8.toByte(), b1[2])
        assertEquals(0x13.toByte(), b1[3])

        // CAN framed with headers
        val lines2 = listOf("7E8 06 41 00 BE 3F B8 13")
        val b2 = PidDiscoveryDecoder.extractBitmap(0x00, lines2)
        assertNotNull(b2)
        assertEquals(0xBE.toByte(), b2!![0])
        assertEquals(0x3F.toByte(), b2[1])

        // Unspaced compact format
        val lines3 = listOf("4100BE3EB813")
        val b3 = PidDiscoveryDecoder.extractBitmap(0x00, lines3)
        assertNotNull(b3)
        assertEquals(0xBE.toByte(), b3!![0])

        // Error lines before response
        val lines4 = listOf("SEARCHING...", "41 00 BE 3E B8 13")
        val b4 = PidDiscoveryDecoder.extractBitmap(0x00, lines4)
        assertNotNull(b4)
        assertEquals(0xBE.toByte(), b4!![0])

        // NO DATA response
        val linesNoData = listOf("NO DATA")
        val bNoData = PidDiscoveryDecoder.extractBitmap(0x00, linesNoData)
        assertNull(bNoData)
    }

    @Test
    fun testDecodeFromRawResponse() {
        val lines = listOf("41 00 BE 3E B8 13")
        val result = PidDiscoveryDecoder.decodeFromRawResponse(0x00, lines)
        assertNotNull(result)
        assertEquals(0x00, result!!.basePid)
        assertEquals(18, result.supportedPids.size)
        assertTrue(result.supportedPids.contains(0x0C)) // Engine RPM
        assertTrue(result.supportedPids.contains(0x0D)) // Speed
        assertTrue(result.hasNextRange)
        assertEquals(32, result.allTestedPids.size)
    }

    /**
     * OWNER REAL-CAR REGRESSION (Kylaq discovery run 2026-09-16 08:57 IST, VIN
     * MEXKPEPC2TG028855): TX 0100 returned a garbled stale frame ("7E804413C14EA") and
     * the valid 41 00 bitmaps arrived ONE COMMAND LATE, during TX 0120. The strict
     * per-request decode (correctly) rejected them for base 0x20 - and the whole base
     * block (RPM 0C, speed 0D, coolant 05, MAP 0B, throttle 11, fuel system 03...)
     * vanished from that report even though the car plainly answers those PIDs every day.
     * The salvage must restore the lagged frames under their true base 0x00.
     */
    @Test
    fun laggedBaseBlockBitmapsFromTheOwnerKylaqRunAreSalvagedUnderTheirTrueBase() {
        val rxDuring0120 = listOf("7E906410098188001", "7E8064100BE3EA813")

        // Strict per-request behaviour is unchanged: these are NOT answers to 0120.
        assertNull(PidDiscoveryDecoder.decodeFromRawResponse(0x20, rxDuring0120))

        val salvaged = PidDiscoveryDecoder.salvageLaggedBitmap(0x20, rxDuring0120, emptySet())
        assertNotNull(salvaged)
        assertEquals(0x00, salvaged!!.basePid)
        assertEquals("both ECUs (7E9 + 7E8) must survive the salvage", 2, salvaged.ecuResponses.size)

        // Union of BE 3E A8 13 (7E8) and 98 18 80 01 (7E9): the PIDs the app records daily.
        val supported = salvaged.supportedPids
        for (expected in intArrayOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x13, 0x15)) {
            assertTrue("PID %02X must be in the salvaged base block".format(expected), supported.contains(expected))
        }
        assertTrue("bit 32 promises the next block", salvaged.hasNextRange)
    }

    @Test
    fun salvageSkipsBasesAlreadyDecodedAndNeverInventsSupportFromGarbage() {
        // Base 0x00 already decoded this run -> the same frames salvage nothing new.
        assertNull(
            PidDiscoveryDecoder.salvageLaggedBitmap(
                0x20,
                listOf("7E8064100BE3EA813"),
                setOf(0x00)
            )
        )
        // The garbled TX-0100 RX from the owner run is not a bitmap for ANY base.
        assertNull(
            PidDiscoveryDecoder.salvageLaggedBitmap(
                0x00,
                listOf("7E804413C14EA"),
                emptySet()
            )
        )
    }
}
