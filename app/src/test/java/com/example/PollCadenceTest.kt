package com.example

import com.example.scheduler.PollCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The polling card's honesty math (owner 2026-09-19: "ISO 15765-4 CAN 11-bit 500kbps vs current
 * sampling period ... any issues with current hardware ELM327 capabilities?"). The mode promises
 * a per-command interval; these turn the OBSERVED serial gap into what the owner sees: requests
 * per second and the round-robin cycle that is the true per-signal refresh rate.
 */
class PollCadenceTest {

    @Test
    fun meanGapIsNullUntilSomethingIsMeasured() {
        assertNull(PollCadence.meanGapMs(emptyList()))
        assertEquals(150L, PollCadence.meanGapMs(listOf(100L, 200L)))
    }

    @Test
    fun cycleIsGapTimesLivePids() {
        // The owner's 99-minute session sustained ~6.7 req/s - a ~150 ms round trip. Over ~40
        // live PIDs that is a ~6 s cycle: exactly why the stale floors are 2.5/5/15 s and why
        // the tank-level row for since-refuel arrives every few seconds, never every 125 ms.
        assertEquals(6.0, PollCadence.cycleSeconds(150L, 40), 1e-9)
        assertEquals(8.0, PollCadence.reqPerSec(125L), 1e-9)
        assertEquals(0.0, PollCadence.reqPerSec(0L), 1e-9)
    }
}
