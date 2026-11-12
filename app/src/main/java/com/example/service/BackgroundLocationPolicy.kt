package com.example.service

/**
 * When Android will hand a background service a GPS fix, and when it will not.
 *
 * Owner field report 2026-09-18, one word: *"Altitude"*. The screenshots showed a 1 h 33 min
 * recovered drive with `-- m` for max altitude and an empty Altitude (GPS) trend. The recorder was
 * not at fault and neither was recovery - the journal carries `altitude_m` and reads it back. The
 * fixes never arrived: this app declared only `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`,
 * and from Android 10 (API 29) onward a foreground service with no `ACCESS_BACKGROUND_LOCATION` is
 * handed **no location updates at all while the app is not on screen**. A drive with the phone in a
 * pocket is exactly that, so every sample was recorded with `altitudeM = null` and the trip drew an
 * honest blank. Distance and economy survived because they come off the OBD bus, not the sky.
 *
 * The permission has three different request paths depending on the OS level, and getting them
 * mixed is how apps end up with a dead dialog or a Play Store rejection, so the decision lives here
 * as a pure function with tests rather than inline in an Activity:
 *
 *  - **API < 29** - there is no separate background permission; the foreground grant covers a
 *    service. Nothing to offer, ever.
 *  - **API 29** - background may be requested *in the same dialog* as foreground, as an "allow all
 *    the time" checkbox. That is the only level where bundling it into the startup request is both
 *    legal and effective.
 *  - **API 30+** - bundling is ignored. The sanctioned flow is a dedicated request for the background
 *    permission alone, which the system answers with a redirect into Settings; the honest UI is a
 *    card with a button that starts exactly that, never a popup on launch.
 *
 * And a rule borrowed from [BatteryOptimizationPolicy]: a decline is recorded and cooled off, so the
 * app never re-raises the dialog on every launch. The card stays reachable, because the owner can
 * always change his mind - what must not happen is nagging.
 */
object BackgroundLocationPolicy {

    /** A decline is respected for a week. The card remains, so changing his mind costs one tap. */
    const val DECLINE_COOLDOWN_MS = 7L * 86_400_000L

    enum class State {
        /** API < 29: no separate background permission exists; a foreground grant covers services. */
        NOT_APPLICABLE,

        /** Background location is granted: a pocketed phone still gets fixes, altitude included. */
        GRANTED,

        /** Foreground not granted yet: the normal startup dialog comes first. */
        NEEDS_FOREGROUND,

        /** API 29, foreground granted: background can still ride in the same dialog. */
        OFFER_IN_DIALOG,

        /** API 30+, foreground granted: only a dedicated request / Settings redirect works. */
        OFFER_VIA_SETTINGS,

        /** He said no recently: stay quiet, keep the card, do not re-raise the dialog. */
        COOLDOWN
    }

    fun state(
        sdkInt: Int,
        foregroundGranted: Boolean,
        backgroundGranted: Boolean,
        lastDeclinedMs: Long,
        nowMs: Long
    ): State {
        if (sdkInt < 29) return State.NOT_APPLICABLE
        if (backgroundGranted) return State.GRANTED
        if (!foregroundGranted) return State.NEEDS_FOREGROUND
        val cooling = lastDeclinedMs > 0L && nowMs - lastDeclinedMs < DECLINE_COOLDOWN_MS
        return when {
            cooling -> State.COOLDOWN
            sdkInt == 29 -> State.OFFER_IN_DIALOG
            else -> State.OFFER_VIA_SETTINGS
        }
    }

    /**
     * Whether the startup permission array should carry the background permission. True only on
     * API 29, where the system renders it as a checkbox in the same dialog, and only outside a
     * cooldown. On 30+ bundling it is silently ignored, so including it would be a lie in the
     * manifest's intent with no effect at runtime.
     */
    fun includeInStartupRequest(sdkInt: Int, lastDeclinedMs: Long, nowMs: Long): Boolean =
        sdkInt == 29 && !(lastDeclinedMs > 0L && nowMs - lastDeclinedMs < DECLINE_COOLDOWN_MS)

    /** The permissions a dedicated background request should name, per OS level. */
    fun requestArray(sdkInt: Int): Array<String> =
        if (sdkInt >= 29) arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else emptyArray()

    /**
     * Builds from this date onward record with a location-type foreground service, so while-in-use
     * alone keeps GPS flowing on a pocketed drive. Trips that STARTED before it were recorded by a
     * service Android treated as background for location - a different cause, needing different
     * words. Parsed by the app's own IST parser, never a hand-computed epoch.
     */
    val FIX_LIVE_SINCE_MS: Long =
        com.example.data.RecordTime.parseMillis("2026-09-18T00:00:00+05:30") ?: 0L

    /**
     * Plain-language reason for a blank altitude column, so the UI never guesses wrong. Era-aware:
     * a trip that started before [FIX_LIVE_SINCE_MS] was recorded by a build whose recording service
     * was NOT a location-type foreground service, and no permission the owner grants today can bring
     * fixes back that never arrived.
     */
    fun altitudeBlankReason(state: State, tripStartMs: Long? = null): String {
        if (tripStartMs != null && tripStartMs < FIX_LIVE_SINCE_MS) {
            return "this trip was recorded by an older build whose recording service was not a " +
                "location-type foreground service: the moment the screen went off, Android withheld " +
                "GPS from it, so a drive with the phone in a pocket carries no fixes at all - only " +
                "what the OBD bus reported. A fix that never arrived leaves no trace to recover"
        }
        return when (state) {
            State.NEEDS_FOREGROUND ->
                "location permission was never granted, so no fix could reach the recorder"
            else ->
                "no accuracy-gated GPS fix reached the recorder (worse than 40 m horizontal is " +
                    "dropped) - GPS was off or under cover for the drive; 'Allow all the time' in " +
                    "Settings is only optional belt, recording keeps GPS on 'While using' because " +
                    "the service is a location-type foreground service"
        }
    }
}
