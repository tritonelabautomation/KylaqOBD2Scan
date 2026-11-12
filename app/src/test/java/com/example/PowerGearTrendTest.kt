package com.example

import com.example.analysis.TripTrendAnalyzer
import com.example.analysis.TripTrendAnalyzer.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-09-16: trends must show calculated mechanical power (2*pi*N*T) and gears.
 */
class PowerGearTrendTest {

    private fun s(pid: String, ts: Long, v: Double) = Sample("t1", pid, ts, v)

    @Test
    fun pairedRpmAndTorqueYieldTwoPiNTPower() {
        // rpm 2460, torque 50% = 89 Nm -> P = 2*pi*2460*89/60000 = 22.92 kW
        val pts = TripTrendAnalyzer.analyze(
            listOf(
                s("010C", 1_000, 2460.0),
                s("0162", 1_000, 50.0)
            )
        )
        assertEquals(1, pts.size)
        val p = pts[0].avgPowerKw
        assertNotNull(p)
        assertEquals(2 * Math.PI * 2460.0 * 89.0 / 60000.0, p!!, 0.05)
    }

    @Test
    fun unpairedSamplesNeverInventPower() {
        val pts = TripTrendAnalyzer.analyze(
            listOf(
                s("010C", 1_000, 2460.0),
                s("0162", 99_000, 50.0)   // 98 s apart - not the same engine moment
            )
        )
        assertEquals(null, pts[0].avgPowerKw)
    }

    @Test
    fun cruiseRatioMapsToARealGear() {
        // 2464 rpm at 40 km/h = 61.6 rpm per km/h = 2nd gear prior (26*2.370)
        val pts = TripTrendAnalyzer.analyze(
            listOf(
                s("010D", 1_000, 40.0),
                s("010C", 1_000, 2464.0)
            )
        )
        assertEquals(2, pts[0].modeGear)
        assertEquals(2, pts[0].gearMin)
        assertEquals(2, pts[0].gearMax)
    }

    @Test
    fun parkedSamplesNeverProduceGears() {
        val pts = TripTrendAnalyzer.analyze(
            listOf(
                s("010D", 1_000, 0.0),
                s("010C", 1_000, 1400.0)
            )
        )
        assertEquals(null, pts[0].modeGear)
    }

    @Test
    fun multiGearDriveReportsSpanAndMode() {
        val samples = mutableListOf<Sample>()
        var ts = 1_000L
        repeat(6) {   // 2nd gear cruise
            samples += s("010D", ts, 40.0); samples += s("010C", ts, 2464.0); ts += 1_000
        }
        repeat(3) {   // 4th gear cruise: 30.03*80 = 2402 rpm
            samples += s("010D", ts, 80.0); samples += s("010C", ts, 2402.0); ts += 1_000
        }
        val p = TripTrendAnalyzer.analyze(samples)[0]
        assertEquals(2, p.modeGear)          // 6 samples vs 3
        assertTrue("span covers 2..4: ${p.gearMin}..${p.gearMax}", p.gearMin!! <= 2 && p.gearMax!! >= 4)
    }
}
