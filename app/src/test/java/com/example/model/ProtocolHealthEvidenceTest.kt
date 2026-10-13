package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Evidence over verdict (owner 2026-09-19). The dashboard showed NO_RESPONSE, grey CAN/ECU dots
 * and "No ECU response received on current protocol profile" WHILE 39,808 CAN frames were flowing
 * and a 99-minute recording was active: a connect-time probe had judged a still-waking ECU and
 * nothing ever revised the judgement. Live frames are stronger evidence than a probe, so past a
 * threshold they upgrade the verdict - and only the verdicts frames can settle.
 */
class ProtocolHealthEvidenceTest {

    @Test
    fun flowingFramesUpgradeAStaleNoResponse() {
        assertEquals(
            ProtocolHealth.WORKING,
            healthAfterFrameEvidence(ProtocolHealth.NO_RESPONSE, FRAME_EVIDENCE_THRESHOLD.toLong())
        )
    }

    @Test
    fun belowTheThresholdTheVerdictStands() {
        // 24 frames can still be a fluke of a dying bus; the rule waits for 25.
        assertEquals(
            ProtocolHealth.NO_RESPONSE,
            healthAfterFrameEvidence(ProtocolHealth.NO_RESPONSE, FRAME_EVIDENCE_THRESHOLD.toLong() - 1)
        )
    }

    @Test
    fun unknownAlsoYieldsToEvidence() {
        assertEquals(
            ProtocolHealth.WORKING,
            healthAfterFrameEvidence(ProtocolHealth.UNKNOWN, 1000L)
        )
    }

    @Test
    fun claimsFramesCannotSettleAreLeftAlone() {
        // A partial capability bitmap is a statement about WHICH pids answer, not whether the bus
        // works; an adapter fault is about the adapter. Frame count settles neither.
        assertEquals(
            ProtocolHealth.PARTIAL,
            healthAfterFrameEvidence(ProtocolHealth.PARTIAL, 10_000L)
        )
        assertEquals(
            ProtocolHealth.ADAPTER_ERROR,
            healthAfterFrameEvidence(ProtocolHealth.ADAPTER_ERROR, 10_000L)
        )
        assertEquals(
            ProtocolHealth.WORKING,
            healthAfterFrameEvidence(ProtocolHealth.WORKING, 0L)
        )
        assertEquals(
            ProtocolHealth.TESTING,
            healthAfterFrameEvidence(ProtocolHealth.TESTING, 10_000L)
        )
    }
}
