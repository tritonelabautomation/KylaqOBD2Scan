package com.example.auto

import android.content.Intent
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

class ObdCarAppService : CarAppService() {
    init {
        Log.i("OBDLogger/AndroidAuto", "CarAppService instantiated")
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("OBDLogger/AndroidAuto", "CarAppService onCreate")
    }

    override fun createHostValidator(): HostValidator {
        Log.i("OBDLogger/AndroidAuto", "CarAppService createHostValidator called")
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        Log.i("OBDLogger/AndroidAuto", "CarAppService onCreateSession(sessionInfo=$sessionInfo)")
        return try {
            ObdCarSession()
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Error creating ObdCarSession", t)
            ObdCarSession()
        }
    }

    override fun onCreateSession(): Session {
        Log.i("OBDLogger/AndroidAuto", "CarAppService onCreateSession() [no-arg]")
        return try {
            ObdCarSession()
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Error creating ObdCarSession", t)
            ObdCarSession()
        }
    }

    override fun onDestroy() {
        Log.i("OBDLogger/AndroidAuto", "CarAppService onDestroy")
        super.onDestroy()
    }
}

class ObdCarSession : Session() {
    init {
        Log.i("OBDLogger/AndroidAuto", "ObdCarSession instantiated")
    }

    override fun onCreateScreen(intent: Intent): Screen {
        Log.i("OBDLogger/AndroidAuto", "ObdCarSession onCreateScreen with intent: $intent")
        return try {
            ObdDashboardScreen(carContext)
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Error instantiating ObdDashboardScreen", t)
            ObdDashboardScreen(carContext)
        }
    }
}
