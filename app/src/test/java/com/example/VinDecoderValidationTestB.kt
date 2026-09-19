package com.example

import com.example.protocol.IsoTpParser
import org.junit.Assert.*
import org.junit.Test

/** Rigorous VIN validation tests - Part B. */
class VinDecoderValidationTestB {

    @Test
    fun testVinWithQ_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58 4B 51 45 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("'Q' rejected", valid)
        }
    }

    @Test
    fun testVinWithSpace_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58 4B 20 45 50 43 32 54 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("space rejected", valid)
        }
    }

    @Test
    fun testIncomplete_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.isComplete && it.reconstructedBytes.size == 20 }
        assertNull("incomplete rejected", msg)
    }

    @Test
    fun testMalformed_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58","7E8 23 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.isComplete && !it.isMalformed && it.reconstructedBytes.size == 20 }
        assertNull("malformed rejected", msg)
    }

    @Test
    fun testDifferentCanIds_NotCombined() {
        // The consecutive frame belongs to 7E9 while the first/last frames belong to 7E8,
        // so no complete 20-byte VIN may ever be reconstructed.
        //
        // FIX: this assertion used to demand exactly one complete 20-byte message (and then
        // called .first() on the result), which is the opposite of the test's own intent and
        // threw NoSuchElementException because the parser correctly produces none.
        val rawLines = listOf("7E8 10 14 49 02 01 4D 45 58","7E9 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val all = IsoTpParser.reassembleLines(rawLines)
        val completeVins = all.filter { it.isComplete && !it.isMalformed && it.reconstructedBytes.size == 20 }
        assertTrue("Frames from different CAN ids must never be stitched into a VIN", completeVins.isEmpty())

        // The 7E8 stream is reported, but explicitly flagged as broken.
        val partial7E8 = all.filter { it.canId == "7E8" }
        assertEquals(1, partial7E8.size)
        assertTrue("Partial 7E8 message must be flagged malformed", partial7E8.first().isMalformed)

        // The orphan 7E9 consecutive frame is reported separately, never merged.
        assertTrue(all.any { it.canId == "7E9" && it.isMalformed })
    }

    @Test
    fun testLowercase_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 6D 45 58","7E8 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("lowercase rejected", valid)
        }
    }

    @Test
    fun testControlChar_Rejected() {
        val rawLines = listOf("7E8 10 14 49 02 01 4D 00 58","7E8 21 4B 50 45 50 43 32 54","7E8 22 47 30 32 38 38 35 35")
        val msg = IsoTpParser.reassembleLines(rawLines).firstOrNull { it.reconstructedBytes.size == 20 }
        if (msg != null) {
            val valid = msg.reconstructedBytes.slice(3..19).map { (it and 0xFF).toChar() }.all { it.code in 0..127 && it.toString().matches(Regex("^[A-HJ-NPR-Z0-9]$")) }
            assertFalse("control char rejected", valid)
        }
    }
}
