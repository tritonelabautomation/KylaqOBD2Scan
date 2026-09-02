package com.example.auto

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.bluetooth.ConnectionState
import com.example.di.AppContainer
import com.example.model.ProtocolHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ObdDashboardScreen(carContext: CarContext) : Screen(carContext) {
    init {
        Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen created")
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onCreate")
            }
            override fun onStart(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onStart")
            }
            override fun onResume(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onResume")
            }
            override fun onPause(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onPause")
            }
            override fun onStop(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onStop")
            }
            override fun onDestroy(owner: LifecycleOwner) {
                Log.i("OBDLogger/AndroidAuto", "ObdDashboardScreen lifecycle onDestroy")
            }
        })
    }

    private var rpm: String = "--"
    private var speed: String = "--"
    private var load: String = "--"
    private var throttle: String = "--"
    private var mapStr: String = "--"
    private var coolant: String = "--"
    private var fuelRate: String = "--"
    
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var protocolHealth: ProtocolHealth = ProtocolHealth.UNKNOWN
    
    private var isSubscribed = false

    override fun onGetTemplate(): Template {
        try {
            Log.i("OBDLogger/AndroidAuto", "onGetTemplate called, connection: $connectionState, protocol: $protocolHealth")
            
            // AppContainer initialization in try-catch so it never blocks or prevents template return
            try {
                AppContainer.init(carContext.applicationContext)
                if (!isSubscribed) {
                    isSubscribed = true
                    subscribeToTelemetry()
                }
            } catch (t: Throwable) {
                Log.e("OBDLogger/AndroidAuto", "Failed to initialize AppContainer or subscribe to telemetry: ${t.message}", t)
            }

            val paneBuilder = Pane.Builder()

            // Only show live values if connected and verified
            if (connectionState == ConnectionState.CONNECTED && (protocolHealth == ProtocolHealth.WORKING || protocolHealth == ProtocolHealth.PARTIAL)) {
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle("RPM: $rpm")
                        .addText("Speed: $speed km/h")
                        .build()
                )
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle("Load: $load %")
                        .addText("Throttle: $throttle %")
                        .build()
                )
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle("MAP: $mapStr kPa")
                        .addText("Coolant: $coolant °C")
                        .build()
                )
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle("Fuel Rate: $fuelRate L/h")
                        .addText("OBD CONNECTED • ECU RESPONDING")
                        .build()
                )
            } else {
                val statusText = when {
                    connectionState != ConnectionState.CONNECTED -> "OBD DISCONNECTED\nConnect adapter from phone"
                    protocolHealth == ProtocolHealth.UNKNOWN || protocolHealth == ProtocolHealth.TESTING -> "OBD NOT VERIFIED\nVerify protocol on phone"
                    protocolHealth == ProtocolHealth.NO_RESPONSE -> "ECU NO RESPONSE"
                    else -> "Awaiting telemetry..."
                }
                paneBuilder.addRow(
                    Row.Builder()
                        .setTitle("STATUS")
                        .addText(statusText)
                        .build()
                )
            }

            return PaneTemplate.Builder(paneBuilder.build())
                .setTitle("OBD LOGGER - KYLAQ 1.0 TSI")
                .setHeaderAction(Action.APP_ICON)
                .build()
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Exception during onGetTemplate: ${t.message}", t)
            val fallbackPane = Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("OBD LOGGER")
                        .addText("OBD Disconnected\nOpen phone app to connect")
                        .build()
                )
                .build()
            return PaneTemplate.Builder(fallbackPane)
                .setTitle("OBD LOGGER")
                .setHeaderAction(Action.APP_ICON)
                .build()
        }
    }

    private fun subscribeToTelemetry() {
        try {
            val bluetoothManager = AppContainer.bluetoothManager
            val obdScheduler = AppContainer.obdScheduler

            lifecycleScope.launch {
                bluetoothManager.connectionState.collectLatest {
                    connectionState = it
                    invalidate()
                }
            }
            lifecycleScope.launch {
                AppContainer.protocolHealth.collectLatest {
                    protocolHealth = it
                    invalidate()
                }
            }
            lifecycleScope.launch {
                obdScheduler.liveDecodedMap.collectLatest { map ->
                    rpm = formatValue(map, "010C")
                    speed = formatValue(map, "010D")
                    load = formatValue(map, "0104")
                    throttle = formatValue(map, "0111")
                    mapStr = formatValue(map, "010B")
                    coolant = formatValue(map, "0105")
                    fuelRate = formatValue(map, "019D")
                    
                    invalidate()
                    // Throttle updates to ~2 per second to avoid flooding Android Auto
                    delay(500)
                }
            }
        } catch (t: Throwable) {
            Log.e("OBDLogger/AndroidAuto", "Exception during subscribeToTelemetry: ${t.message}", t)
        }
    }

    private fun formatValue(map: Map<String, String>, pid: String): String {
        val value = map[pid] ?: return "--"
        return if (isError(value)) "--" else value
    }

    private fun isError(value: String): Boolean {
        val upper = value.uppercase()
        return upper == "UNSUPPORTED" || upper == "TIMEOUT" || upper == "ERROR" || upper == "NO_DATA" || upper == "NO_RESPONSE"
    }
}
