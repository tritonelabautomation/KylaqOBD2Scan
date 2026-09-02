package com.example.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.example.bluetooth.ConnectionState
import com.example.di.AppContainer
import com.example.model.ProtocolHealth
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.util.Log

class ObdDashboardScreen(carContext: CarContext) : Screen(carContext) {
    init { Log.i("OBDLogger/AndroidAuto", "DashboardScreen created") }

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
        Log.i("OBDLogger/AndroidAuto", "onGetTemplate called, connection: $connectionState, protocol: $protocolHealth")
        // AppContainer must be initialized
        AppContainer.init(carContext)
        
        if (!isSubscribed) {
            isSubscribed = true
            subscribeToTelemetry()
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
    }

    private fun subscribeToTelemetry() {
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
