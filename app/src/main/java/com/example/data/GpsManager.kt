package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GpsData(
    val speedKmh: Float = 0f,
    val altitudeMeters: Double = 0.0,
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
            if (hasGps) {
                // FIX TD-2 / MED: Use 5m min distance instead of 0f to avoid continuous GPS
                // callbacks that drain battery. 5m is fine-grained enough for OBD correlation.
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 5f, this)
                isTracking = true
                return true
            } else {
                // FIX HIGH-1: GPS is disabled — mark unavailable and signal failure.
                // Previously this was silent, making "GPS off" indistinguishable from
                // "vehicle is stationary" (both result in speedKmh = 0f).
                _gpsData.value = _gpsData.value.copy(isAvailable = false)
                return false
            }
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

    override fun onLocationChanged(location: Location) {
        if (lastLocation != null) {
            totalDistance += lastLocation!!.distanceTo(location)
        }
        lastLocation = location

        _gpsData.value = GpsData(
            speedKmh = location.speed * 3.6f,
            altitudeMeters = location.altitude,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            isAvailable = true,
            distanceTraveledMeters = totalDistance
        )
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
        _gpsData.value = _gpsData.value.copy(isAvailable = false)
    }
}
