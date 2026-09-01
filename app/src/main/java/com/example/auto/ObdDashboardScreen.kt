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
import kotlinx.coroutines.launch

class ObdDashboardScreen(carContext: CarContext) : Screen(carContext) {

    private var rpm: String = "--"
    private var speed: String = "--"
    private var load: String = "--"
    private var mapStr: String = "--"
    private var iat: String = "--"
    private var coolant: String = "--"
    private var fuelLevel: String = "--"
    private var fuelRate: String = "--"
    private var ethanol: String = "--"
    
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var protocolHealth: ProtocolHealth = ProtocolHealth.UNKNOWN
    
    private var isSubscribed = false

    override fun onGetTemplate(): Template {
        // AppContainer must be initialized
        AppContainer.init(carContext)
        
        if (!isSubscribed) {
            isSubscribed = true
            subscribeToTelemetry()
        }

        val paneBuilder = Pane.Builder()

        val statusText = when {
            connectionState != ConnectionState.CONNECTED -> "OBD DISCONNECTED"
            protocolHealth == ProtocolHealth.UNKNOWN || protocolHealth == ProtocolHealth.TESTING -> "OBD NOT VERIFIED"
            protocolHealth == ProtocolHealth.NO_RESPONSE -> "ECU NOT RESPONDING"
            else -> "OBD CONNECTED • ECU WORKING"
        }
        
        paneBuilder.addRow(
            Row.Builder()
                .setTitle("STATUS")
                .addText(statusText)
                .build()
        )

        // Only show live values if connected and verified
        if (connectionState == ConnectionState.CONNECTED && (protocolHealth == ProtocolHealth.WORKING || protocolHealth == ProtocolHealth.PARTIAL)) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("ENGINE")
                    .addText("$rpm rpm • $speed km/h • Load: $load %")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("AIR")
                    .addText("MAP: $mapStr kPa • IAT: $iat °C")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("TEMPERATURE")
                    .addText("Coolant: $coolant °C")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("FUEL & ETHANOL")
                    .addText("Lvl: $fuelLevel % • Rate: $fuelRate L/h • Eth: $ethanol")
                    .build()
            )
        } else {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("TELEMETRY")
                    .addText("Awaiting connection and protocol verification...")
                    .build()
            )
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("OBD LOGGER")
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
                mapStr = formatValue(map, "010B")
                iat = formatValue(map, "010F")
                coolant = formatValue(map, "0105")
                fuelLevel = formatValue(map, "012F")
                fuelRate = formatValue(map, "019D")
                
                val rawEthanol = map["0152"]
                ethanol = if (rawEthanol == null || isError(rawEthanol)) {
                    "Unavailable"
                } else {
                    "$rawEthanol %"
                }
                
                invalidate()
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
