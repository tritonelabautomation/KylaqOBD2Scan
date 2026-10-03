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
        // 2026-09-14 (owner: AA never lists the app): rethrowing here made the AA
        // host fail the whole bind, so the app vanished from the launcher with no
        // visible error. A degraded diagnostic pane beats an invisible app.
        return try {
            ObdDashboardScreen(carContext)
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "ObdDashboardScreen failed - serving fallback pane", t)
            ObdFallbackScreen(carContext, t.message ?: t.javaClass.simpleName)
        }
    }
}

/**
 * Degraded AA screen: proves the car-app bind works and tells the driver where the
 * live data is, instead of the host dropping the app silently.
 */
class ObdFallbackScreen(private val ctx: androidx.car.app.CarContext, private val reason: String) :
    Screen(ctx) {
    override fun onGetTemplate(): androidx.car.app.model.Template {
        val pane = androidx.car.app.model.Pane.Builder()
            .addRow(
                androidx.car.app.model.Row.Builder()
                    .setTitle("Kylaq TSI Coach")
                    .addText("Car telemetry UI could not start: $reason")
                    .addText("Live logging continues on the phone app; the car mirror retries on next launch.")
                    .build()
            )
            .build()
        return androidx.car.app.model.PaneTemplate.Builder(pane)
            .setHeaderAction(androidx.car.app.model.Action.BACK)
            .build()
    }
}
