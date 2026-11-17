package com.example

import com.example.bluetooth.ConnectionFailureClassifier
import com.example.bluetooth.ConnectionFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 2026-09-09: adapter-held-by-another-app classification. The classic Android
 * busy-socket IOException literally contains the word "timeout", so BUSY must win
 * over TIMEOUT - that ordering is the whole point of this suite.
 */
class ConnectionFailureClassifierTest {

    @Test
    fun `classic busy IOException classifies as BUSY despite containing the word timeout`() {
        val msg = "read failed, socket might closed or timeout, read ret: -1"
        assertEquals(ConnectionFailureKind.BUSY, ConnectionFailureClassifier.classify(msg))
    }

    @Test
    fun `other busy signatures classify as BUSY case-insensitively`() {
        assertEquals(ConnectionFailureKind.BUSY, ConnectionFailureClassifier.classify("Service discovery failed"))
        assertEquals(ConnectionFailureKind.BUSY, ConnectionFailureClassifier.classify("CONNECTION REFUSED"))
        assertEquals(ConnectionFailureKind.BUSY, ConnectionFailureClassifier.classify("Broken pipe"))
    }

    @Test
    fun `pure timeout message classifies as TIMEOUT`() {
        val msg = "Connection timeout after 15000ms (peer may be unreachable)"
        assertEquals(ConnectionFailureKind.TIMEOUT, ConnectionFailureClassifier.classify(msg))
    }

    @Test
    fun `null empty and unknown messages classify as UNKNOWN`() {
        assertEquals(ConnectionFailureKind.UNKNOWN, ConnectionFailureClassifier.classify(null))
        assertEquals(ConnectionFailureKind.UNKNOWN, ConnectionFailureClassifier.classify(""))
        assertEquals(ConnectionFailureKind.UNKNOWN, ConnectionFailureClassifier.classify("something odd"))
    }

    @Test
    fun `busy guidance names the culprit apps and the auto-retry window`() {
        val g = ConnectionFailureClassifier.guidance(ConnectionFailureKind.BUSY, "OBDII", null)
        assertTrue(g.contains("BUSY"))
        assertTrue(g.contains("Torque"))
        assertTrue(g.contains("JioThings"))
        assertTrue(g.contains("every 10 s"))
        assertTrue(g.contains("OBDII"))
    }

    @Test
    fun `timeout guidance explains the 15 s bound and unknown guidance carries the raw message`() {
        assertTrue(ConnectionFailureClassifier.guidance(ConnectionFailureKind.TIMEOUT, "ELM327", null).contains("15 s"))
        val u = ConnectionFailureClassifier.guidance(ConnectionFailureKind.UNKNOWN, "ELM327", "weird failure")
        assertTrue(u.contains("weird failure"))
    }
}
