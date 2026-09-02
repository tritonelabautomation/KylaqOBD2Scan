package com.example.auto

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

import android.util.Log

class ObdCarAppService : CarAppService() {
    init { Log.i("OBDLogger/AndroidAuto", "CarAppService created") }
    override fun createHostValidator(): HostValidator {
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // Simplified for this environment
        }
    }

    override fun onCreateSession(): Session {
        return ObdCarSession()
    }
}

class ObdCarSession : Session() {
    init { Log.i("OBDLogger/AndroidAuto", "Session created") }
    override fun onCreateScreen(intent: Intent): Screen {
        return ObdDashboardScreen(carContext)
    }
}
