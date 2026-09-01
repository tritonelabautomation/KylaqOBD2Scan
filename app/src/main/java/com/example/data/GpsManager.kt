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

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (isTracking) return
        try {
            val hasGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (hasGps) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
                isTracking = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
    override fun onProviderDisabled(provider: String) {
        _gpsData.value = _gpsData.value.copy(isAvailable = false)
    }
}
