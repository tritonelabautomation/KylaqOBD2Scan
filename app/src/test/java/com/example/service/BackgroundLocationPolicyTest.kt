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
    fun theReasonNamesTheRealCauseForTheEraOfTheTrip() {
        val day = 86_400_000L
        // A trip recorded BEFORE the recording service became a location-type foreground service:
        // the cause is the old build, whatever the owner grants today, and the words must say so -
        // no permission can bring back fixes that never arrived.
        val oldTrip = com.example.service.BackgroundLocationPolicy.FIX_LIVE_SINCE_MS - day
        val old = BackgroundLocationPolicy.altitudeBlankReason(
            BackgroundLocationPolicy.State.GRANTED, oldTrip
        )
        assertTrue("must name the old build: $old", old.contains("older build"))
        assertTrue("must name the pocket case: $old", old.contains("pocket"))
        assertTrue("must not promise recovery: $old", old.contains("no trace"))
        // Era wins over state: even a refused permission is not the cause of an old blank.
        assertEquals(
            old,
            BackgroundLocationPolicy.altitudeBlankReason(
                BackgroundLocationPolicy.State.NEEDS_FOREGROUND, oldTrip
            )
        )

        // A trip recorded by THIS build with location granted: the honest causes left are the
        // accuracy gate and GPS being off or under cover.
        val nowTrip = com.example.service.BackgroundLocationPolicy.FIX_LIVE_SINCE_MS + day
        val current = BackgroundLocationPolicy.altitudeBlankReason(
            BackgroundLocationPolicy.State.GRANTED, nowTrip
        )
        assertTrue("must name the gate: $current", current.contains("40 m"))

        // This build, permission never granted at all.
        val never = BackgroundLocationPolicy.altitudeBlankReason(
            BackgroundLocationPolicy.State.NEEDS_FOREGROUND, nowTrip
        )
        assertTrue("must name the missing grant: $never", never.contains("never granted"))
    }

    @Test
    fun aCurrentBuildReasonKeepsTheBeltHonest() {
        // While-in-use is now enough during recording (location-type foreground service), so the
        // reason must not tell the owner that 'Allow all the time' is a prerequisite - that sentence
        // was true of the old build only.
        val reason = BackgroundLocationPolicy.altitudeBlankReason(
            BackgroundLocationPolicy.State.OFFER_VIA_SETTINGS,
            BackgroundLocationPolicy.FIX_LIVE_SINCE_MS + 86_400_000L
        )
        assertTrue("must say while-in-use suffices: $reason", reason.contains("While using"))
        assertTrue("must call all-the-time what it is: $reason", reason.contains("optional belt"))
    }
}
