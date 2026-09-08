package com.example

import com.example.data.TripPlanCodec
import org.junit.Assert.*
import org.junit.Test

/** Pure-JVM round-trip tests for the planned-trips codec. */
class TripPlansTest {

    private val plan = TripPlanCodec.TripPlan(
        idMs = 42L,
        name = "Hyderabad -> Uppal | office run",
        from = "Hitec City",
        to = "Uppal\nMetro",
        distanceKm = 32.5,
        dateMs = 1_780_000_000_000L,
        budget = 500.0,
        note = "avoid ORR toll",
        completed = false
    )

    @Test
    fun `round trip preserves every field including separators`() {
        val decoded = TripPlanCodec.decode(TripPlanCodec.encode(plan))!!
        assertEquals(plan, decoded)
    }

    @Test
    fun `null budget survives and completed flag flips`() {
        val noBudget = plan.copy(budget = null, completed = true)
        val decoded = TripPlanCodec.decode(TripPlanCodec.encode(noBudget))!!
        assertNull(decoded.budget)
        assertTrue(decoded.completed)
    }

    @Test
    fun `bad lines decode to null`() {
        assertNull(TripPlanCodec.decode("garbage"))
        assertNull(TripPlanCodec.decode("t1|not|enough"))
        assertNull(TripPlanCodec.decode("t9|1|a|b|c|1.0|2|3|d|0"))
    }
}
