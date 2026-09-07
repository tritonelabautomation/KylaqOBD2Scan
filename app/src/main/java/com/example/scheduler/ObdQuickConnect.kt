package com.example.scheduler

import com.example.bluetooth.ConnectionState
import com.example.bluetooth.ElmTransport
import com.example.di.AppContainer
import com.example.model.CapabilityStatus
import com.example.model.ProtocolHealth
import com.example.protocol.IsoTpParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Connect to the paired adapter, validate the dashboard PIDs and start polling" as one call.
 *
 * This exists because an Android Auto session used to show `Waiting for ECU...` forever even with
 * the adapter paired and switched on:
 *
 *  1. [ObdScheduler.startPolling] skips every PID for which
 *     `capabilityManager.isLiveEligible(pid)` is false. Nothing validates capabilities unless the
 *     phone dashboard runs its bootstrap or a PID discovery scan, so a car-only session polled
 *     zero PIDs.
 *  2. `AppContainer.protocolHealth` was written only by `MainViewModel`, i.e. only while the phone
 *     UI was alive. A cold car session saw [ProtocolHealth.UNKNOWN] and the screen refused to
 *     display values.
 *
 * Both are handled here, so the car session behaves like a freshly opened phone dashboard.
 */
object ObdQuickConnect {

    /** PIDs shown on the dashboards; each is probed once before live polling is allowed. */
    val DASHBOARD_PIDS = listOf("010C", "010D", "0104", "0105", "010B", "0111", "010F", "0142")

    /**
     * Name fragments that identify an ELM327-style adapter. Auto-connect must never grab a
     * paired headset, a speaker or a watch, which would fail confusingly (and can interrupt
     * a phone call), so an unrecognised name means "do not connect".
     */
    private val OBD_NAME_HINTS = listOf(
        "obd", "elm", "vgate", "vlink", "carista", "kiwi", "dus", "cres", "scan",
        "autel", "foxwell", "konnwei", "viecar", "iccar", "torque", "blue driver", "adapter"
    )

    fun looksLikeObdAdapter(name: String?): Boolean {
        val lower = name?.lowercase() ?: return false
        return OBD_NAME_HINTS.any { lower.contains(it) }
    }

    /** True when the transport is already up, whether or not the scheduler is polling it. */
    fun isAdapterConnected(): Boolean {
        val containerReady = try {
            AppContainer.bluetoothManager.connectionState.value == ConnectionState.CONNECTED
        } catch (_: UninitializedPropertyAccessException) {
            false
        }
        return containerReady
    }

    /**
     * Connects (or reuses an existing connection), validates the dashboard PIDs against the real
     * ECU and starts live polling in [scope].
     *
     * @return true when polling is running afterwards, false when no usable adapter was found.
     */
    suspend fun connectPairedAdapterAndPoll(
        scope: CoroutineScope,
        pids: List<String> = DASHBOARD_PIDS,
        onStatus: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val scheduler = try {
            AppContainer.obdScheduler
        } catch (_: UninitializedPropertyAccessException) {
            onStatus("App container is not ready yet")
            return@withContext false
        }

        if (scheduler.isPolling.value) {
            onStatus("Already polling")
            return@withContext true
        }

        val bluetooth = AppContainer.bluetoothManager
        val settings = AppContainer.settingsRepository

        // Case 1: the phone app (or an earlier car session) already holds the RFCOMM socket.
        val existing = bluetooth.currentTransport()
        if (bluetooth.connectionState.value == ConnectionState.CONNECTED && existing != null && existing.isConnected) {
            onStatus("Reusing the open adapter connection")
            val validated = bootstrapCapabilities(scheduler, existing, pids)
            publishHealth(validated, pids.size)
            scheduler.startPolling(scope, existing)
            return@withContext true
        }

        // Another entry point (phone UI or an earlier tick of this screen) may already be opening
        // the socket; the ELM327 exposes one RFCOMM channel, so racing it fails both attempts.
        val state = bluetooth.connectionState.value
        if (state == ConnectionState.CONNECTING || state == ConnectionState.INITIALIZING) {
            onStatus("Adapter connection already in progress")
            return@withContext false
        }

        // Case 2: nothing connected yet - find a paired OBD adapter and open it.
        val device = try {
            bluetooth.getPairedDevices().firstOrNull { looksLikeObdAdapter(it.name) }
        } catch (t: SecurityException) {
            onStatus("Bluetooth permission is missing on the phone")
            return@withContext false
        } catch (t: Throwable) {
            onStatus("Could not read paired devices: ${t.message}")
            return@withContext false
        }

        if (device == null) {
            onStatus("No paired OBD adapter found (pair an ELM327 in Android Bluetooth settings)")
            return@withContext false
        }

        onStatus("Connecting to ${device.name}")
        val (success, transport) = try {
            bluetooth.connectToDevice(
                deviceAddress = device.address,
                initSequence = settings.initCommands.value,
                rawLogListener = AppContainer.rawLogManager
            )
        } catch (t: Throwable) {
            onStatus("Connection failed: ${t.message}")
            return@withContext false
        }

        if (!success || transport == null) {
            onStatus("Adapter refused the connection")
            return@withContext false
        }

        val validated = bootstrapCapabilities(scheduler, transport, pids)
        publishHealth(validated, pids.size)
        if (validated == 0) {
            onStatus("Adapter connected but no PID answered - is the ignition on?")
            return@withContext false
        }
        scheduler.startPolling(scope, transport)
        onStatus("Live polling started ($validated/${pids.size} PIDs answering)")
        true
    }

