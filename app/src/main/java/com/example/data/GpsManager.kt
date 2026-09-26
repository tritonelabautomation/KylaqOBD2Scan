package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.example.analysis.AltitudeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GpsData(
    val speedKmh: Float = 0f,
    val altitudeMeters: Double = 0.0,
    /**
     * False when the fix carried no altitude, in which case [altitudeMeters] is only the
     * meaningless 0.0 default and must never be logged or displayed as a measurement
     * (no-fake-values rule). Added 2026-09-15 with the per-sample trip-log altitude column.
     */
    val hasAltitude: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float = 0f,
    val isAvailable: Boolean = false,
    val distanceTraveledMeters: Float = 0f
)

class GpsManager(private val context: Context) : LocationListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _gpsData = MutableStateFlow(GpsData())
    val gpsData: StateFlow<GpsData> = _gpsData.asStateFlow()

    private var isTracking = false
    private var lastLocation: Location? = null
    private var totalDistance = 0f

    /**
     * WHY GPS is not delivering fixes (owner 2026-09-16: "Still Altitude logs are
     * missing very very bad"). startTracking() failures used to be silent, so a whole
     * trip could record null altitude with zero clue why. The dashboard now shows this
     * live while recording.
     */
    private val _gpsStatus = MutableStateFlow(STATUS_NO_FIX)
    val gpsStatus: StateFlow<String> = _gpsStatus.asStateFlow()

    /**
     * Live tracking flag (owner 2026-09-20: "Altitude still not logging in"). A process that
     * restarts MID-drive resumes the unfinished journal without ever passing the
     * START_RECORDING edge - the only place the auto-record loop used to start GPS - so the
     * keep-alive supervisor asks every tick and belts a recording that lacks GPS.
     */
    val isTrackingNow: Boolean get() = isTracking

    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_NO_FIX = "NO_FIX"
        const val STATUS_PROVIDER_OFF = "PROVIDER_OFF"
        const val STATUS_PERMISSION_DENIED = "PERMISSION_DENIED"
    }

    /**
     * Per-trip GPS altitude range (owner 2026-09-15). Fed only fixes that pass the
     * accuracy gate below AND report altitude; reset at every startTracking() so each
     * recording gets its own min/max; persisted by RecordingManager at stop.
     */
    val tripAltitude = AltitudeStats()

    /**
     * Starts GPS tracking.
     * @return true if GPS tracking was successfully started; false if GPS is unavailable or
     *         disabled. When false is returned, callers should surface a user-visible warning.
     * FIX HIGH-1: Now returns Boolean so callers know if GPS startup failed.
     */
    @SuppressLint("MissingPermission")
    fun startTracking(): Boolean {
        if (isTracking) return true
        try {
            val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val hasNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (hasGps || hasNetwork) {
                // New trip, new altitude window (owner 2026-09-15 trip-summary fix).
                tripAltitude.reset()
                totalDistance = 0f
                lastLocation = null
                
                // Continuous 1-second cadence with 0m distance threshold so stationary / idling
                // periods and traffic light stops continuously record altitude.
                if (hasGps) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                }
                if (hasNetwork) {
                    runCatching {
                        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this)
                    }
                }
                isTracking = true
                _gpsStatus.value = STATUS_NO_FIX

                // Prime from the last known fix (accuracy-gated inside onLocationChanged) so a
                // parked recording gets altitude IMMEDIATELY instead of waiting for satellite lock.
                try {
                    val lastGps = if (hasGps) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
                    val lastNet = if (hasNetwork) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
                    val best = when {
                        lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                        lastGps != null -> lastGps
                        else -> lastNet
                    }
                    best?.let { onLocationChanged(it) }
                } catch (_: Exception) {}
                return true
            } else {
                // FIX HIGH-1: GPS is disabled — mark unavailable and signal failure.
                // Previously this was silent, making "GPS off" indistinguishable from
                // "vehicle is stationary" (both result in speedKmh = 0f).
                _gpsStatus.value = STATUS_PROVIDER_OFF
                _gpsData.value = _gpsData.value.copy(isAvailable = false)
                return false
            }
        } catch (e: SecurityException) {
            // Android 12+ "Approximate location" grants only COARSE - GPS_PROVIDER needs
            // FINE and throws. Previously swallowed by the generic catch: silent null altitude.
            _gpsStatus.value = STATUS_PERMISSION_DENIED
            _gpsData.value = _gpsData.value.copy(isAvailable = false)
            return false
        } catch (e: Exception) {
            e.printStackTrace()
            _gpsData.value = _gpsData.value.copy(isAvailable = false)
            return false
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            locationManager.removeUpdates(this)
            isTracking = false
            _gpsData.value = _gpsData.value.copy(isAvailable = false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Horizontal accuracy gate (2026-09-14 cross-validation: Car Scanner's VAG default
     * thresholds are 40 m horizontal / 50 m vertical / 2 m/s speed). Fixes worse than
     * this are dropped instead of poisoning trip distance, coast detection and the
     * speed series; the last good fix stays published. Loosened to 80m so initial
     * satellite acquisition doesn't get dropped while locking.
     */
    val MAX_HORIZONTAL_ACCURACY_M = 80f

    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MAX_HORIZONTAL_ACCURACY_M) {
            return // multipath/canyon fix - keep the last good one
        }
        // QA fix: !! on a mutable property is a race-prone crash class; ?.let is equivalent and safe.
        lastLocation?.let { totalDistance += it.distanceTo(location) }
        lastLocation = location
        // Trip altitude window: only accuracy-gated fixes that actually report altitude.
        if (location.hasAltitude()) tripAltitude.record(location.altitude)

        _gpsData.value = GpsData(
            speedKmh = location.speed * 3.6f,
            // Only publish an altitude the fix actually reported; otherwise the 0.0 default
            // would look like a real "0 m" measurement downstream.
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
            hasAltitude = location.hasAltitude(),
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            isAvailable = true,
            distanceTraveledMeters = totalDistance
        )
        _gpsStatus.value = STATUS_OK
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}

    /**
     * FIX HIGH-2: GPS provider disabled at OS level — unregister listener and update state.
     * Previously this only set isAvailable = false but left the listener registered,
     * causing a resource leak and potential callbacks on a stale listener.
     */
    override fun onProviderDisabled(provider: String) {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        isTracking = false
        _gpsStatus.value = STATUS_PROVIDER_OFF
        _gpsData.value = _gpsData.value.copy(isAvailable = false)
    }
}
