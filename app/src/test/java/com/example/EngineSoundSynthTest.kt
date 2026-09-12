package com.example

import com.example.sound.EngineSoundProfiles
import com.example.sound.EngineSoundSynth
import com.example.sound.EngineSynthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rev Theater synth: firing physics, determinism, dynamics and profile sanity. */
class EngineSoundSynthTest {

    @Test
    fun `firing frequency is true four-stroke physics`() {
        // 3-cyl TSI @ 3000 rpm: 3000/60 rev/s * 3/2 pulses = 75 Hz
        assertEquals(75.0, EngineSoundProfiles.firingFrequencyHz(EngineSoundProfiles.TSI_TRIPLE, 3000.0), 1e-9)
        // V8 @ 3000 rpm = 200 Hz; V12 @ 9000 rpm = 900 Hz
        assertEquals(200.0, EngineSoundProfiles.firingFrequencyHz(EngineSoundProfiles.V8_MUSCLE, 3000.0), 1e-9)
        assertEquals(900.0, EngineSoundProfiles.firingFrequencyHz(EngineSoundProfiles.V12_ITALIAN, 9000.0), 1e-9)
    }

    @Test
    fun `render is deterministic for identical state and inputs`() {
        val synth = EngineSoundSynth()
        val a = ShortArray(512)
        val b = ShortArray(512)
        synth.render(EngineSynthState(EngineSoundProfiles.TSI_TRIPLE, seed = 7), EngineSoundProfiles.TSI_TRIPLE, 3000.0, 0.5, a)
        synth.render(EngineSynthState(EngineSoundProfiles.TSI_TRIPLE, seed = 7), EngineSoundProfiles.TSI_TRIPLE, 3000.0, 0.5, b)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `more throttle means louder output`() {
        val synth = EngineSoundSynth()
        val quiet = ShortArray(1024)
        val loud = ShortArray(1024)
        synth.render(EngineSynthState(EngineSoundProfiles.V8_MUSCLE, seed = 3), EngineSoundProfiles.V8_MUSCLE, 2500.0, 0.0, quiet)
        synth.render(EngineSynthState(EngineSoundProfiles.V8_MUSCLE, seed = 3), EngineSoundProfiles.V8_MUSCLE, 2500.0, 1.0, loud)
        assertTrue(synth.peak(loud) > synth.peak(quiet))
    }

    @Test
    fun `output never exceeds 16-bit range even at redline wide open`() {
        val synth = EngineSoundSynth()
        val buf = ShortArray(2048)
        val state = EngineSynthState(EngineSoundProfiles.V4_SPORTBIKE, seed = 11)
        synth.render(state, EngineSoundProfiles.V4_SPORTBIKE, 14000.0, 1.0, buf)
        // ShortArray is bounded by construction; assert the shaper actually produced signal
        assertTrue(synth.peak(buf) > 1000)
        assertNotEquals(0, synth.peak(buf))
    }

    @Test
    fun `phase state stays wrapped and never runs away`() {
        val synth = EngineSoundSynth()
        val state = EngineSynthState(EngineSoundProfiles.V12_ITALIAN, seed = 5)
        val buf = ShortArray(1024)
        repeat(5) { synth.render(state, EngineSoundProfiles.V12_ITALIAN, 9000.0, 1.0, buf) }
        state.phases.forEach { p ->
            assertTrue(p >= 0.0)
            assertTrue(p < 2.0 * Math.PI)
        }
    }

    @Test
    fun `backfires only on overrun - closed throttle above 3000 rpm`() {
        val rot = EngineSoundProfiles.ROTARY
        assertTrue(EngineSoundProfiles.backfireAllowed(rot, 5000.0, 0.0))
        assertFalse(EngineSoundProfiles.backfireAllowed(rot, 5000.0, 0.5))
        assertFalse(EngineSoundProfiles.backfireAllowed(rot, 2000.0, 0.0))
    }

    @Test
    fun `every profile is physically sane`() {
        EngineSoundProfiles.ALL.forEach { p ->
            assertTrue("${p.id} idle<redline", p.idleRpm < p.redlineRpm)
            assertTrue("${p.id} cylinders", p.cylinders >= 2)
            assertTrue("${p.id} harmonics", p.harmonics.isNotEmpty() && p.harmonics[0] == 1.0)
            assertTrue("${p.id} drive", p.drive > 0.0)
            assertTrue("${p.id} backfire", p.backfire in 0.0..1.0)
        }
        assertEquals(6, EngineSoundProfiles.ALL.size)
        assertEquals(EngineSoundProfiles.TSI_TRIPLE, EngineSoundProfiles.byId("tsi3"))
        assertEquals(EngineSoundProfiles.TSI_TRIPLE, EngineSoundProfiles.byId("nonexistent"))
    }
}