    /**
     * Probes each PID once and records which ECU answered, mirroring the phone dashboard's
     * bootstrap. Only PIDs that answer here become eligible for live polling.
     *
     * @return how many of [pids] were directly validated.
     */
    suspend fun bootstrapCapabilities(
        scheduler: ObdScheduler,
        transport: ElmTransport,
        pids: List<String> = DASHBOARD_PIDS
    ): Int {
        var validated = 0
        for (request in pids) {
            if (!transport.isConnected) break
            val expectedPidHex = if (request.length >= 4) request.substring(2) else request
            val expectedPidInt = expectedPidHex.toIntOrNull(16) ?: -1
            val response = try {
                transport.sendCommand(request, 1000L)
            } catch (_: Throwable) {
                continue
            }

            var respondingCanId: String? = null
            var answered = false

            // Preferred path: ISO-TP reassembly (handles 7E8/7E9 and multi-frame replies).
            try {
                val message = IsoTpParser.reassembleLines(response.lines).find { msg ->
                    msg.reconstructedBytes.size >= 2 &&
                        msg.reconstructedBytes[0] == 0x41 &&
                        msg.reconstructedBytes[1] == expectedPidInt
                }
                if (message != null) {
                    respondingCanId = message.canId?.takeIf { it.matches(CAN_ID_REGEX) } ?: "7E8"
                    answered = true
                }
            } catch (_: Throwable) {
                // fall through to the raw line scan
            }

            // Fallback: single-line adapters answer without ISO-TP framing.
            if (!answered) {
                val needle = "41$expectedPidHex"
                for (line in response.lines) {
                    val clean = line.replace(" ", "").uppercase()
                    val index = clean.indexOf(needle)
                    if (index in 0..8) {
                        val prefix = clean.substring(0, index).takeLast(3)
                        respondingCanId = if (prefix.matches(CAN_ID_REGEX)) prefix else "7E8"
                        answered = true
                        break
                    }
                }
            }

            if (answered) {
                scheduler.capabilityManager.markPidValidated(
                    respondingCanId ?: "7E8",
                    expectedPidHex,
                    CapabilityStatus.DIRECT_VALIDATED
                )
                validated++
            }
        }
        return validated
    }

    /**
     * Publishes protocol health for callers that do not run the phone verification flow, without
     * overwriting a verdict the phone dashboard has already reached.
     */
    private fun publishHealth(validatedPids: Int, probedPids: Int) {
        val verdict = when {
            validatedPids == 0 -> ProtocolHealth.NO_RESPONSE
            validatedPids < probedPids -> ProtocolHealth.PARTIAL
            else -> ProtocolHealth.WORKING
        }
        val current = AppContainer.protocolHealth.value
        if (current == ProtocolHealth.UNKNOWN || current == ProtocolHealth.TESTING) {
            AppContainer.protocolHealth.value = verdict
        }
    }

    private val CAN_ID_REGEX = Regex("^[0-9A-F]{3}$")
}
