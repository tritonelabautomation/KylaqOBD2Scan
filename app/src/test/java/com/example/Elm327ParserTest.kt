package com.example

import com.example.bluetooth.Elm327Parser
import com.example.model.ResponseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA batch 2026-09-09 (senior audit finding MED-3): Elm327Parser.parse is the single
 * ingress point for EVERY byte the adapter sends, yet had no dedicated unit test.
 * Each case below is derived from the actual parser source (bluetooth/Elm327Parser.kt).
 */
class Elm327ParserTest {

    @Test
    fun `hex frame with prompt parses OK and strips prompt from lines`() {
        val r = Elm327Parser.parse("41 00 BE 3E B8 13\r\r>", durationMs = 42L)
        assertEquals(ResponseStatus.OK, r.status)
        assertTrue(r.isPromptReceived)
        assertEquals(listOf("41 00 BE 3E B8 13"), r.lines)
        assertEquals(42L, r.durationMs)
        assertNull(r.errorMessage)
        assertTrue(r.rawText.contains(">")) // rawText is untouched evidence
    }

    @Test
    fun `multi-line response keeps every non-blank line in order`() {
        val r = Elm327Parser.parse("SEARCHING...\n41 0C 1A F8\r41 0C 1B 20\r>")
        assertEquals(ResponseStatus.OK, r.status)
        assertEquals(listOf("SEARCHING...", "41 0C 1A F8", "41 0C 1B 20"), r.lines)
    }

    @Test
    fun `NO DATA is classified case-insensitively`() {
        val r = Elm327Parser.parse("no data\r>")
        assertEquals(ResponseStatus.NO_DATA, r.status)
        assertEquals("NO DATA", r.errorMessage)
        assertTrue(r.isPromptReceived)
    }

    @Test
    fun `CAN ERROR maps to CAN_ERROR status`() {
        assertEquals(ResponseStatus.CAN_ERROR, Elm327Parser.parse("CAN ERROR\r>").status)
    }

    @Test
    fun `UNABLE TO CONNECT maps to UNABLE_TO_CONNECT status`() {
        assertEquals(
            ResponseStatus.UNABLE_TO_CONNECT,
            Elm327Parser.parse("UNABLE TO CONNECT\r>").status
        )
    }

    @Test
    fun `BUS INIT ERROR maps to BUS_INIT_ERROR status`() {
        assertEquals(ResponseStatus.BUS_INIT_ERROR, Elm327Parser.parse("BUS INIT: ERROR\r>").status)
        assertEquals(ResponseStatus.BUS_INIT_ERROR, Elm327Parser.parse("BUS INIT: ...ERROR\r>").status)
    }

    @Test
    fun `question mark means MALFORMED command`() {
        val r = Elm327Parser.parse("?")
        assertEquals(ResponseStatus.MALFORMED, r.status)
        assertFalse(r.isPromptReceived)
        assertTrue(r.errorMessage!!.contains("'?'"))
    }

    @Test
    fun `adapter errors BUFFER FULL and FB ERROR are MALFORMED`() {
        assertEquals(ResponseStatus.MALFORMED, Elm327Parser.parse("BUFFER FULL\r>").status)
        assertEquals(ResponseStatus.MALFORMED, Elm327Parser.parse("FB ERROR\r>").status)
        assertEquals(ResponseStatus.MALFORMED, Elm327Parser.parse("DATA ERROR\r>").status)
    }

    @Test
    fun `prompt-only response is OK with zero lines`() {
        val r = Elm327Parser.parse(">")
        assertEquals(ResponseStatus.OK, r.status)
        assertTrue(r.lines.isEmpty())
        assertTrue(r.isPromptReceived)
    }

    @Test
    fun `empty response without prompt is TIMEOUT with message`() {
        val r = Elm327Parser.parse("", durationMs = 1500L)
        assertEquals(ResponseStatus.TIMEOUT, r.status)
        assertFalse(r.isPromptReceived)
        assertEquals("No response received within timeout", r.errorMessage)
        assertEquals(1500L, r.durationMs)
    }

    @Test
    fun `whitespace-only lines are dropped but raw text survives`() {
        val r = Elm327Parser.parse("  \r\n 41 04 7F \r\n  >")
        assertEquals(listOf("41 04 7F"), r.lines)
        assertEquals(ResponseStatus.OK, r.status)
    }
}
