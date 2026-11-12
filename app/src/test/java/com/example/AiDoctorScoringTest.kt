package com.example

import com.example.ai.AiDoctorScoring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guard tests for the 2026-09-14 AI Doctor honesty gate (owner: bedroom
 * screenshots showed a perfect 100/100 "VEHICLE HEALTH SCORE" with the adapter
 * never connected - the score only deducted on bad LIVE readings, so no data
 * read as a flawless car).
 */
class AiDoctorScoringTest {

    @Test
    fun `no adapter link means no score - never an invented 100`() {
        assertNull(AiDoctorScoring.liveHealthScore(false, null, null))
        // even alarming values must not produce a number without a link
        assertNull(AiDoctorScoring.liveHealthScore(false, 10.5, 120.0))
    }

    @Test
    fun `connected but no samples yet means no score`() {
        assertNull(AiDoctorScoring.liveHealthScore(true, null, null))
    }

    @Test
    fun `live deductions match the documented penalties`() {
        assertEquals(100, AiDoctorScoring.liveHealthScore(true, 12.8, 90.0))
        assertEquals(85, AiDoctorScoring.liveHealthScore(true, 12.0, 90.0))
        assertEquals(75, AiDoctorScoring.liveHealthScore(true, 12.8, 110.0))
        assertEquals(60, AiDoctorScoring.liveHealthScore(true, 12.0, 110.0))
        // single-sample scoring: voltage alone is enough once live
        assertEquals(85, AiDoctorScoring.liveHealthScore(true, 12.0, null))
    }
}
