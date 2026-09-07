package com.example.auto

import android.os.SystemClock
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
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
import com.example.model.RealtimeEconomySnapshot
import com.example.model.TripEconomyStats
import com.example.scheduler.ObdQuickConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android Auto / AAOS dashboard.
 *
 * Two things used to stop this screen from ever showing live data, both fixed here:
 *
 *  * The screen only rendered values while `AppContainer.protocolHealth` was WORKING or PARTIAL,
 *    but that flow was written exclusively by the phone `MainViewModel`. A driver who launched the
 *    app from the car head unit therefore saw "OBD NOT VERIFIED - Verify protocol on phone" even
 *    with the adapter connected. Health is now also derived from the telemetry itself and set by
 *    [ObdQuickConnect] for car-only sessions.
 *  * Nothing connected the adapter. A car session now auto-connects to the paired ELM327, runs the
 *    same PID validation bootstrap the phone dashboard uses, starts polling, and keeps retrying
 *    while the ignition session is alive.
 *
 * Values are already unit-formatted by `PidDecoder` ("978 RPM", "35 kPa"), so they are displayed
 * verbatim - appending a unit here produced "Speed: 0 km/h km/h".
 */
class ObdDashboardScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "OBDLogger/AndroidAuto"

        /**
         * Car hosts throttle/queue template updates; pushing one per decoded frame floods them and
         * the AA runtime can drop the whole session. `sample()` collapses the telemetry flow to
         * ~2 Hz, which is as fast as a driver can read a gauge.
         */
        private const val UI_THROTTLE_MS = 500L

        /** How often the supervisor re-checks the adapter connection while the screen is open. */
        private const val RECONNECT_INTERVAL_MS = 10_000L
    }

    private var isInitialized = false

    private var values: Map<String, String> = emptyMap()
    private var connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var protocolHealth: ProtocolHealth = ProtocolHealth.UNKNOWN
    private var drivingStateName: String = "UNKNOWN"
    private var economy: RealtimeEconomySnapshot = RealtimeEconomySnapshot()
    private var trip: TripEconomyStats = TripEconomyStats()
    private var statusNote: String = ""
    private var lastTelemetryAtMs: Long = 0L

    init {
        Log.i(TAG, "ObdDashboardScreen created")
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                Log.i(TAG, "lifecycle onCreate")
                initializeOnce()
            }

            override fun onStart(owner: LifecycleOwner) {
                Log.i(TAG, "lifecycle onStart")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(TAG, "lifecycle onDestroy")
            }
        })
    }

    // region wiring

    private fun initializeOnce() {
        if (isInitialized) return
        try {
            AppContainer.init(carContext.applicationContext)
            subscribeToTelemetry()
            startConnectionSupervisor()
            isInitialized = true
            Log.i(TAG, "initializeOnce: success")
        } catch (t: Throwable) {
            // Leave isInitialized false so the next lifecycle event retries.
            Log.e(TAG, "initializeOnce failed, will retry: ${t.message}", t)
        }
    }

    private fun subscribeToTelemetry() {
        try {
            val bluetoothManager = AppContainer.bluetoothManager
            val obdScheduler = AppContainer.obdScheduler

            lifecycleScope.launch {
                bluetoothManager.connectionState.collectLatest { state ->
                    connectionState = state
                    invalidate()
                }
            }
            lifecycleScope.launch {
                AppContainer.protocolHealth.collectLatest { health ->
                    protocolHealth = health
                    invalidate()
                }
            }
            lifecycleScope.launch {
                obdScheduler.drivingState.sample(UI_THROTTLE_MS).collectLatest { result ->
                    drivingStateName = result.state.displayName
                    invalidate()
                }
            }
            lifecycleScope.launch {
                obdScheduler.realtimeEconomy.sample(UI_THROTTLE_MS).collectLatest { snapshot ->
                    economy = snapshot
                    invalidate()
                }
            }
            lifecycleScope.launch {
                obdScheduler.tripEconomy.sample(UI_THROTTLE_MS).collectLatest { stats ->
                    trip = stats
                    invalidate()
                }
            }
            lifecycleScope.launch {
                obdScheduler.liveDecodedMap.sample(UI_THROTTLE_MS).collectLatest { map ->
                    values = map
                    if (map.isNotEmpty()) lastTelemetryAtMs = SystemClock.elapsedRealtime()
                    invalidate()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "subscribeToTelemetry failed: ${t.message}", t)
        }
    }

    /**
     * Keeps a live OBD session for the whole time the screen exists. When the adapter drops
     * (ignition off, socket reset, adapter sleep) `ObdScheduler` clears `isPolling`, and the next
     * tick reconnects - so a driver does not have to pick up the phone mid-trip.
     */
    private fun startConnectionSupervisor() {
        lifecycleScope.launch {
            while (isActive) {
                val alreadyPolling = try {
                    AppContainer.obdScheduler.isPolling.value
                } catch (t: Throwable) {
                    false
                }
                if (!alreadyPolling) {
                    try {
                        val ok = ObdQuickConnect.connectPairedAdapterAndPoll(this) { note ->
                            statusNote = note
                            Log.i(TAG, "connect: $note")
                            invalidate()
                        }
                        if (!ok) invalidate()
                    } catch (t: Throwable) {
                        statusNote = t.message ?: "Auto-connect failed"
                        Log.e(TAG, "auto-connect failed: ${t.message}", t)
                        invalidate()
                    }
                }
                delay(RECONNECT_INTERVAL_MS)
            }
        }
    }

    private fun requestReconnect(scope: CoroutineScope) {
        statusNote = "Reconnecting..."
        invalidate()
        scope.launch {
            try {
                AppContainer.obdScheduler.stopPolling()
            } catch (t: Throwable) {
                Log.w(TAG, "stopPolling before reconnect failed: ${t.message}")
            }
            try {
                val ok = ObdQuickConnect.connectPairedAdapterAndPoll(this) { note ->
                    statusNote = note
                    invalidate()
                }
                if (!ok) {
                    statusNote = "Reconnect failed - is the adapter paired and ignition on?"
                }
            } catch (t: Throwable) {
                statusNote = t.message ?: "Reconnect failed"
            }
            invalidate()
        }
    }

    // endregion

    override fun onGetTemplate(): Template {
        return try {
            buildDashboardTemplate()
        } catch (t: Throwable) {
            Log.e(TAG, "onGetTemplate failed, using fallback: ${t.message}", t)
            buildFallbackTemplate()
        }
    }

    private fun buildDashboardTemplate(): Template {
        val paneBuilder = Pane.Builder()
        val hasTelemetry = values.isNotEmpty()
        val verified = protocolHealth == ProtocolHealth.WORKING || protocolHealth == ProtocolHealth.PARTIAL

        if (hasTelemetry || verified) {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("RPM ${value("010C")}")
                    .addText("Speed ${value("010D")}")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Coolant ${value("0105")}")
                    .addText("Intake ${value("010F")}")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("MAP ${value("010B")}")
                    .addText("Load ${value("0104")}")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Throttle ${value("0111")}")
                    .addText("Battery ${value("0142")}")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle("Fuel ${value("015E")}")
                    .addText("Instant ${economy.instantKmLDisplay} km/L")
                    .build()
            )
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(drivingStateName)
                    .addText(statusLine())
                    .build()
            )
        } else {
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(statusHeadline())
                    .addText(statusNote.ifEmpty { statusHint() })
                    .build()
            )
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Details")
                    .setOnClickListener { callback ->
                        try {
                            screenManager.push(ObdDetailsScreen(carContext))
                        } catch (t: Throwable) {
                            Log.e(TAG, "push details screen failed: ${t.message}", t)
                        }
                        callback.onSuccess(null)
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Reconnect")
                    .setOnClickListener { callback ->
                        requestReconnect(lifecycleScope)
                        callback.onSuccess(null)
                    }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("OBD LOGGER - KYLAQ 1.0 TSI")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .build()
    }

    private fun buildFallbackTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("OBD LOGGER")
                    .addText("Open the phone app once to connect the adapter")
                    .build()
            )
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle("OBD LOGGER")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    // region display helpers

    /** Decoded display strings already carry their unit ("978 RPM"), so they are shown as-is. */
    private fun value(pid: String): String {
        val raw = values[pid]?.trim().orEmpty()
        if (raw.isEmpty()) return "--"
        val upper = raw.uppercase()
        return when {
            upper.startsWith("NOT AVAILABLE") -> "--"
            upper == "UNSUPPORTED" || upper == "TIMEOUT" || upper == "ERROR" ||
                upper == "NO_DATA" || upper == "NO_RESPONSE" -> "--"
            else -> raw
        }
    }

    private fun statusHeadline(): String = when {
        connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.INITIALIZING ->
            "CONNECTING TO ADAPTER"
        connectionState != ConnectionState.CONNECTED -> "NO ADAPTER CONNECTION"
        protocolHealth == ProtocolHealth.NO_RESPONSE -> "ECU NOT RESPONDING"
        else -> "WAITING FOR TELEMETRY"
    }

    private fun statusHint(): String = when {
        connectionState != ConnectionState.CONNECTED ->
            "Pair the ELM327 in Android Bluetooth settings, then switch the ignition on."
        protocolHealth == ProtocolHealth.NO_RESPONSE ->
            "Adapter is connected but the ECU is silent - ignition off, or the adapter needs a protocol reset."
        else -> "Polling ${values.size} live parameters."
    }

    private fun statusLine(): String {
        val live = values.count { !it.value.startsWith("Not available") }
        val age = if (lastTelemetryAtMs > 0L) {
            val seconds = (SystemClock.elapsedRealtime() - lastTelemetryAtMs) / 1000L
            when {
                seconds < 2 -> "now"
                seconds < 60 -> "${seconds}s ago"
                else -> "${seconds / 60}m ago"
            }
        } else {
            "no data yet"
        }
        val connection = when (connectionState) {
            ConnectionState.CONNECTED -> "connected"
            ConnectionState.CONNECTING, ConnectionState.INITIALIZING -> "connecting"
            ConnectionState.ERROR -> "adapter error"
            ConnectionState.DISCONNECTED -> "disconnected"
        }
        return "$live live \u2022 $connection \u2022 updated $age"
    }

    // endregion
}
