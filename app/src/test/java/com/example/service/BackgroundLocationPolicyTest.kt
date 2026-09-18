package com.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The background-location rule, pinned down.
 *
 * Owner field report 2026-09-18, one word: *"Altitude"*. A 1 h 33 min recovered drive showed `-- m`
 * and an empty Altitude trend because Android 10+ hands a foreground service **no location updates
 * at all** while the app is off-screen unless `ACCESS_BACKGROUND_LOCATION` is granted - and a drive
 * with the phone in a pocket is off-screen. The permission has three different request paths by OS
 * level and mixing them up produces either a dead dialog or a Play rejection, so the decision is a
 * pure function and these are its guarantees.
 */
class BackgroundLocationPolicyTest {

    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    // ── By OS level ────────────────────────────────────────────────────────────────────────

    @Test
    fun belowApi29ThereIsNothingToOffer() {
        // Android 9 and earlier: no separate background permission; the foreground grant covers a
        // service. Offering one would be asking for a permission that does not exist.
        assertEquals(
            BackgroundLocationPolicy.State.NOT_APPLICABLE,
            BackgroundLocationPolicy.state(28, true, false, 0L, now)
        )
        assertFalse(BackgroundLocationPolicy.includeInStartupRequest(28, 0L, now))
        assertEquals(0, BackgroundLocationPolicy.requestArray(28).size)
    }

    @Test
    fun api29BundlesBackgroundIntoTheSameDialog() {
        // The one level where a checkbox for "allow all the time" rides in the startup dialog.
        assertEquals(
            BackgroundLocationPolicy.State.OFFER_IN_DIALOG,
            BackgroundLocationPolicy.state(29, true, false, 0L, now)
        )
        assertTrue(BackgroundLocationPolicy.includeInStartupRequest(29, 0L, now))
    }

    @Test
    fun api30PlusNeverBundlesAndUsesTheDedicatedRequest() {
        // Bundling is silently ignored from 11 onward, so including it would be intent without
        // effect. The sanctioned flow is a request for the background permission alone.
        assertEquals(
            BackgroundLocationPolicy.State.OFFER_VIA_SETTINGS,
            BackgroundLocationPolicy.state(33, true, false, 0L, now)
        )
        assertFalse(BackgroundLocationPolicy.includeInStartupRequest(33, 0L, now))
        assertEquals(
            listOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            BackgroundLocationPolicy.requestArray(33).toList()
        )
    }

    @Test
    fun grantedAndNotYetAskedAreTheirOwnStates() {
        assertEquals(
            BackgroundLocationPolicy.State.GRANTED,
            BackgroundLocationPolicy.state(33, true, true, 0L, now)
        )
        assertEquals(
            BackgroundLocationPolicy.State.NEEDS_FOREGROUND,
            BackgroundLocationPolicy.state(33, false, false, 0L, now)
        )
        // Foreground refused but background somehow granted is not a state the OS produces; the
        // background grant still wins because that is what actually delivers fixes.
        assertEquals(
            BackgroundLocationPolicy.State.GRANTED,
            BackgroundLocationPolicy.state(33, false, true, 0L, now)
        )
    }

    // ── The cooldown ───────────────────────────────────────────────────────────────────────

    @Test
    fun aDeclineIsRespectedForAWeekAndThenExpires() {
        val declined = now - 3 * day
        assertEquals(
            BackgroundLocationPolicy.State.COOLDOWN,
            BackgroundLocationPolicy.state(33, true, false, declined, now)
        )
        assertFalse(BackgroundLocationPolicy.includeInStartupRequest(29, declined, now))

        val stale = now - 8 * day
        assertEquals(
            "after the cooldown the offer returns",
            BackgroundLocationPolicy.State.OFFER_VIA_SETTINGS,
            BackgroundLocationPolicy.state(33, true, false, stale, now)
        )
        assertTrue(BackgroundLocationPolicy.includeInStartupRequest(29, stale, now))
    }

    @Test
    fun theCooldownNeverHidesTheTruthFromTheOwner() {
        // Cooling off suppresses the DIALOG, never the explanation: a blank altitude column must
        // still say why, in every state, or the footnote is a shrug.
        for (state in BackgroundLocationPolicy.State.values()) {
            assertTrue(
                "state $state must explain a blank altitude",
                BackgroundLocationPolicy.altitudeBlankReason(state).isNotBlank()
            )
        }
    }

    @Test
    fun theReasonNamesTheRealCause() {
        // The 2026-09-18 drive: granted foreground, no background, phone in a pocket. The footnote
        // has to say Android withheld GPS - not "recorded before 2026-09-15", which was the old
        // guess and was false for a drive recorded that morning.
        val reason = BackgroundLocationPolicy.altitudeBlankReason(
            BackgroundLocationPolicy.state(33, true, false, 0L, now)
        )
        assertTrue("must name the OS rule: $reason", reason.contains("Allow all the time"))
        assertTrue("must name the pocket case: $reason", reason.contains("pocket"))

        val granted = BackgroundLocationPolicy.altitudeBlankReason(
            BackgroundLocationPolicy.State.GRANTED
        )
        assertTrue("granted means the gate is the remaining explanation: $granted", granted.contains("40 m"))
    }
}
