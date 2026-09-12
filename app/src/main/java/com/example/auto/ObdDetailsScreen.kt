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
import com.example.di.AppContainer
import com.example.model.ProtocolHealth
import com.example.model.RealtimeEconomySnapshot
import com.example.model.TripEconomyStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Second Android Auto screen: everything the dashboard does not fit.
 *
 * Built only from Car App API level 1 primitives (`PaneTemplate`, `Pane`, `Row`, `Action`) so it
 * keeps working on the oldest Android Auto hosts; the manifest declares `minCarApiLevel = 1`.
 *
 * Values are read straight from the shared singleton state flows on each refresh instead of being
 * collected, which keeps this screen allocation-free while it is pushed.
 */
class ObdDetailsScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "OBDLogger/AndroidAuto"

        /** Detail values are read once a second; the dashboard already refreshes at 2 Hz. */
        private const val REFRESH_INTERVAL_MS = 1000L

        /**
         * Atmospheric pressure used to derive boost when PID 0133 (barometric pressure) is not
         * answering. 101 kPa is sea level; the difference only shifts the boost figure slightly
         * and the label states that it is an estimate.
         */
        private const val FALLBACK_BARO_KPA = 101.0
    }

    private var values: Map<String, String> = emptyMap()
    private var numbers: Map<String, Double> = emptyMap()
    private var trip: TripEconomyStats = TripEconomyStats()
    private var economy: RealtimeEconomySnapshot = RealtimeEconomySnapshot()
    private var drivingStateName: String = "UNKNOWN"
    private var brakeDisplay: String = ""
    private var health: ProtocolHealth = ProtocolHealth.UNKNOWN
    private var isPolling: Boolean = false
    private var connectedDevice: String? = null
    private var enabledPids: List<com.example.model.PidDefinition> = emptyList()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                try {
                    AppContainer.init(carContext.applicationContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "details screen init failed: ${t.message}", t)
                }
                lifecycleScope.launch {
                    while (isActive) {
                        snapshot()
                        invalidate()
                        delay(REFRESH_INTERVAL_MS)
                    }
                }
            }
        })
    }

    private fun snapshot() {
        try {
            val scheduler = AppContainer.obdScheduler
            values = scheduler.liveDecodedMap.value
            numbers = scheduler.liveNumericMap.value
            trip = scheduler.tripEconomy.value
            economy = scheduler.realtimeEconomy.value
            val state = scheduler.drivingState.value
            drivingStateName = state.state.displayName
            brakeDisplay = state.brakeStatusDisplay
            health = AppContainer.protocolHealth.value
            isPolling = scheduler.isPolling.value
            connectedDevice = AppContainer.bluetoothManager.connectedDeviceName.value
            enabledPids = try {
                AppContainer.settingsRepository.pidDefinitions.value.filter { it.enabled }
            } catch (t: Throwable) {
                emptyList()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "details snapshot failed: ${t.message}", t)
        }
    }

    override fun onGetTemplate(): Template {
        return try {
            buildTemplate()
        } catch (t: Throwable) {
            Log.e(TAG, "details onGetTemplate failed: ${t.message}", t)
            PaneTemplate.Builder(
                Pane.Builder()
                    .addRow(Row.Builder().setTitle("OBD DETAILS").addText("Unavailable").build())
                    .build()
            )
                .setTitle("OBD DETAILS")
                .setHeaderAction(Action.BACK)
                .build()
        }
    }

    private fun buildTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(connectionRow())
            .addRow(boostRow())
            .addRow(
                Row.Builder()
                    .setTitle("Ignition ${value("010E")}")
                    .addText("Fuel sys ${value("0103")}")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Short trim ${value("0106")}")
                    .addText("Long trim ${value("0107")}")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Air flow ${value("0110")}")
                    .addText("Oil temp ${value("015C")}")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Fuel rate ${value("015E")}")
                    .addText("Ambient ${value("0146")}")
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Run time ${value("011F")}")
                    .addText("MIL distance ${value("0121")}")
                    .build()
            )
            .addRow(tripRow())
            .addRow(economyRow())
            .let { builder -> extraPidRows().fold(builder) { b, row -> b.addRow(row) } }
            .addRow(
                Row.Builder()
                    .setTitle("State $drivingStateName")
                    .addText(if (brakeDisplay.isEmpty()) "Brake: unknown" else "Brake: $brakeDisplay")
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("OBD DETAILS - LIVE ENGINE DATA")
            .setHeaderAction(Action.BACK)
            .build()
    }

    /**
     * Every enabled PID the phone app polls, in pairs, so PIDs added in PID Config appear on the
     * car screen too instead of a frozen hardcoded list.
     */
    private fun extraPidRows(): List<Row> {
        val alreadyShown = setOf(
            "010C", "010D", "0104", "0105", "010B", "010F", "0111", "0142",
            "010E", "0103", "0106", "0107", "0110", "015C", "015E", "0146", "011F", "0121"
        )
        val extras = enabledPids.filter { it.id !in alreadyShown }.take(24)
        return extras.chunked(2).map { pair ->
            val first = pair[0]
            val builder = Row.Builder()
                .setTitle("${first.shortName} ${value(first.id)}")
            if (pair.size > 1) {
                builder.addText("${pair[1].shortName} ${value(pair[1].id)}")
            }
            builder.build()
        }
    }

    private fun connectionRow(): Row = Row.Builder()
        .setTitle(
            when {
                isPolling -> "POLLING \u2022 ECU ${health.name}"
                connectedDevice != null -> "CONNECTED \u2022 not polling"
                else -> "NOT CONNECTED"
            }
        )
        .addText(connectedDevice ?: "Pair the ELM327 adapter on the phone")
        .build()

    /**
     * Turbo boost from standard OBD data: manifold absolute pressure minus barometric pressure.
     * On the 1.0 TSI the manifold runs below atmospheric at idle/part load (vacuum) and above it
     * once the turbo is producing boost, so this is the honest way to show boost without
     * manufacturer specific measuring blocks.
     */
    private fun boostRow(): Row {
        val map = numbers["010B"]
        val baro = numbers["0133"]
        val text = if (map == null) {
            "MAP ${value("010B")}"
        } else {
            val reference = baro ?: FALLBACK_BARO_KPA
            val deltaKpa = map - reference
            val psi = deltaKpa * 0.145038
            val suffix = if (baro == null) " (est.)" else ""
            if (deltaKpa > 0) {
                "Boost +${format(deltaKpa)} kPa / +${format(psi)} psi$suffix"
            } else {
                "Vacuum ${format(deltaKpa)} kPa / ${format(psi)} psi$suffix"
            }
        }
        return Row.Builder()
            .setTitle("MAP ${value("010B")}")
            .addText(text)
            .build()
    }

    private fun tripRow(): Row = Row.Builder()
        .setTitle("Trip ${format(trip.distanceKm)} km in ${formatDuration(trip.tripDurationSec)}")
        .addText("Fuel ${format(trip.totalFuelLiters)} L \u2022 idle ${format(trip.idleFuelLiters)} L")
        .build()

    private fun economyRow(): Row = Row.Builder()
        .setTitle("Average ${format(trip.averageKmL)} km/L")
        .addText(
            "Avg speed ${format(trip.averageSpeedKmh)} km/h \u2022 smoothed ${economy.smoothedKmLDisplay} km/L"
        )
        .build()

    private fun value(pid: String): String {
        val raw = values[pid]?.trim().orEmpty()
        if (raw.isEmpty()) return "--"
        val upper = raw.uppercase()
        return if (upper.startsWith("NOT AVAILABLE") || upper == "UNSUPPORTED" ||
            upper == "TIMEOUT" || upper == "ERROR" || upper == "NO_DATA"
        ) {
            "--"
        } else {
            raw
        }
    }

    private fun format(value: Double?): String {
        val v = value ?: return "--"
        if (!v.isFinite()) return "--"
        val rounded = if (abs(v) >= 100) v.toLong().toString() else String.format("%.1f", v)
        return rounded
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (minutes <= 0) "${rest}s" else "${minutes}m ${rest}s"
    }
}
