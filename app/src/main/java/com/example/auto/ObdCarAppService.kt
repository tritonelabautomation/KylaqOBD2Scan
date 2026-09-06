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
        // FIX TD-1: Previous code caught the throwable, logged it, and then
        // immediately retried the SAME construction. If construction fails (e.g. DI
        // container not initialized in the new process), the second attempt fails the
        // same way, silently swallowing the error. We now propagate the failure so
        // Android Auto can show a meaningful error and we never appear to "succeed"
        // with a broken session.
        return try {
            ObdCarSession()
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Error creating ObdCarSession — rethrowing", t)
            throw t
        }
    }

    override fun onCreateSession(): Session {
        Log.i("OBDLogger/AndroidAuto", "CarAppService onCreateSession() [no-arg]")
        // FIX TD-1: See above. No silent retry on the same broken path.
        return try {
            ObdCarSession()
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Error creating ObdCarSession — rethrowing", t)
            throw t
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
        // FIX TD-1: Propagate error instead of silently retrying the same broken path.
        return try {
            ObdDashboardScreen(carContext)
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Error instantiating ObdDashboardScreen — rethrowing", t)
            throw t
        }
    }
}
