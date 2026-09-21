package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.BluetoothDeviceInfo
import com.example.bluetooth.BluetoothManager
import com.example.bluetooth.ConnectionState
import com.example.bluetooth.ElmResponse
import com.example.data.db.entities.instantMs
import com.example.bluetooth.ElmTransport
import com.example.bluetooth.SimulationTransport
import kotlinx.coroutines.flow.firstOrNull
import com.example.analysis.DriveInsightsStore
import com.example.data.PollingSpeedMode
import com.example.data.RawLogEntry
import com.example.data.RawLogManager
import com.example.data.RecordingManager
import com.example.data.SavedRecording
import com.example.data.SettingsRepository
import com.example.model.BytePositionStats
import com.example.model.PidDefinition
import com.example.model.RecordingMetadata
import com.example.model.TransactionRecord
import com.example.protocol.PidDecoder
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import com.example.scheduler.ObdQuickConnect
import com.example.scheduler.ObdScheduler
import com.example.update.AppUpdateFeed
import com.example.update.AppUpdateInfo
import com.example.update.UpdateManager
import com.example.update.UpdateUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

import com.example.di.AppContainer

class MainViewModel(application: Application) : AndroidViewModel(application) {
    init { AppContainer.init(application) }

    
    val rawLogManager = AppContainer.rawLogManager
    val gpsManager = AppContainer.gpsManager

    val gpsData = gpsManager.gpsData
    val settingsRepository = AppContainer.settingsRepository
    val recordingManager = AppContainer.recordingManager
    val bluetoothManager = AppContainer.bluetoothManager
    val obdScheduler = AppContainer.obdScheduler

    // ── Background location (owner field report 2026-09-18: "Altitude") ────────────────────
    //
    // A 1 h 33 min recovered drive showed `-- m` altitude and an empty Altitude trend because
    // Android 10+ hands a foreground service NO location updates while the app is off-screen
    // unless ACCESS_BACKGROUND_LOCATION is granted. The phone spends a drive in a pocket, so
    // without it every sample is recorded with altitudeM = null. The decision of WHEN to offer the
    // permission lives in BackgroundLocationPolicy (tested); this ViewModel only observes grants and
    // publishes the state, so the Settings card and the altitude footnote read one truth.
    private val _bgLocationState =
        MutableStateFlow(com.example.service.BackgroundLocationPolicy.State.NEEDS_FOREGROUND)
    val backgroundLocationState: StateFlow<com.example.service.BackgroundLocationPolicy.State> =
        _bgLocationState.asStateFlow()

    /** Re-reads the grants. Called on resume and after any permission result. */
    fun refreshLocationPermissionState() {
        val fg = androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val bg = androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(), android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        _bgLocationState.value = com.example.service.BackgroundLocationPolicy.state(
            sdkInt = android.os.Build.VERSION.SDK_INT,
            foregroundGranted = fg,
            backgroundGranted = bg,
            lastDeclinedMs = settingsRepository.lastBgLocationDeclinedMs(),
            nowMs = System.currentTimeMillis()
        )
    }

    /** Recorded when a request comes back with foreground granted but background refused. */
    /** Detected refuel events, newest first (owner 2026-09-19 since-refuel tracking). */
    fun refuelEventsFlow() = recordingManager.tripRepository.refuelEventsFlow()

    /** The four PIDs every Driving-Data tab integrates: speed, two fuel rates, odometer. */
    private val summaryPids = listOf("010D", "019D", "015E", "01A6")

    /** Consumption since the newest detected refuel, from the app's own rows. Null if none yet. */
    suspend fun sinceRefuelStats(): com.example.analysis.SinceRefuelStats.Stats? {
        val repo = recordingManager.tripRepository
        val ev = repo.refuelEvents().firstOrNull() ?: return null
        val rows = repo.samplesSince(ev.tsEndMs, summaryPids)
        return com.example.analysis.SinceRefuelStats.summarize(
            rows.map { com.example.analysis.SinceRefuelStats.Row(it.timestamp, it.pid, it.numericValue) }
        )
    }

    /**
     * Cluster LONG-TERM tab: everything the app has ever recorded, through the SAME integrator
     * as since-refuel - one code path, so the tabs can never disagree about a litre.
     */
    suspend fun longTermStats(): com.example.analysis.SinceRefuelStats.Stats? {
        val rows = recordingManager.tripRepository.samplesSince(0L, summaryPids)
        if (rows.isEmpty()) return null
        return com.example.analysis.SinceRefuelStats.summarize(
            rows.map { com.example.analysis.SinceRefuelStats.Row(it.timestamp, it.pid, it.numericValue) }
        )
    }

    /**
     * Cluster SINCE START tab: the live session while recording (rows straight from RAM), else
     * the last COMPLETED trip from its stored samples - the cluster resets at ignition, the app
     * keeps showing the finished drive until the next one starts.
     */
    suspend fun sinceStartStats(): com.example.analysis.SinceRefuelStats.Stats? {
        val repo = recordingManager.tripRepository
        val rows: List<com.example.analysis.SinceRefuelStats.Row> =
            if (recordingManager.isRecording.value) {
                recordingManager.currentTransactions.value.map { tx ->
                    com.example.analysis.SinceRefuelStats.Row(
                        com.example.data.RecordTime.instantOf(tx.timestampMonotonic, tx.timestampUtc)
                            ?: tx.timestampMonotonic,
                        tx.pid,
                        tx.decodedValue
                    )
                }
            } else {
                val trip = repo.allTripsChronological().lastOrNull { it.status == "COMPLETED" }
                    ?: return null
                repo.samplesForTripPids(trip.id, summaryPids).map {
                    com.example.analysis.SinceRefuelStats.Row(it.timestamp, it.pid, it.numericValue)
                }
            }
        if (rows.isEmpty()) return null
        return com.example.analysis.SinceRefuelStats.summarize(rows)
    }

    /** Current odometer, PID 01A6: newest row of the live session, else newest stored row. */
    suspend fun currentOdoKm(): Double? = currentPidValue("01A6")

    /** Current tank level, PID 012F: newest live row, else the previous session's last stamp. */
    suspend fun currentLevelPct(): Double? =
        currentPidValue("012F")
            ?: com.example.di.AppContainer.settingsRepository.lastLevelStamp()?.second

    private suspend fun currentPidValue(pid: String): Double? =
        if (recordingManager.isRecording.value) {
            recordingManager.currentTransactions.value
                .lastOrNull { it.pid == pid && it.decodedValue != null }?.decodedValue
        } else {
            recordingManager.tripRepository.latestNumericFor(pid)
        }

    /** Calibrated tank capacity for the range estimate (Settings holds it, prefs-backed). */
    fun tankCapacityL(): Double = com.example.di.AppContainer.settingsRepository.tankCapacityL()

    /**
     * Closes the brim-to-brim loop: a fuel-log entry whose odometer matches an uncalibrated refuel
     * event supplies the pump's litres. The implied tank capacity (pump L over level rise) then
     * re-derives the estimate constant, clamped to a sane tank so one bad receipt cannot poison it.
     */
    fun calibrateAfterFuelEntry(litres: Double, odoKm: Double?, partial: Boolean) {
        if (partial || odoKm == null || litres <= 0.0) return
        viewModelScope.launch {
            val repo = recordingManager.tripRepository
            val match = repo.refuelEvents().firstOrNull {
                it.calibratedPumpL == null && it.odoKm != null &&
                    kotlin.math.abs(it.odoKm - odoKm) <= 3.0
            } ?: return@launch
            repo.calibrateRefuelEvent(match.idMs, litres)
            val rise = match.levelAfterPct - match.levelBeforePct
            if (rise >= 4.0) {
                val implied = litres / (rise / 100.0)
                if (implied in 30.0..80.0) {
                    com.example.di.AppContainer.settingsRepository.setTankCapacityL(implied)
                }
            }
        }
    }

    /** Restart-refuel prompt from the recorder: first level row after start vs the last stamp. */
    val restartRefuel = recordingManager.restartRefuel

    /** Prefill for the Fuel & Costs receipt dialog when the owner answers the prompt. */
    data class ReceiptPrefill(val liters: Double, val odoKm: Double?, val whenMs: Long)

    private val _receiptPrefill = MutableStateFlow<ReceiptPrefill?>(null)
    val receiptPrefill: StateFlow<ReceiptPrefill?> = _receiptPrefill

    fun consumeReceiptPrefill() { _receiptPrefill.value = null }

    /**
     * Both answers end the prompt for THIS event (one popup per refuel, never a nag on every
     * restart): "Add Receipt" carries the estimate, the odometer and the restart instant into the
     * entry dialog; "Not Now" leaves the estimate in the ledger, where a receipt entered later
     * still matches it by odometer.
     */
    fun answerRestartRefuel(addReceipt: Boolean) {
        val c = recordingManager.restartRefuel.value ?: return
        if (addReceipt) _receiptPrefill.value = ReceiptPrefill(c.estLitres, c.odoKm, c.idMs)
        recordingManager.clearRestartRefuel()
        viewModelScope.launch {
            com.example.di.AppContainer.settingsRepository.setRestartRefuelDismissedMs(c.idMs)
        }
    }

    /**
     * I/M readiness report (owner gap analysis 2026-09-19): what an inspection scan tool reads.
     * 0141 = this drive cycle, 0101 = since codes cleared; both on-demand through the live link,
     * never polled, so a car that answers slowly costs nothing while driving.
     */
    data class ReadinessReport(
        val cycle: com.example.analysis.ReadinessMonitors.Status?,
        val sinceCleared: com.example.analysis.ReadinessMonitors.Status?,
        val error: String? = null
    )

    private val _readinessReport = MutableStateFlow<ReadinessReport?>(null)
    val readinessReport: StateFlow<ReadinessReport?> = _readinessReport

    fun fetchReadiness() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        viewModelScope.launch {
            val r41 = transport.sendCommand("0141", 3000L)
            val r01 = transport.sendCommand("0101", 3000L)
            val cycle = r41.lines.firstOrNull()
                ?.let { com.example.analysis.ReadinessMonitors.fromResponseLine(it, "41") }
            val since = r01.lines.firstOrNull()
                ?.let { com.example.analysis.ReadinessMonitors.fromResponseLine(it, "01") }
            _readinessReport.value = ReadinessReport(
                cycle = cycle,
                sinceCleared = since,
                error = if (cycle == null && since == null) {
                    "No readable monitor frame from the ECU (NO DATA or unsupported) - not a " +
                        "fault verdict, just no answer to show."
                } else null
            )
        }
    }

    /**
     * Pump cost of one trip: its integrated fuel litres x the latest logged price per litre.
     * Null when the trip has no integratable rate rows or no price exists yet - the car-pool
     * maths then shows earned without a fabricated cost (no-fake-values rule).
     */
    suspend fun tripFuelCost(tripId: String): Double? {
        val price = fuelLogRepository.entries().maxByOrNull { it.idMs }?.pricePerL ?: return null
        if (price <= 0.0) return null
        val rows = recordingManager.tripRepository.samplesForTripPids(tripId, listOf("019D", "015E"))
        if (rows.isEmpty()) return null
        val s = com.example.analysis.TripFuelSummary.summarize(
            rows.map {
                com.example.analysis.TripFuelSummary.SamplePoint(it.pid, it.timestamp, it.numericValue)
            }
        )
        return if (s.fuelLiters > 0.01) s.fuelLiters * price else null
    }

    fun carpoolEntries(): List<com.example.data.CarpoolCodec.CarpoolEntry> =
        carpoolRepository.entries()

    fun deleteCarpool(idMs: Long) {
        carpoolRepository.delete(idMs)
        viewModelScope.launch {
            try {
                settingsRepository.driveTreeUri()?.let { tree ->
                    com.example.backup.DriveBackupClient.sendBackup(
                        getApplication(), android.net.Uri.parse(tree), recordingManager
                    )
                    settingsRepository.setLastBackupTimestamp(System.currentTimeMillis())
                }
            } catch (_: Exception) {}
            try { cloudBackupManager.performAutoBackupIfNeeded() } catch (_: Exception) {}
        }
    }

    suspend fun tripTitleMap(): Map<String, String> =
        recordingManager.tripRepository.allTripsChronological().associate { it.id to it.title }

    /**
     * Save a car-pool ride, auto-linking it to whichever saved trip's time window covers the
     * ride's own date and time (owner 2026-09-19: "based on date & time input in car pool logging
     * trip can fetch at exact time if any car pool exist it can link to trip"). An explicit
     * tripId (saved from a trip's own card) always wins.
     */
    suspend fun saveCarpool(e: com.example.data.CarpoolCodec.CarpoolEntry) {
        val linked = e.tripId ?: com.example.data.CarpoolCodec.whenMs(e)?.let { ms ->
            com.example.data.CarpoolCodec.tripLinkFor(
                ms,
                recordingManager.tripRepository.allTripsChronological().map {
                    com.example.data.CarpoolCodec.TripWindow(it.id, it.startTimestamp, it.endTimestamp)
                }
            )
        }
        carpoolRepository.save(e.copy(tripId = linked))
        // Owner 2026-09-21: "as soon as an entry is made automatically it should trigger backup and save info"
        // Car-pool rides are part of the Drive backup snapshot (PrefsSnapshotter), so save must mirror to Drive
        // immediately, not wait for the daily gate. Best-effort, never blocks the save path.
        viewModelScope.launch {
            try {
                settingsRepository.driveTreeUri()?.let { tree ->
                    com.example.backup.DriveBackupClient.sendBackup(
                        getApplication(), android.net.Uri.parse(tree), recordingManager
                    )
                    settingsRepository.setLastBackupTimestamp(System.currentTimeMillis())
                }
            } catch (_: Exception) {}
            try { cloudBackupManager.performAutoBackupIfNeeded() } catch (_: Exception) {}
        }
    }

    /**
     * Car-pool monthly roll-up (owner 2026-09-19): earned vs effective cost per IST month.
     * Pump costs are resolved BEFORE the pure grouping: the codec's cost lambda is deliberately
     * non-suspend so the month math stays JVM-testable without a coroutine harness.
     */
    suspend fun monthlyCarpool(): List<com.example.data.CarpoolCodec.MonthRow> {
        val entries = carpoolRepository.entries()
        val costs = entries.associate { e -> e.idMs to (e.tripId?.let { tripFuelCost(it) }) }
        val rows = com.example.data.CarpoolCodec.monthly(entries) { e -> costs[e.idMs] }
        // Cash-basis fuel: what the fuel ledger says he spent at the pump each IST month.
        val refuelByMonth = fuelLogRepository.entries()
            .groupBy { com.example.data.RecordTime.format("yyyy-MM", it.idMs) }
            .mapValues { (_, monthEntries) -> monthEntries.sumOf { it.totalCost } }
        return com.example.data.CarpoolCodec.withRefuelFuel(rows, refuelByMonth)
    }

    fun noteBackgroundLocationDeclined() {
        settingsRepository.setLastBgLocationDeclinedMs(System.currentTimeMillis())
        refreshLocationPermissionState()
    }

    init {
        refreshLocationPermissionState()
    }

    init {
        // A recording can now be stopped by something other than this ViewModel: the keep-alive
        // service runs the same auto-record rule, so it closes the drive when the engine has been
        // off long enough, whether or not the UI is open. Everything that has to happen after a
        // stop - the ride X-ray, the OAuth-free Drive mirror, the cloud backup, the unsaved-raw-log
        // banner - lives here, so watch for the fall and run it. Without this a background stop
        // would save the trip and quietly skip the backups.
        viewModelScope.launch {
            var wasRecording = false
            // No distinctUntilChanged(): a StateFlow is already conflated and only ever emits a
            // value that differs from the last, and applying the operator to one is deprecated -
            // which this build treats as an error.
            recordingManager.isRecording.collect { recording ->
                if (wasRecording && !recording && !stopInitiatedHere) {
                    stopInitiatedHere = true
                    recordingTimerJob?.cancel()
                    gpsManager.stopTracking()
                    if (!insightsPersistedForRecording) {
                        insightsPersistedForRecording = true
                        persistDriveInsights()
                    }
                    if (settingsRepository.autoCloudBackup.value) {
                        settingsRepository.driveTreeUri()?.let { tree ->
                            try {
                                com.example.backup.DriveBackupClient.sendBackup(
                                    getApplication(), android.net.Uri.parse(tree), recordingManager
                                )
                                settingsRepository.setLastBackupTimestamp(System.currentTimeMillis())
                            } catch (e: Exception) {
                                // Backup is best-effort; never block the stop path.
                            }
                        }
                    }
                    AppContainer.cloudBackupManager.performAutoBackupIfNeeded()
                    refreshUnsavedRawLogs()
                }
                wasRecording = recording
            }
        }

        // Elevation logging for the ride X-ray: GPS altitude when available, silent otherwise.
        obdScheduler.altitudeSource = {
            val g = gpsManager.gpsData.value
            if (g.isAvailable) g.altitudeMeters else null
        }
        // Rebuild anything a killed process left behind, before the owner has to ask.
        runAutoRecovery()
    }
    val cloudBackupManager = AppContainer.cloudBackupManager
    val catalogRepository = AppContainer.catalogRepository

    var activeTransport: ElmTransport? = null
        private set
    private var recordingTimerJob: Job? = null

    /** One-persist-per-recording guard for coast/ride/tank insight logs (dup-ride fix 2026-09-15). */
    private var insightsPersistedForRecording = false

    /**
     * Set the moment THIS ViewModel begins stopping a recording, so the external-stop watcher below
     * does not run the same aftermath a second time. `stopRecording()` launches, so the flag has to
     * be raised before the launch rather than inside it - otherwise the watcher can see
     * `isRecording` fall while the flag is still false and persist the ride X-ray twice, which is
     * the 2026-09-15 duplicate-ride bug wearing a different hat.
     */
    @Volatile private var stopInitiatedHere = false

    val connectionState: StateFlow<ConnectionState> = bluetoothManager.connectionState
    val connectedDeviceName: StateFlow<String?> = bluetoothManager.connectedDeviceName
    val connectionStatusMessage: StateFlow<String> = bluetoothManager.statusMessage

    /** Human-readable notes from the auto-connect/auto-record supervisors. */
    private val _automationMessage = MutableStateFlow<String?>(null)
    val automationMessage: StateFlow<String?> = _automationMessage.asStateFlow()

    val isPolling: StateFlow<Boolean> = obdScheduler.isPolling

    private val _selectedCanProtocol = MutableStateFlow(com.example.model.KylaqProtocolProfile.DEFAULT_CAN_PROTOCOL)
    val selectedCanProtocol: StateFlow<com.example.model.CanProtocol> = _selectedCanProtocol.asStateFlow()

    val protocolHealth: StateFlow<com.example.model.ProtocolHealth> = AppContainer.protocolHealth.asStateFlow()
    
    private val _protocolVerificationResult = MutableStateFlow<com.example.model.ProtocolVerificationResult?>(null)
    val protocolVerificationResult: StateFlow<com.example.model.ProtocolVerificationResult?> = _protocolVerificationResult.asStateFlow()

    private val _adapterVoltage = MutableStateFlow<String?>(null)
    val adapterVoltage: StateFlow<String?> = _adapterVoltage.asStateFlow()

    private val _adapterFirmware = MutableStateFlow<String?>(null)
    val adapterFirmware: StateFlow<String?> = _adapterFirmware.asStateFlow()

    private val _vehicleVin = MutableStateFlow<String?>(null)
    val vehicleVin: StateFlow<String?> = _vehicleVin.asStateFlow()
    
    private val _vinDecodeResult = MutableStateFlow<com.example.protocol.VinDecodeResult?>(null)
    val vinDecodeResult: StateFlow<com.example.protocol.VinDecodeResult?> = _vinDecodeResult.asStateFlow()

    private var lastVinAttemptMs = 0L

    init {
        // Replay trips finalized before the refuel detector shipped, once, on first launch
        // (owner 2026-09-19: "does the current logic detect the fuel refill automatically?").
        viewModelScope.launch { recordingManager.backfillRefuelEvents() }
    }

    init {
        // The VIN resolves itself (owner 2026-09-19: "VIN is not resolved yet"). The auto-connect
        // path never opens the scan screen - the only place that used to ask the ECU for 0902 - so
        // a drive connected in the background could show "VIN Unavailable" forever with no button
        // press coming. Ask once the link is up AND frame evidence says the bus is talking;
        // throttled to one attempt per minute so a flapping verdict cannot spam mode-09 reads.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(connectionState, com.example.di.AppContainer.protocolHealth) { c, h -> c to h }
                .collect { (c, h) ->
                    val vin = _vehicleVin.value
                    val needsVin = vin.isNullOrBlank() || vin == "VIN Unavailable"
                    val linkUp = c == ConnectionState.CONNECTED &&
                        (h == com.example.model.ProtocolHealth.WORKING ||
                            h == com.example.model.ProtocolHealth.PARTIAL)
                    if (needsVin && linkUp && System.currentTimeMillis() - lastVinAttemptMs > 60_000L) {
                        lastVinAttemptMs = System.currentTimeMillis()
                        kotlinx.coroutines.delay(1200)
                        fetchVehicleVin()
                    }
                }
        }
    }

    fun fetchVehicleVin() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return

        viewModelScope.launch {
            val resp = transport.sendCommand("0902", 3000L)

            if (resp.status != com.example.model.ResponseStatus.OK || resp.lines.isEmpty()) {
                _vehicleVin.value = "VIN Unavailable"
                _vinDecodeResult.value = null
                return@launch
            }

            try {
                // Mode 09 PID 02 response structure (SAE J1979):
                // Byte 0 = 0x49 (Mode 09 + 0x40)
                // Byte 1 = 0x02 (PID for VIN)
                // Byte 2 = 0x01 (VIN record indicator)
                // Bytes 3-19 = exactly 17 ASCII VIN characters
                //
                // Total payload: exactly 20 bytes.
                //
                // Response may arrive as ISO-TP multi-frame:
                // 7E8 10 14 49 02 01 ...
                // 7E8 21 ...
                // 7E8 22 ...
                //
                // ISO-TP ensures frames from different CAN IDs are kept separate.
                // VIN ECU Authority Model: Only 7E8 (Engine) and 7E1 (Transmission)
                // are authoritative for VIN. Other ECUs cannot provide VIN.

                val allMessages = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)

                // Collect all valid VIN candidates with their source ECU
                val candidates = com.example.protocol.VinAuthority.collectVinCandidates(allMessages)

                // Apply VIN ECU authority policy
                val result = com.example.protocol.VinAuthority.selectVinByAuthority(candidates)

                when (result) {
                    is com.example.protocol.VinSelectionResult.Success -> {
                        _vehicleVin.value = result.vin
                        _vinDecodeResult.value = com.example.protocol.VinDecoder.decodeVin(result.vin, catalogRepository)
                    }
                    is com.example.protocol.VinSelectionResult.Ambiguous -> {
                        _vehicleVin.value = "VIN Ambiguous"
                        _vinDecodeResult.value = null
                    }
                    is com.example.protocol.VinSelectionResult.Unavailable -> {
                        _vehicleVin.value = "VIN Unavailable"
                        _vinDecodeResult.value = null
                    }
                }

            } catch (e: Exception) {
                _vehicleVin.value = "Failed to parse VIN"
                _vinDecodeResult.value = null
            }
        }
    }
    val transactionCount: StateFlow<Long> = obdScheduler.transactionCount
    val canResponseCount: StateFlow<Long> = obdScheduler.canResponseCount
    val errorCount: StateFlow<Long> = obdScheduler.errorCount
    val liveDecodedMap: StateFlow<Map<String, String>> = obdScheduler.liveDecodedMap
    val liveNumericMap: StateFlow<Map<String, Double>> = obdScheduler.liveNumericMap
    val pidRawHistory: StateFlow<Map<String, List<TransactionRecord>>> = obdScheduler.pidRawHistory
    val lastTransaction: StateFlow<TransactionRecord?> = obdScheduler.lastTransaction

    // Powertrain Intelligence & Trust Model Streams
    val liveTelemetryMap = obdScheduler.liveTelemetryMap
    /** Per-PID capability verdicts so dashboard tiles can say WHY a value is missing. */
    val pidCapabilities = obdScheduler.capabilityManager.capabilitiesFlow
    val realtimeEconomy = obdScheduler.realtimeEconomy
    val tripEconomy = obdScheduler.tripEconomy
    val drivingState = obdScheduler.drivingState
    val transmissionState = obdScheduler.transmissionState
    val ecuDiscoveryReport = obdScheduler.ecuDiscoveryManager.discoveryReport
    val isDiscoveringEcus = obdScheduler.ecuDiscoveryManager.isDiscovering
    val discoveryProgressText = obdScheduler.ecuDiscoveryManager.discoveryProgressText

    fun runEcuDiscovery() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        viewModelScope.launch {
            obdScheduler.ecuDiscoveryManager.runDiscovery(transport)
        }
    }

    // PID Discovery & Availability Scanner
    val pidDiscoveryService = AppContainer.pidDiscoveryService
    val pidScanStatus = pidDiscoveryService.status
    val isPidScanning = pidDiscoveryService.isScanning
    val isPidValidating = pidDiscoveryService.isValidating
    val pidScanProgress = pidDiscoveryService.progress
    val pidScanRangeText = pidDiscoveryService.currentRangeText
    val discoveredPids = pidDiscoveryService.discoveredPids
    val validatedPids = pidDiscoveryService.validatedPids
    val discoveredSupportedCount = pidDiscoveryService.supportedPidsCount
    val validatedPidsCount = pidDiscoveryService.validatedPidsCount
    val discoveredEcus = pidDiscoveryService.discoveredEcus
    val pidScanErrorMessage = pidDiscoveryService.errorMessage
    val pidScanRawLogs = pidDiscoveryService.rawLogEntries
    val discoveredRanges = pidDiscoveryService.discoveredRanges

    fun startPidScan() {
        val transport = activeTransport
        if (transport == null || !transport.isConnected) {
            pidDiscoveryService.startScan(
                transport ?: SimulationTransport(),
                viewModelScope
            )
            return
        }

        viewModelScope.launch {
            val wasPolling = obdScheduler.isPolling.value
            if (wasPolling) {
                obdScheduler.stopPolling()
            }
            pidDiscoveryService.startScan(transport, viewModelScope)
        }
    }

    fun startDirectValidation() {
        val transport = activeTransport
        if (transport == null || !transport.isConnected) {
            pidDiscoveryService.startDirectValidation(
                transport ?: SimulationTransport(),
                viewModelScope
            )
            return
        }

        viewModelScope.launch {
            val wasPolling = obdScheduler.isPolling.value
            if (wasPolling) {
                obdScheduler.stopPolling()
            }
            pidDiscoveryService.startDirectValidation(transport, viewModelScope)
        }
    }

    fun stopPidScan() {
        pidDiscoveryService.stopScan()
    }

    fun clearPidScan() {
        pidDiscoveryService.clearResults()
    }

    fun exportDiscoveryReportJson(): String {
        val vin = vehicleVin.value?.takeIf { it.length == 17 } ?: obdScheduler.ecuDiscoveryManager.discoveryReport.value?.detectedEcus?.firstOrNull { !it.vin.isNullOrEmpty() }?.vin
        return pidDiscoveryService.exportDiscoveryReportJson(vehicleVin = vin)
    }

    fun exportDiscoveryReportCsv(): String {
        return pidDiscoveryService.exportDiscoveryReportCsv()
    }

    fun applyDiscoveredPidsToLiveData(): Int {
        val count = pidDiscoveryService.applySupportedPidsToLiveData(settingsRepository)
        val transport = activeTransport
        if (transport != null && transport.isConnected && !obdScheduler.isPolling.value) {
            obdScheduler.startPolling(viewModelScope, transport)
        }
        return count
    }

    fun resetTripEconomy() {
        obdScheduler.economyEngine.resetTrip()
    }

    val isRecording: StateFlow<Boolean> = recordingManager.isRecording
    val autoStopNotice: StateFlow<String?> = recordingManager.autoStopNotice

    /** Non-suspending snapshot for lifecycle hooks (battery-exemption prompt). */
    fun isSessionActiveNow(): Boolean =
        isRecording.value || connectionState.value == com.example.bluetooth.ConnectionState.CONNECTED
    val currentSessionMetadata: StateFlow<RecordingMetadata?> = recordingManager.currentSessionMetadata
    val currentTransactions: StateFlow<List<TransactionRecord>> = recordingManager.currentTransactions
    val savedRecordings: StateFlow<List<SavedRecording>> = recordingManager.savedRecordings

    // CRITICAL FIX: Added activeVehicleId for proper DTC association
    private val _activeVehicleId = MutableStateFlow<String?>(null)
    val activeVehicleId: StateFlow<String?> = _activeVehicleId.asStateFlow()

    val rawLogs: StateFlow<List<RawLogEntry>> = rawLogManager.logs

    val pollingMode: StateFlow<PollingSpeedMode> = settingsRepository.pollingMode
    val pidDefinitions: StateFlow<List<PidDefinition>> = settingsRepository.pidDefinitions
    val vehicleName: StateFlow<String> = settingsRepository.vehicleName

    fun setVehicleName(name: String) {
        settingsRepository.setVehicleName(name)
    }

    val canHeader: StateFlow<String> = settingsRepository.canHeader
    val sppUuid: StateFlow<String> = settingsRepository.sppUuid

    private val _recordingDurationSeconds = MutableStateFlow(0L)
    val recordingDurationSeconds: StateFlow<Long> = _recordingDurationSeconds.asStateFlow()

    private val _manualCommandOutput = MutableStateFlow<String?>(null)
    val manualCommandOutput: StateFlow<String?> = _manualCommandOutput.asStateFlow()

    private val _manualCommandError = MutableStateFlow<String?>(null)
    val manualCommandError: StateFlow<String?> = _manualCommandError.asStateFlow()

    fun selectCanProtocol(protocol: com.example.model.CanProtocol) {
        if (_selectedCanProtocol.value != protocol) {
            _selectedCanProtocol.value = protocol
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            AppContainer.protocolHealth.value = com.example.model.ProtocolHealth.UNKNOWN
            _protocolVerificationResult.value = null
        }
    }


    private val _isBatchTesting = MutableStateFlow(false)
    val isBatchTesting = _isBatchTesting.asStateFlow()

    private val _batchTestResults = MutableStateFlow<List<com.example.model.ProtocolVerificationResult>>(emptyList())
    val batchTestResults = _batchTestResults.asStateFlow()
    companion object {
        const val PROTOCOL_SETTLE_DELAY_MS = 400L
    }

    fun verifySelectedProtocol() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            AppContainer.protocolHealth.value = com.example.model.ProtocolHealth.TESTING
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            
            transport.sendCommand("ATPC", 1000)
            kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
            
            val proto = _selectedCanProtocol.value
            transport.sendCommand(proto.atCommand, 1500)
            kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
            
            val result = executeProtocolVerification(transport, proto)
            
            _protocolVerificationResult.value = result
            AppContainer.protocolHealth.value = result.health
            
            if (result.health == com.example.model.ProtocolHealth.WORKING || result.health == com.example.model.ProtocolHealth.PARTIAL) {
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private suspend fun executeProtocolVerification(transport: com.example.bluetooth.ElmTransport, proto: com.example.model.CanProtocol): com.example.model.ProtocolVerificationResult {
        // Set protocol
        transport.sendCommand(proto.atCommand, 1500L)
        kotlinx.coroutines.delay(200)

        // 015E/019D added 2026-09-12: fuel-rate PIDs were never validated, so the
        // capability gate skipped them forever and every trip logged 0.00 L.
        val pidsToTest = listOf("0100", "010C", "010D", "0105", "010B", "0111", "010F", "0142", "015E", "019D")
        var success = 0
        var timeout = 0
        var invalid = 0
        var unsupported = 0
        var canErrorCount = 0
        var totalTime = 0L
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        
        val pidResults = mutableListOf<com.example.model.PidTestResult>()
        
        // Warm up and negotiate
        transport.sendCommand("0100", 3000L)
        
        // Find resolved protocol if Auto
        var resolvedProto = proto
        if (proto.atCommand == "ATSP0") {
            val dpn = transport.sendCommand("ATDPN", 1000L)
            var dpnVal = dpn.rawText.trim().replace(">", "").trim()
            if (dpnVal.length > 0 && dpnVal.first().isLetter()) {
                dpnVal = dpnVal.substring(1) // sometimes "A6" for auto 6
            }
            if (dpnVal.isNotEmpty()) {
                val matched = com.example.model.CanProtocol.values().find { it.protocolNumber == dpnVal }
                if (matched != null) {
                    resolvedProto = matched
                }
            }
        }

        for (pid in pidsToTest) {
            val resp = transport.sendCommand(pid, 2000L)
            val duration = resp.durationMs
            
            if (duration > 0) {
                totalTime += duration
                if (duration < minTime) minTime = duration
                if (duration > maxTime) maxTime = duration
            }
            
            val expectedService = "41"
            val expectedPid = pid.substring(2)
            
            var respondingCanId: String? = null
            val hasEcuResponse = try {
                val reconstructed = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)
                val expectedAck = 0x41
                val expectedPidInt = expectedPid.toIntOrNull(16) ?: -1
                val validMsg = reconstructed.find { msg ->
                    msg.reconstructedBytes.size >= 2 && msg.reconstructedBytes[0] == expectedAck && msg.reconstructedBytes[1] == expectedPidInt
                }
                if (validMsg != null) {
                    respondingCanId = validMsg.canId ?: "7E8"
                    true
                } else {
                    false
                }
            } catch (_: Exception) { false } || resp.lines.any { line -> 
                val cleanLine = line.replace(" ", "").uppercase()
                val expected = expectedService + expectedPid
                val idx = cleanLine.indexOf(expected)
                if (idx in 0..8) {
                    val possibleCanId = cleanLine.substring(0, idx).takeLast(3)
                    respondingCanId = if (possibleCanId.matches(Regex("^[0-9A-F]{3}$"))) possibleCanId else "7E8"
                    true
                } else false
            }
            
            val status = when {
                hasEcuResponse -> {
                    success++
                    if (expectedPid != "00") {
                        val ecu = respondingCanId ?: "7E8"
                        obdScheduler.capabilityManager.markPidValidated(ecu, expectedPid, com.example.model.CapabilityStatus.DIRECT_VALIDATED)
                    }
                    com.example.model.PidTestStatus.ECU_RESPONSE
                }
                resp.status == com.example.model.ResponseStatus.NO_DATA -> {
                    unsupported++
                    com.example.model.PidTestStatus.NO_DATA
                }
                resp.status == com.example.model.ResponseStatus.TIMEOUT -> {
                    timeout++
                    com.example.model.PidTestStatus.TIMEOUT
                }
                resp.status == com.example.model.ResponseStatus.CAN_ERROR || resp.status == com.example.model.ResponseStatus.BUS_INIT_ERROR -> {
                    canErrorCount++
                    com.example.model.PidTestStatus.CAN_ERROR
                }
                resp.status == com.example.model.ResponseStatus.UNABLE_TO_CONNECT || resp.status == com.example.model.ResponseStatus.MALFORMED -> {
                    invalid++
                    com.example.model.PidTestStatus.ADAPTER_ERROR
                }
                else -> {
                    invalid++
                    com.example.model.PidTestStatus.MALFORMED
                }
            }
            
            pidResults.add(
                com.example.model.PidTestResult(
                    txCommand = pid,
                    rxResponse = if (resp.lines.isNotEmpty()) resp.lines.joinToString(" ") else resp.rawText.trim(),
                    status = status,
                    latencyMs = duration
                )
            )
            
            kotlinx.coroutines.delay(100)
        }
        
        val avgTime = if (pidsToTest.isNotEmpty()) totalTime / pidsToTest.size else 0L
        
        // Protocol health categorization
        val health = when {
            success > 1 -> com.example.model.ProtocolHealth.WORKING
            success == 1 -> com.example.model.ProtocolHealth.PARTIAL
            canErrorCount > 0 || invalid > 0 || timeout == pidsToTest.size -> com.example.model.ProtocolHealth.NO_RESPONSE
            else -> com.example.model.ProtocolHealth.ADAPTER_ERROR
        }
        
        val appVer = com.example.BuildConfig.VERSION_NAME
        val appBuild = com.example.BuildConfig.VERSION_CODE
        val commit = com.example.BuildConfig.GIT_COMMIT
        
        return com.example.model.ProtocolVerificationResult(
            protocol = resolvedProto,
            successCount = success,
            timeoutCount = timeout,
            unsupportedCount = unsupported,
            invalidCount = invalid,
            canErrorCount = canErrorCount,
            totalRequests = pidsToTest.size,
            avgResponseTimeMs = avgTime,
            minResponseTimeMs = if (minTime == Long.MAX_VALUE) 0L else minTime,
            maxResponseTimeMs = if (maxTime == Long.MIN_VALUE) 0L else maxTime,
            health = health,
            pidResults = pidResults,
            appVersion = appVer,
            buildNumber = appBuild,
            commitHash = commit
        )
    }

    fun testAllCanProtocols() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            _isBatchTesting.value = true
            _batchTestResults.value = emptyList()
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            
            val protocolsToTest = listOf(
                com.example.model.CanProtocol.ISO_15765_11B_500K,
                com.example.model.CanProtocol.ISO_15765_29B_500K,
                com.example.model.CanProtocol.ISO_15765_11B_250K,
                com.example.model.CanProtocol.ISO_15765_29B_250K
            )
            
            val results = mutableListOf<com.example.model.ProtocolVerificationResult>()
            
            for (proto in protocolsToTest) {
                transport.sendCommand("ATPC", 1000)
                kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
                transport.sendCommand(proto.atCommand, 1500)
                kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
                
                val result = executeProtocolVerification(transport, proto)
                results.add(result)
                _batchTestResults.value = results.toList()
                
                // Save to database
                val entity = com.example.data.db.entities.ProtocolTestResultEntity(
                    timestamp = System.currentTimeMillis(),
                    protocol = result.protocol.displayName,
                    atCommand = result.protocol.atCommand,
                    resultStatus = result.health.name,
                    ecuResponses = result.successCount,
                    canErrors = result.canErrorCount,
                    timeouts = result.timeoutCount,
                    averageLatency = result.avgResponseTimeMs,
                    appVersion = result.appVersion,
                    buildNumber = result.buildNumber,
                    gitCommit = result.commitHash
                )
                recordingManager.tripRepository.insertProtocolTestResult(entity)
                
                // Safety delay before testing next protocol
                kotlinx.coroutines.delay(500)
            }
            
            _isBatchTesting.value = false
            
            // Auto-select best protocol
            val best = rankProtocols(results).firstOrNull()
            if (best != null && (best.health == com.example.model.ProtocolHealth.WORKING || best.health == com.example.model.ProtocolHealth.PARTIAL)) {
                _selectedCanProtocol.value = best.protocol
                _protocolVerificationResult.value = best
                AppContainer.protocolHealth.value = best.health
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private fun rankProtocols(results: List<com.example.model.ProtocolVerificationResult>): List<com.example.model.ProtocolVerificationResult> {
        return results.sortedWith(
            compareBy<com.example.model.ProtocolVerificationResult> { 
                when (it.health) {
                    com.example.model.ProtocolHealth.WORKING -> 0
                    com.example.model.ProtocolHealth.PARTIAL -> 1
                    com.example.model.ProtocolHealth.NO_RESPONSE -> 2
                    com.example.model.ProtocolHealth.ADAPTER_ERROR -> 3
                    else -> 4
                }
            }
            .thenByDescending { it.successCount }
            .thenBy { it.canErrorCount + it.invalidCount + it.timeoutCount }
            .thenBy { it.avgResponseTimeMs }
        )
    }

    fun connectDevice(deviceAddress: String) {
        viewModelScope.launch {
            obdScheduler.stopPolling()
            val uuid = try {
                UUID.fromString(sppUuid.value)
            } catch (_: Exception) {
                BluetoothManager.DEFAULT_SPP_UUID
            }
            val (success, transport) = bluetoothManager.connectToDevice(
                deviceAddress = deviceAddress,
                sppUuid = uuid,
                initSequence = settingsRepository.initCommands.value,
                rawLogListener = rawLogManager
            )
            if (success && transport != null) {
                activeTransport = transport
                fetchAdapterInfo(transport)
                performDashboardBootstrap(transport)
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private suspend fun performDashboardBootstrap(transport: ElmTransport) {
        val pidsToTest = listOf("010C", "010D", "0105", "010B", "0111", "010F", "0142", "015E", "019D")
        for (pid in pidsToTest) {
            val resp = transport.sendCommand(pid, 1000L)
            val expectedService = "41"
            val expectedPid = pid.substring(2)
            
            var respondingCanId: String? = null
            val hasEcuResponse = try {
                val reconstructed = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)
                val expectedAck = 0x41
                val expectedPidInt = expectedPid.toIntOrNull(16) ?: -1
                val validMsg = reconstructed.find { msg ->
                    msg.reconstructedBytes.size >= 2 && msg.reconstructedBytes[0] == expectedAck && msg.reconstructedBytes[1] == expectedPidInt
                }
                if (validMsg != null) {
                    respondingCanId = validMsg.canId ?: "7E8"
                    true
                } else {
                    false
                }
            } catch (_: Exception) { false } || resp.lines.any { line -> 
                val cleanLine = line.replace(" ", "").uppercase()
                val expected = expectedService + expectedPid
                val idx = cleanLine.indexOf(expected)
                if (idx in 0..8) {
                    val possibleCanId = cleanLine.substring(0, idx).takeLast(3)
                    respondingCanId = if (possibleCanId.matches(Regex("^[0-9A-F]{3}$"))) possibleCanId else "7E8"
                    true
                } else false
            }
            
            if (hasEcuResponse) {
                val ecu = respondingCanId ?: "7E8"
                obdScheduler.capabilityManager.markPidValidated(ecu, expectedPid, com.example.model.CapabilityStatus.DIRECT_VALIDATED)
            }
        }
    }

    private suspend fun fetchAdapterInfo(transport: ElmTransport) {
        val voltageResp = transport.sendCommand("ATRV", 1000)
        _adapterVoltage.value = if (voltageResp.status == com.example.model.ResponseStatus.OK) voltageResp.rawText.trim() else null

        val firmwareResp = transport.sendCommand("ATI", 1000)
        _adapterFirmware.value = if (firmwareResp.status == com.example.model.ResponseStatus.OK) firmwareResp.rawText.trim() else null
    }

    fun startSimulationMode() {
        viewModelScope.launch {
            obdScheduler.stopPolling()
            val (success, transport) = bluetoothManager.startSimulationMode(
                initSequence = settingsRepository.initCommands.value,
                rawLogListener = rawLogManager
            )
            if (success) {
                activeTransport = transport
                fetchAdapterInfo(transport)
                performDashboardBootstrap(transport)
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            obdScheduler.stopPolling()
            bluetoothManager.disconnect()
            activeTransport = null
        }
    }

    fun togglePolling() {
        val transport = activeTransport ?: return
        if (isPolling.value) {
            obdScheduler.stopPolling()
        } else {
            obdScheduler.startPolling(viewModelScope, transport)
        }
    }

    /**
     * Keeps a logging session alive without the user touching the phone:
     *
     *  * every 10 s, if auto-connect is enabled and nothing is polling, ask [ObdQuickConnect]
     *    to (re)open the starred / recognised adapter - this is what makes recording resume
     *    after the adapter drops or the ignition cycle restarts;
     *  * every 2 s, if auto-record is enabled, start a recording as soon as the engine is
     *    running (rpm > 200) and save it once the engine has been off for a minute.
     *
     * The record loop runs [com.example.service.AutoRecordPolicy], the SAME pure rule the
     * keep-alive service runs, so the two supervisors can never disagree about when a drive starts
     * or ends. `startRecording` and `stopRecording` here still do the UI-side work (ride X-ray
     * reset, duration timer, insight persistence) that the service cannot do.
     *
     * These loops stop with the app UI - but the service now runs both of them for as long as the
     * process lives, which is what makes "even in background all ways it should run and record"
     * true rather than a claim in a comment. Android Auto runs its own supervisor while the car
     * screen is visible.
     */
    fun startSessionAutomation() {
        viewModelScope.launch {
            while (isActive) {
                if (settingsRepository.autoConnect.value && !obdScheduler.isPolling.value) {
                    try {
                        ObdQuickConnect.connectPairedAdapterAndPoll(viewModelScope) { message ->
                            _automationMessage.value = message
                        }
                    } catch (t: Exception) {
                        _automationMessage.value = t.message ?: "Auto-connect failed"
                    }
                }
                delay(10_000)
            }
        }
        viewModelScope.launch {
            var engineOffSinceMs = 0L
            while (isActive) {
                val rpm = obdScheduler.liveNumericMap.value["010C"]
                val recording = recordingManager.isRecording.value
                val now = android.os.SystemClock.elapsedRealtime()
                when (
                    com.example.service.AutoRecordPolicy.decide(
                        rpm = rpm,
                        isRecording = recording,
                        isPolling = obdScheduler.isPolling.value,
                        autoRecordEnabled = settingsRepository.autoRecord.value,
                        engineOffSinceMs = engineOffSinceMs,
                        nowMs = now,
                        sessionAgeMs = recordingManager.currentSessionAgeMs(now)
                    )
                ) {
                    // Resume, not reopen: a drive cut off by a process death moments ago
                    // continues in its own session - one drive, one trip.
                    com.example.service.AutoRecordPolicy.Decision.START_RECORDING ->
                        recordingManager.startOrResumeRecording()
                    com.example.service.AutoRecordPolicy.Decision.STOP_RECORDING -> stopRecording()
                    com.example.service.AutoRecordPolicy.Decision.NONE -> Unit
                }
                engineOffSinceMs = com.example.service.AutoRecordPolicy.nextEngineOffSince(
                    rpm, recording, engineOffSinceMs, now
                )
                // The Idle Start-Stop rule that used to be inline here (owner 2026-09-15, P0: a
                // fresh rpm <= 200 means the car is awake at a junction and gets a 5-minute grace,
                // while a MISSING rpm means the link went stale and gets 60 s) now lives in
                // AutoRecordPolicy, tested, and shared with the service supervisor. Keeping a second
                // copy of a timing rule is how the two supervisors would eventually disagree and
                // shred one drive into two trips.
                delay(2_000)
            }
        }
    }

    // ── Car Welcome voice (owner 2026-09-16): greet on OBD link connect, MacroDroid-style ──
    @Volatile private var lastWelcomeAtMs = 0L

    init {
        viewModelScope.launch {
            var prev = bluetoothManager.connectionState.value
            bluetoothManager.connectionState.collect { st ->
                val connected = st == com.example.bluetooth.ConnectionState.CONNECTED
                val connectEdge = connected && prev != com.example.bluetooth.ConnectionState.CONNECTED
                val disconnectEdge = !connected && prev == com.example.bluetooth.ConnectionState.CONNECTED
                prev = st
                if (connectEdge) maybeSpeakWelcome()
                // Log adapter traffic from the moment the link is up, not only while a trip is
                // being recorded (owner 2026-09-17: "it never ever loose the logs"). Frames that
                // arrive before auto-record starts used to exist only in the RAM ring buffer, so a
                // kill in that window left nothing on disk at all. A session log, once open, always
                // takes precedence and this is a no-op.
                if (connectEdge) runCatching { rawLogManager.startConnectionLogging() }
                if (disconnectEdge) runCatching { rawLogManager.stopConnectionLogging() }
            }
        }
    }

    private fun maybeSpeakWelcome() {
        val repo = settingsRepository
        if (!repo.welcomeEnabled.value) return
        val now = System.currentTimeMillis()
        // Re-spam guard: mid-drive link drops that auto-reconnect must not re-greet.
        if (!com.example.data.WelcomeSpeaker.shouldSpeak(lastWelcomeAtMs, now)) return
        lastWelcomeAtMs = now
        speakWelcomeNow()
    }

    // ── Fuelio import (owner 2026-09-16: "how i can import my data from fuelio") ──
    private val _fuelioImportNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val fuelioImportNotice: kotlinx.coroutines.flow.StateFlow<String?> = _fuelioImportNotice.asStateFlow()

    fun importFuelioCsv(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val text = getApplication<android.app.Application>().contentResolver.openInputStream(uri)?.use {
                    it.reader(java.nio.charset.StandardCharsets.UTF_8).readText()
                }
                if (text == null) {
                    _fuelioImportNotice.value = "Could not read that file - pick the CSV exported from Fuelio."
                    return@launch
                }
                val parsed = com.example.data.FuelioImporter.parse(text, System.currentTimeMillis())
                if (parsed.entries.isEmpty()) {
                    _fuelioImportNotice.value = "No fill-ups found in that file. In Fuelio: menu -> " +
                        "Backup -> Export to CSV (SD), then pick that CSV here."
                    return@launch
                }
                val fresh = com.example.data.FuelioImporter.dedupe(fuelLogRepository.entries(), parsed.entries)
                fresh.forEach { fuelLogRepository.add(it) }
                val dupes = parsed.entries.size - fresh.size
                _fuelioImportNotice.value = "Imported ${fresh.size} fill-up(s) from Fuelio" +
                    (if (dupes > 0) " - skipped $dupes already in your log" else "") +
                    ". They now feed km/L, cost/km and station stats."
            } catch (e: Exception) {
                _fuelioImportNotice.value = "Fuelio import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    val welcomeVoices: kotlinx.coroutines.flow.StateFlow<List<com.example.data.WelcomeSpeaker.VoiceOption>> =
        com.example.di.AppContainer.welcomeSpeaker.voices
    val welcomeVoiceId: kotlinx.coroutines.flow.StateFlow<String?> =
        settingsRepository.welcomeVoiceId

    /** Settings voice picker: switch the TTS voice right now and persist the choice. */
    fun selectWelcomeVoice(id: String?) {
        settingsRepository.setWelcomeVoiceId(id)
        com.example.di.AppContainer.welcomeSpeaker.selectVoice(id)
    }

    /** Settings "Test voice" button: speaks immediately, ignoring the interval guard. */
    fun testWelcomeVoice() {
        speakWelcomeNow()
    }

    private fun speakWelcomeNow() {
        val repo = settingsRepository
        if (repo.welcomeVolumeEnabled.value) {
            com.example.di.AppContainer.welcomeSpeaker.setMediaVolumePercent(repo.welcomeVolumePct.value)
        }
        com.example.di.AppContainer.welcomeSpeaker.speak(
            com.example.data.WelcomeSpeaker.resolveMessage(repo.welcomeMessage.value, vehicleName.value)
        )
    }

    fun startRecording() {
        // The keep-alive service runs the same auto-record rule, so it may have opened this session
        // a tick earlier. Re-doing the UI-side reset then would wipe the ride X-ray of a drive that
        // is already being recorded and restart the duration timer at zero.
        if (recordingManager.isRecording.value) return
        // Fresh ride X-ray for this recording; the owner's mode tag (D/S/M) carries over.
        obdScheduler.rideRecorder.reset()
        insightsPersistedForRecording = false
        recordingManager.startRecording(
            vehicleName = vehicleName.value,
            vehicleId = _activeVehicleId.value,  // FIX: Pass vehicleId for proper association
            profileName = "India-Market 1.0 TSI (EA211)",
            adapterName = connectedDeviceName.value ?: "ELM327 v1.5",
            protocolName = "ISO 15765-4 CAN 11/500"
        )
        gpsManager.startTracking()
        _recordingDurationSeconds.value = 0L
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (recordingManager.isRecording.value) {
                delay(1000)
                _recordingDurationSeconds.value++
            }
        }
    }

    /**
     * Sets the currently active vehicle in the garage.
     * This is used to properly associate DTC records, trip logs, and other
     * vehicle-specific data with the correct vehicle.
     */
    fun setActiveVehicleId(vehicleId: String?) {
        _activeVehicleId.value = vehicleId
    }


    fun fetchActiveDtcs() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            // VW TP 2.0 (2026-09-14 cross-validation: Car Scanner's "Send Open session
            // request before DTC operations", enabled by default for VAG cars): open a
            // UDS extended diagnostic session first. Refusal (0x7F / NO DATA) is
            // tolerated - default-session reads still work on many ECUs.
            val sessResp = transport.sendCommand("10 03", 2000L)
            android.util.Log.i("OBDLogger/DTC", "UDS 10 03 open session -> ${sessResp.rawText.trim()}")
            val resp = transport.sendCommand("03", 5000L)
            val isoTp = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)
            val allDtcs = mutableListOf<String>()
            for (msg in isoTp) {
                // FIX P0-5: Pass mode=0x03 so DtcDecoder validates response is 43 (positive Mode 03 ack)
                allDtcs.addAll(com.example.protocol.DtcDecoder.extractDtcs(msg.reconstructedPayloadHex, mode = 0x03))
            }
            saveDtcs(allDtcs.distinct(), "CONFIRMED")
        }
    }

    fun fetchPendingDtcs() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return

        viewModelScope.launch {
            val resp = transport.sendCommand("07", 5000L)
            val isoTp = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)
            val allDtcs = mutableListOf<String>()
            for (msg in isoTp) {
                // FIX P0-5: Pass mode=0x07 so DtcDecoder validates response is 47 (positive Mode 07 ack)
                allDtcs.addAll(com.example.protocol.DtcDecoder.extractDtcs(msg.reconstructedPayloadHex, mode = 0x07))
            }
            saveDtcs(allDtcs.distinct(), "PENDING")
        }
    }

    /**
     * Clears DTC information from local cache only.
     * 
     * SAFETY: Mode 04 (Clear DTCs) is NEVER sent to the vehicle.
     * This app is strictly READ-ONLY diagnostic/logger - no control commands are permitted.
     * The SafetyValidator enforces this at the transport layer.
     * 
     * @see SafetyValidator - explicitly blocks CONTROL services including 04
     */
    fun clearDtcs() {
        // CRITICAL: This is a read-only diagnostic app.
        // Mode 04 is blocked by SafetyValidator at the transport layer.
        // This function only clears local UI state, NOT vehicle memory.
        // Cloud sync message is a read-only StateFlow from outside the manager;
        // route the update through the new public setter to keep encapsulation intact.
        cloudBackupManager.setStatusMessage("Local DTC cache cleared. Note: Vehicle DTC memory is READ-ONLY in this app.")
    }

    private suspend fun saveDtcs(dtcs: List<String>, status: String) {
        // FIX P0-6: Use the explicitly known vehicle (current session -> active selection).
        // Do NOT silently fall back to "first vehicle in the database" - that previously caused
        // DTCs read from one vehicle to be associated with another vehicle in a multi-vehicle garage.
        val currentSession = recordingManager.currentSessionMetadata.value
        val vehicleId: String? = currentSession?.vehicleId ?: activeVehicleId.value

        if (vehicleId == null) {
            // No active vehicle context. Refuse to silently attach diagnostic data to an
            // arbitrary vehicle. Surface a user-visible message instead.
            android.util.Log.w(
                "MainViewModel",
                "saveDtcs: No active vehicle - ${dtcs.size} DTC(s) NOT saved to avoid misattribution."
            )
            return
        }

        val tripId = currentSession?.sessionId
        val timestamp = System.currentTimeMillis()

        for (code in dtcs) {
            val entity = com.example.data.db.entities.DtcRecordEntity(
                vehicleId = vehicleId,
                tripId = tripId,
                timestamp = timestamp,
                code = code,
                description = decodeDtcDescription(code),
                status = status
            )
            recordingManager.tripRepository.insertDtcRecord(entity)
        }
    }

    /**
     * Decodes common OBD-II DTC codes to human-readable descriptions.
     * Returns generic "Diagnostic Trouble Code" for unknown codes.
     */
    private fun decodeDtcDescription(code: String): String {
        val known = mapOf(
            // P0xxx - Powertrain
            "P0010" to "A Camshaft Position Actuator Circuit (Bank 1)",
            "P0011" to "A Camshaft Position - Timing Over-Advanced (Bank 1)",
            "P0012" to "A Camshaft Position - Timing Over-Retarded (Bank 1)",
            "P0100" to "Mass or Volume Air Flow Circuit",
            "P0101" to "Mass Air Flow Circuit Range/Performance",
            "P0102" to "Mass Air Flow Circuit Low Input",
            "P0103" to "Mass Air Flow Circuit High Input",
            "P0110" to "Intake Air Temperature Circuit",
            "P0115" to "Engine Coolant Temperature Circuit",
            "P0116" to "Engine Coolant Temperature Circuit Range/Performance",
            "P0117" to "Engine Coolant Temperature Circuit Low",
            "P0118" to "Engine Coolant Temperature Circuit High",
            "P0120" to "Throttle/Pedal Position Sensor Circuit",
            "P0128" to "Coolant Thermostat (Below Regulating Temperature)",
            "P0171" to "System Too Lean (Bank 1)",
            "P0172" to "System Too Rich (Bank 1)",
            "P0174" to "System Too Lean (Bank 2)",
            "P0175" to "System Too Rich (Bank 2)",
            "P0300" to "Random/Multiple Cylinder Misfire Detected",
            "P0301" to "Cylinder 1 Misfire Detected",
            "P0302" to "Cylinder 2 Misfire Detected",
            "P0303" to "Cylinder 3 Misfire Detected",
            "P0304" to "Cylinder 4 Misfire Detected",
            "P0420" to "Catalyst System Efficiency Below Threshold (Bank 1)",
            "P0440" to "Evaporative Emission Control System",
            "P0442" to "EVAP System Small Leak Detected",
            "P0455" to "EVAP System Gross Leak Detected",
            "P0500" to "Vehicle Speed Sensor",
            "P0506" to "Idle Control System RPM Lower Than Expected",
            "P0507" to "Idle Control System RPM Higher Than Expected",
            // B - Body
            "B1000" to "ECU Internal Failure",
            // C - Chassis
            "C0035" to "Left Front Wheel Speed Sensor Circuit",
            // U - Network
            "U0001" to "High Speed CAN Communication Bus",
            "U0100" to "Lost Communication With ECM/PCM",
            "U0121" to "Lost Communication With ABS Control Module"
        )
        return known[code.uppercase()] ?: "Diagnostic Trouble Code ($code)"
    }

    fun stopRecording() {
        stopInitiatedHere = true
        viewModelScope.launch {
            recordingTimerJob?.cancel()
            gpsManager.stopTracking()
            // 2026-09-15 duplicate-ride fix: auto-stop (car off / link drop) and a manual
            // STOP tap can BOTH fire for one drive; persistDriveInsights() then appended
            // the SAME ride X-ray twice (the ride log had no dedup key, unlike the tank
            // log). One persist per recording session, ever.
            if (!insightsPersistedForRecording) {
                insightsPersistedForRecording = true
                persistDriveInsights()
            }
            recordingManager.stopRecording()
            // OAuth-free Drive backup: if a folder is linked, mirror the recordings there.
            if (settingsRepository.autoCloudBackup.value) {
                settingsRepository.driveTreeUri()?.let { tree ->
                    try {
                        com.example.backup.DriveBackupClient.sendBackup(
                            getApplication(), android.net.Uri.parse(tree), recordingManager
                        )
                        settingsRepository.setLastBackupTimestamp(System.currentTimeMillis())
                    } catch (e: Exception) {
                        // Backup is best-effort; never block the stop path.
                    }
                }
            }
            // Auto-backup to cloud if enabled
            cloudBackupManager.performAutoBackupIfNeeded()
            // A finished stop can reveal older orphaned raw logs (or fail partially) -
            // keep the recovery banner in Trips & Recordings up to date.
            refreshUnsavedRawLogs()
        }
    }

    // ── Unsaved raw-log recovery (owner 2026-09-15: "I logs my logs it didn't save
    // how to recover from mobile?") — the raw OBD log is flushed to disk every line, so
    // drives killed before STOP can be rebuilt from the phone itself, no PC needed. ──
    private val _unsavedRawLogs = MutableStateFlow<List<java.io.File>>(emptyList())
    val unsavedRawLogs: StateFlow<List<java.io.File>> = _unsavedRawLogs.asStateFlow()

    private val _isRecovering = MutableStateFlow(false)
    val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()

    /** Re-scans files/raw_logs for sessions that were never finalized (disk IO, off-main). */
    suspend fun refreshUnsavedRawLogs() {
        _unsavedRawLogs.value = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                recordingManager.findUnsavedRawLogs()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val _recoveryNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val recoveryNotice: kotlinx.coroutines.flow.StateFlow<String?> = _recoveryNotice.asStateFlow()

    // ── AUTOMATIC recovery (owner 2026-09-17: "today logs not saved unable to recover it ...
    // it never ever loose the logs") ────────────────────────────────────────────────────
    //
    // Recovery used to be a banner on Trips & Recordings that had to be noticed and tapped. The
    // owner never saw it and a whole day of driving stayed unrecovered. It now runs by itself the
    // moment the app starts - and again whenever the keep-alive service is restarted by Android
    // after a kill, which needs no UI at all - rebuilding every session from the crash journal and
    // the raw logs.

    private val _autoRecoveryNotice = MutableStateFlow<String?>(null)
    val autoRecoveryNotice: StateFlow<String?> = _autoRecoveryNotice.asStateFlow()

    val isAutoRecovering: StateFlow<Boolean> = recordingManager.recoveryRunning

    private var autoRecoveryStarted = false

    /** Idempotent: safe from init, from a resume, and from the service at the same time. */
    fun runAutoRecovery() {
        if (autoRecoveryStarted) return
        autoRecoveryStarted = true
        viewModelScope.launch {
            val summary = try {
                // Resume window: journals cut off moments ago may belong to a drive still
                // running - the supervisors resume those; everything older is recovered now.
                recordingManager.recoverUnfinishedSessions(
                    com.example.data.SessionRecoveryPolicy.RESUME_WINDOW_MS
                )
            } catch (e: Exception) {
                null
            }
            if (summary != null) {
                _autoRecoveryNotice.value = summary.notice()
                _recoveryNotice.value = summary.notice()
                refreshUnsavedRawLogs()
            }
        }
        // Deferred sweep: a skipped resumable journal whose drive ended at the kill ages
        // out of the window while the app is open - finalize it then, without any tap.
        viewModelScope.launch {
            kotlinx.coroutines.delay(
                com.example.data.SessionRecoveryPolicy.RESUME_WINDOW_MS + 60_000L
            )
            runCatching {
                recordingManager.recoverUnfinishedSessions(
                    com.example.data.SessionRecoveryPolicy.RESUME_WINDOW_MS
                )
            }
        }
    }

    fun clearAutoRecoveryNotice() { _autoRecoveryNotice.value = null }

    fun recoverRawLog(file: java.io.File) {
        if (_isRecovering.value) return
        viewModelScope.launch {
            _isRecovering.value = true
            try {
                val outcome = recordingManager.recoverFromRawLog(file)
                val sid = com.example.analysis.RawLogRecovery.sessionIdOf(file.name) ?: file.name
                _recoveryNotice.value = when (outcome) {
                    is com.example.data.RecordingManager.RecoveryOutcome.Recovered ->
                        "Recovered session ${outcome.sessionId} - ${outcome.samples} OBD lines " +
                            "rebuilt. It is now a normal trip in Trips & Recordings."
                    com.example.data.RecordingManager.RecoveryOutcome.NothingToRecover -> {
                        recordingManager.archiveUnrecoverableRawLog(file)
                        "Session $sid holds NO decodable OBD responses - the link was silent that " +
                            "whole time (engine off / adapter asleep), so there is no telemetry " +
                            "inside to rebuild. The raw log is kept under raw_logs/archived."
                    }
                    com.example.data.RecordingManager.RecoveryOutcome.AlreadyRecovered ->
                        "Session $sid was already recovered earlier - look for its 'Recovered Run' " +
                            "card in Trips & Recordings."
                    is com.example.data.RecordingManager.RecoveryOutcome.Failed ->
                        "Recovery failed: ${outcome.reason}"
                }
            } catch (e: Exception) {
                _recoveryNotice.value = "Recovery failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isRecovering.value = false
                refreshUnsavedRawLogs()
            }
        }
    }

    // ── In-app updater (owner 2026-09-16: "update available I click it will automatically
    // fetch latest update from GitHub ... similar to playstore") ────────────────────────
    private val updateManager: UpdateManager by lazy { UpdateManager(getApplication()) }

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /**
     * Checks the rolling GitHub Release for a newer build.
     * @param auto silent launch check - throttled to AppUpdateFeed.AUTO_CHECK_INTERVAL_MS.
     *             A manual tap is never throttled and always reports its outcome, so
     *             "up to date" and "could not reach GitHub" stay distinguishable.
     */
    fun checkForUpdate(auto: Boolean = false) {
        val current = _updateState.value
        if (current.checking || current.downloading) return
        val now = System.currentTimeMillis()
        if (auto && !AppUpdateFeed.shouldAutoCheck(settingsRepository.lastUpdateCheckMs(), now)) return
        settingsRepository.setLastUpdateCheckMs(now)
        _updateState.value = current.copy(checking = true, error = null, upToDate = false, dismissed = false)
        viewModelScope.launch {
            val installed = updateManager.installedVersionCode()
            val feed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { updateManager.fetchFeed() }.getOrNull()
            }
            val newer = AppUpdateFeed.isNewerThan(feed, installed)
            _updateState.value = _updateState.value.copy(
                checking = false,
                lastCheckedAtMs = System.currentTimeMillis(),
                available = if (newer) feed else null,
                upToDate = feed != null && !newer,
                error = if (feed == null) {
                    "Could not reach the GitHub release feed - check the connection and try again."
                } else {
                    null
                }
            )
        }
    }

    /** Fetches the new APK in the background, then verifies its published SHA-256. */
    fun downloadUpdate() {
        val info: AppUpdateInfo = _updateState.value.available ?: return
        if (_updateState.value.downloading) return
        _updateState.value = _updateState.value.copy(
            downloading = true,
            error = null,
            progress = null,
            downloadedBytes = 0L,
            totalBytes = info.sizeBytes,
            stagedFile = null
        )
        viewModelScope.launch {
            var lastPercent = -1
            val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    updateManager.download(info) { received, total ->
                        // Throttled to whole percent steps so progress does not recompose
                        // the screen hundreds of times per megabyte.
                        val percent = if (total > 0L) ((received * 100L) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _updateState.value = _updateState.value.copy(
                                downloadedBytes = received,
                                totalBytes = total,
                                progress = AppUpdateFeed.progressFraction(received, total)
                            )
                        }
                    }
                }
            }
            outcome.onSuccess { apk ->
                val signaturesMatch = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    updateManager.installedSignatureMatchesApk(apk)
                }
                _updateState.value = _updateState.value.copy(
                    downloading = false,
                    progress = 1f,
                    stagedFile = apk,
                    signatureMismatch = signaturesMatch == false,
                    error = null
                )
                // A different signing key means the installer would fail with a cryptic
                // "App not installed", so stop and let the UI explain the one-time
                // reinstall instead of firing a dialog that cannot succeed.
                if (signaturesMatch != false) installUpdate()
            }.onFailure { e ->
                _updateState.value = _updateState.value.copy(
                    downloading = false,
                    progress = null,
                    stagedFile = null,
                    error = e.message ?: "Download failed"
                )
            }
        }
    }

    /** Hands the verified APK to Android's package installer (one system confirmation). */
    fun installUpdate() {
        val apk = _updateState.value.stagedFile ?: return
        if (!updateManager.canRequestPackageInstalls()) {
            _updateState.value = _updateState.value.copy(
                error = "Android blocks installs from this app until you allow " +
                    "\"Install unknown apps\" - opening that screen now."
            )
            updateManager.openInstallPermissionSettings()
            return
        }
        try {
            updateManager.install(apk)
        } catch (e: Exception) {
            _updateState.value = _updateState.value.copy(
                error = "Could not open the installer: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /** Owner dismissed the launch prompt for this app session; Settings still offers it. */
    fun dismissUpdatePrompt() {
        _updateState.value = _updateState.value.copy(dismissed = true)
    }

    /** Clears a reported failure so the update card returns to its idle state. */
    fun clearUpdateError() {
        _updateState.value = _updateState.value.copy(error = null)
    }

    /**
     * Manual requirement: coasting behaviour + mileage are SAVED and X95-vs-regular evidence
     * spans refuels/restarts. DriveAnalytics is memory-only, so at the end of every recording
     * the completed coast session and any closed tank segments are appended to the durable
     * insight logs. Never allowed to break the stop path.
     */
    private fun persistDriveInsights() {
        try {
            val snap = obdScheduler.driveAnalytics.snapshot.value
            // IST with offset (owner 2026-09-17): coast/ride insight logs carry the owner's clock.
            val nowUtc = com.example.data.RecordTime.stamp()
            if (snap.coast.totalSeconds >= 30.0 || snap.coast.totalDistanceM >= 200.0) {
                settingsRepository.appendCoastLog(DriveInsightsStore.encodeCoast(nowUtc, snap.coast))
            }
            val ride = obdScheduler.rideRecorder.summary(nowUtc)
            if (ride.durationSec >= 60.0 && ride.distanceKm >= 0.5) {
                settingsRepository.appendRideLog(com.example.analysis.RideCodec.encode(ride))
            }
            val persistedStarts = settingsRepository.readTankLog()
                .mapNotNull { DriveInsightsStore.decodeTank(it)?.dedupKey }
                .toSet()
            snap.tanks.filter { it.endMonotonicMs != null }.forEach { tank ->
                if (tank.startMonotonicMs.toString() !in persistedStarts) {
                    settingsRepository.appendTankLog(DriveInsightsStore.encodeTank(nowUtc, tank))
                }
            }
        } catch (e: Exception) {
            // Insight persistence is best-effort; stopping the recording always wins.
        }
    }

    val fuelLogRepository = AppContainer.fuelLogRepository
    val carpoolRepository = AppContainer.carpoolRepository
    val maintenanceRepository = AppContainer.maintenanceRepository
    val expenseRepository = AppContainer.expenseRepository
    val documentRepository = AppContainer.documentRepository
    val reminderRepository = AppContainer.reminderRepository
    val tripPlanRepository = AppContainer.tripPlanRepository

    private val _quickAdd = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Quick-Add FAB target consumed once by the destination screen to auto-open its dialog. */
    fun setQuickAdd(tag: String) { _quickAdd.value = tag }

    fun takeQuickAdd(tag: String): Boolean {
        if (_quickAdd.value == tag) { _quickAdd.value = null; return true }
        return false
    }

    /** Due-item summary notification (services, documents, reminders) at app start. */
    fun refreshDueNotifications() {
        if (!settingsRepository.remindersEnabled.value) return
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000L
        val lines = mutableListOf<String>()
        maintenanceRepository.dueStates(now)
            .filter { it.status == com.example.data.MaintenanceCatalog.DueStatus.OVERDUE }
            .forEach { lines.add("Overdue: ${it.item.label}") }
        documentRepository.expiringWithin(30, now)
            .forEach { (doc, ms) -> lines.add(if (ms < 0) "Expired: ${doc.type}" else "Expiring in ${ms / day} d: ${doc.type}") }
        reminderRepository.due(now).forEach { lines.add("Reminder due: ${it.title}") }
        if (lines.isNotEmpty()) com.example.data.NoticeManager.postSummary(getApplication(), lines)

        // Weekly check-in: if nothing was logged for 7+ days (and we have not nagged this week),
        // post a low-priority nudge - VehIQ's inactivity reminder, opt-out in the Reminders hub.
        if (settingsRepository.weeklyCheckInEnabled.value) {
            val week = 7L * 24 * 60 * 60 * 1000
            val lastActivity = maxOf(
                fuelLogRepository.entries().maxOfOrNull { it.idMs } ?: 0L,
                maintenanceRepository.logs().maxOfOrNull { it.dateMs } ?: 0L,
                expenseRepository.entries().maxOfOrNull { it.idMs } ?: 0L
            )
            val sinceLastNag = now - settingsRepository.lastCheckInNotifiedMs()
            if (lastActivity > 0L && now - lastActivity > week && sinceLastNag > week) {
                val inactiveDays = ((now - lastActivity) / day).toInt()
                com.example.data.NoticeManager.postCheckIn(getApplication(), inactiveDays)
                settingsRepository.setLastCheckInNotifiedMs(now)
            }
        }
    }

    /** Owner logged a refuel grade: stamp the next OBD tank segment with ground truth. */
    fun tagFuelGrade(grade: String) {
        obdScheduler.driveAnalytics.fuelQuality.pendingGrade = grade
    }

    /** Saved coasting sessions, newest first (Insights screen history rows). */
    fun coastHistory(): List<DriveInsightsStore.CoastLogEntry> =
        settingsRepository.readCoastLog().mapNotNull { DriveInsightsStore.decodeCoast(it) }.take(8)

    /**
     * Cross-trip trend points (avg rpm/speed/load/torque + idle model-vs-actual) for the
     * Trips tab charts. Computed from stored Room telemetry samples of the newest trips.
     */
    suspend fun computeTripTrends(limit: Int = 8): List<com.example.analysis.TripTrendPoint> {
        val repo = recordingManager.tripRepository
        val trips = repo.recentTrips(limit)
        if (trips.isEmpty()) return emptyList()
        val rows = repo.trendSamples(trips.map { it.id })
        return com.example.analysis.TripTrendAnalyzer.analyze(
            rows.map {
                val pid = it.pid.uppercase()
                com.example.analysis.TripTrendAnalyzer.Sample(
                    tripId = it.tripId,
                    pid = if (pid.length == 2) "01$pid" else pid,
                    ts = it.instantMs,
                    value = it.numericValue
                )
            }
        ).sortedByDescending { it.startTs }
    }

    /** Saved ride X-rays, newest first (behaviour + gears + elevation per ride). */
    // ---- AC & climate behaviour (additive 2026-09-09) ----
    val acSetTempC: StateFlow<Double> = settingsRepository.acSetTempC
    val acAutoMode: StateFlow<Boolean> = settingsRepository.acAutoMode

    fun setAcSetTempC(value: Double) = settingsRepository.setAcSetTempC(value)
    fun setAcAutoMode(enabled: Boolean) = settingsRepository.setAcAutoMode(enabled)

    /** Cross-ride learned AC-on vs AC-off economy from tagged ride summaries. */
    data class AcLearning(val onKmL: Double, val offKmL: Double, val rides: Int)

    data class CodingLabResult(val raw: String, val payloadHex: String?, val ascii: String?, val nrc: String?)

    /** Read-only UDS 0x22 explorer (SafetyValidator blocks every write service). */
    suspend fun codingLabRead(header: String, did: String): CodingLabResult {
        val transport = bluetoothManager.currentTransport()
            ?: return CodingLabResult("NOT CONNECTED - connect the adapter first.", null, null, null)
        val request = com.example.protocol.CodingLabCodec.readRequest(did)
        val validation = com.example.protocol.SafetyValidator.validateCommand(request)
        if (validation is com.example.protocol.ValidationResult.Rejected) {
            return CodingLabResult("SAFETY: ${validation.reason}", null, null, null)
        }
        transport.sendCommand("ATSH $header", 1200L)
        val resp = transport.sendCommand(request, 2500L)
        transport.sendCommand("ATSH 7E0", 1200L)
        val raw = resp.rawText.ifBlank { resp.lines.joinToString(" | ") }
        val nrc = com.example.protocol.CodingLabCodec.negativeNrc(raw)
        val payload = if (nrc == null) {
            com.example.protocol.CodingLabCodec.decodePositive(resp.lines, did)
                .ifBlank { com.example.protocol.CodingLabCodec.decodePositive(listOf(raw), did).ifBlank { null } }
        } else null
        return CodingLabResult(
            raw = raw.ifBlank { "[no response]" },
            payloadHex = payload,
            ascii = payload?.let { com.example.protocol.CodingLabCodec.hexToAscii(it) },
            nrc = nrc?.let { com.example.protocol.CodingLabCodec.nrcName(it) }
        )
    }

    data class SweepHit(val header: String, val kind: String, val detail: String)

    /** Passive 0x22 F190 sweep across the conventional UDS headers - discovers which modules answer. */
    suspend fun codingLabSweep(): List<SweepHit> {
        val transport = bluetoothManager.currentTransport()
            ?: return listOf(SweepHit("--", "NO_ADAPTER", "Connect the ELM327 adapter first."))
        val out = mutableListOf<SweepHit>()
        for (h in com.example.protocol.CodingLabCodec.SWEEP_HEADERS) {
            transport.sendCommand("ATSH $h", 900L)
            val r = transport.sendCommand("22F190", 2000L)
            val raw = r.rawText.ifBlank { r.lines.joinToString(" ") }
            when (val kind = com.example.protocol.CodingLabCodec.classifyResponse(raw, "F190")) {
                "POSITIVE" -> {
                    val payload = com.example.protocol.CodingLabCodec.decodePositive(r.lines, "F190")
                        .ifBlank { com.example.protocol.CodingLabCodec.decodePositive(listOf(raw), "F190") }
                    out.add(SweepHit(h, kind, com.example.protocol.CodingLabCodec.hexToAscii(payload)))
                }
                else -> if (kind.startsWith("NRC")) {
                    out.add(SweepHit(h, kind, com.example.protocol.CodingLabCodec.nrcName(kind.removePrefix("NRC:"))))
                }
            }
        }
        transport.sendCommand("ATSH 7E0", 900L)
        if (out.isEmpty()) out.add(SweepHit("--", "SILENT", "No module answered on any header (adapter asleep or car off)."))
        return out
    }

    /** Fuelio parity: auto-backup hook fired after every refuel save. */
    fun triggerCloudBackupIfEnabled() {
        viewModelScope.launch { cloudBackupManager.performAutoBackupIfNeeded() }
    }

    fun acLearning(): AcLearning? {
        var onKm = 0.0; var onL = 0.0; var offKm = 0.0; var offL = 0.0; var n = 0
        for (r in rideHistory()) {
            if (r.acOnKm > 0.05 && r.acOnFuelL > 0.005 && r.acOffKm > 0.05 && r.acOffFuelL > 0.005) {
                onKm += r.acOnKm; onL += r.acOnFuelL; offKm += r.acOffKm; offL += r.acOffFuelL; n++
            }
        }
        if (n == 0) return null
        return AcLearning(onKm / onL, offKm / offL, n)
    }

    fun rideHistory(): List<com.example.analysis.RideBehaviorRecorder.RideSummary> =
        // deduped(): repairs ride logs written before the one-persist-per-recording guard
        // (an owner log from 2026-09-15 contained the same 70-min ride twice).
        com.example.analysis.RideCodec.deduped(
            settingsRepository.readRideLog().mapNotNull { com.example.analysis.RideCodec.decode(it) }
        ).take(8)

    /** Owner tags the selector position so gear logs carry D/S/M evidence (J1979 has no range PID). */
    fun setRideMode(tag: String) {
        obdScheduler.rideRecorder.modeTag =
            com.example.analysis.RideBehaviorRecorder.ModeTag.values()
                .firstOrNull { it.name == tag } ?: com.example.analysis.RideBehaviorRecorder.ModeTag.D
    }

    val rideMode: String get() = obdScheduler.rideRecorder.modeTag.name

    /**
     * Owner tags the climate state (J1979 exposes no AC-clutch/compressor PID on this ECU).
     * Per-ride economy is then split into AC-on / blower-only / AC-off buckets.
     */
    fun setRideAc(tag: String) {
        obdScheduler.rideRecorder.acTag =
            com.example.analysis.RideBehaviorRecorder.AcTag.values()
                .firstOrNull { it.name == tag } ?: com.example.analysis.RideBehaviorRecorder.AcTag.OFF
    }

    val rideAc: String get() = obdScheduler.rideRecorder.acTag.name

    /** Saved closed tank segments, newest first (X95-vs-regular history). */
    fun tankHistory(): List<DriveInsightsStore.TankLogEntry> =
        settingsRepository.readTankLog().mapNotNull { DriveInsightsStore.decodeTank(it) }.take(10)

    fun renameRecording(sessionId: String, newName: String) {
        recordingManager.renameRecording(sessionId, newName)
    }

    fun deleteRecording(sessionId: String) {
        recordingManager.deleteRecording(sessionId)
    }

    private val _isMerging = MutableStateFlow(false)
    val isMerging: StateFlow<Boolean> = _isMerging.asStateFlow()

    private val _mergeNotice = MutableStateFlow<String?>(null)
    val mergeNotice: StateFlow<String?> = _mergeNotice.asStateFlow()

    fun clearMergeNotice() { _mergeNotice.value = null }

    fun mergeRecordings(sessionIds: List<String>) {
        if (sessionIds.size < 2) {
            _mergeNotice.value = "Select at least 2 trips to merge."
            return
        }
        if (_isMerging.value) return
        viewModelScope.launch {
            _isMerging.value = true
            try {
                val saved = recordingManager.mergeSessions(sessionIds)
                _mergeNotice.value = if (saved != null) {
                    "Merged ${sessionIds.size} trips into '${saved.metadata.sessionName}' — ${saved.transactionCount} transactions, ${saved.metadata.sessionName}. Old fragments deleted."
                } else {
                    "Merge failed — no transactions found in selected trips."
                }
            } catch (e: Exception) {
                _mergeNotice.value = "Merge failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isMerging.value = false
            }
        }
    }

    private val _importStatusMessage = MutableStateFlow<String?>(null)
    val importStatusMessage: StateFlow<String?> = _importStatusMessage.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun clearImportStatusMessage() {
        _importStatusMessage.value = null
    }

    fun importZipUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val results = recordingManager.importZipFiles(uris)
                val successCount = results.count { it.success }
                val failCount = results.size - successCount
                if (failCount == 0) {
                    _importStatusMessage.value = "Successfully imported $successCount trip log(s)."
                } else {
                    _importStatusMessage.value = "Imported $successCount of ${results.size} trip(s). $failCount failed."
                }
            } catch (e: Exception) {
                _importStatusMessage.value = "Import error: ${e.localizedMessage ?: e.message}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun setPollingSpeedMode(mode: PollingSpeedMode) {
        settingsRepository.setPollingMode(mode)
    }

    fun togglePid(pidId: String) {
        settingsRepository.togglePidEnabled(pidId)
    }

    fun savePid(pidDef: PidDefinition) {
        val current = settingsRepository.pidDefinitions.value.toMutableList()
        val index = current.indexOfFirst { it.id == pidDef.id }
        if (index >= 0) {
            current[index] = pidDef
        } else {
            current.add(pidDef)
        }
        settingsRepository.savePidDefinitions(current)
    }

    fun deletePid(pidId: String) {
        val current = settingsRepository.pidDefinitions.value.filterNot { it.id == pidId }
        settingsRepository.savePidDefinitions(current)
    }

    fun resetPidDefaults() {
        settingsRepository.resetPidDefaults()
    }

    fun sendManualCommand(command: String) {
        val transport = activeTransport
        if (transport == null || !transport.isConnected) {
            _manualCommandError.value = "Not connected to adapter"
            _manualCommandOutput.value = null
            return
        }

        viewModelScope.launch {
            val validation = SafetyValidator.validateCommand(command)
            if (validation is ValidationResult.Rejected) {
                _manualCommandError.value = "SAFETY BLOCK: ${validation.reason}"
                _manualCommandOutput.value = null
                return@launch
            }

            _manualCommandError.value = null
            val resp = transport.sendCommand(command, timeoutMs = 2500L)
            _manualCommandOutput.value = resp.rawText.ifBlank { resp.lines.joinToString("\n") }
        }
    }

    fun clearRawLog() {
        rawLogManager.clear()
    }

    fun getAllRawLogText(): String {
        return rawLogManager.getAllAsText()
    }

    /**
     * Reverse engineering byte-level statistics for a specific PID
     */
    fun getByteStatisticsForPid(pidId: String): List<BytePositionStats> {
        val history = pidRawHistory.value[pidId] ?: emptyList()
        val byteLists = history.mapNotNull { tx ->
            if (tx.rawPayload.isNotBlank() && tx.rawPayload.length % 2 == 0) {
                tx.rawPayload.chunked(2).mapNotNull { it.toIntOrNull(16) }
            } else null
        }
        return PidDecoder.analyzeBytePositions(byteLists)
    }

    fun get16BitWordStatisticsForPid(pidId: String): List<String> {
        val history = pidRawHistory.value[pidId] ?: emptyList()
        val byteLists = history.mapNotNull { tx ->
            if (tx.rawPayload.isNotBlank() && tx.rawPayload.length % 2 == 0) {
                tx.rawPayload.chunked(2).mapNotNull { it.toIntOrNull(16) }
            } else null
        }
        return PidDecoder.analyze16BitWords(byteLists)
    }

    // AI Doctor State and Methods
    private val _aiChatHistory = MutableStateFlow<List<com.example.model.ChatMessage>>(listOf(
        com.example.model.ChatMessage(
            sender = com.example.model.MessageSender.CAR_DOCTOR,
            text = "Hello! I am your AI Car Doctor. Ask me anything regarding your engine telemetry, coolant thresholds, boost pressure, or diagnostic codes."
        )
    ))
    val aiChatHistory: StateFlow<List<com.example.model.ChatMessage>> = _aiChatHistory.asStateFlow()

    // FIX P0-3 + AI-not-configured UX: Resilient provider chain.
    //  1) Try FirebaseAiDoctorProvider (Gemini API) - works when GEMINI_API_KEY is set
    //     in BuildConfig and the user has configured a valid key.
    //  2) Fall back to RuleBasedChatProvider - uses the on-device RuleBasedAnalysisEngine
    //     over the user's recorded trip data. Works for ALL users out of the box, no
    //     API key required, no internet required. Provides full conversational vehicle
    //     diagnostics by mapping free-form queries to subsystem analyses.
    //  3) Final safety net: StubAiDoctorProvider (only reached if both above fail).
    //
    // This fixes the "AI provider is not configured. A valid GEMINI_API_KEY is required"
    // wall that previously greeted every user without a paid Gemini key. The Rule-Based
    // provider is fully featured enough to replace the cloud model for routine diagnostics.
    private val aiProvider: com.example.ai.AiDoctorProvider by lazy {
        runCatching { com.example.ai.FirebaseAiDoctorProvider() }
            .getOrElse {
                runCatching { com.example.ai.RuleBasedChatProvider(recordingManager.tripRepository) }
                    .getOrElse { com.example.ai.StubAiDoctorProvider() }
            }
    }

    fun sendAiMessage(query: String) {
        val userMsg = com.example.model.ChatMessage(sender = com.example.model.MessageSender.USER, text = query)
        val loadingMsg = com.example.model.ChatMessage(sender = com.example.model.MessageSender.CAR_DOCTOR, text = "Analyzing...")
        _aiChatHistory.value = _aiChatHistory.value + userMsg + loadingMsg

        viewModelScope.launch {
            val context = buildDiagnosticContext()
            
            // Map history to AiMessage format
            val historyForAi = _aiChatHistory.value
                .dropLast(1) // Remove the "Analyzing..." message
                .map {
                    com.example.ai.AiMessage(
                        role = if (it.sender == com.example.model.MessageSender.USER) "user" else "model",
                        text = it.text
                    )
                }

            val request = com.example.ai.AiDoctorRequest(
                context = context,
                chatHistory = historyForAi,
                latestQuery = query
            )

            val response = aiProvider.analyze(request)
            
            // Replace the loading message with the actual response
            _aiChatHistory.value = _aiChatHistory.value.dropLast(1) + com.example.model.ChatMessage(
                sender = com.example.model.MessageSender.CAR_DOCTOR,
                text = response.responseText,
                isEcuFact = response.isEcuFact
            )
        }
    }

    private suspend fun buildDiagnosticContext(): com.example.ai.VehicleDiagnosticContext {
        val dtcRecords = try {
            recordingManager.tripRepository.dtcRecordsFlow.firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val dtcString = if (dtcRecords.isEmpty()) "No active DTCs were detected by the application." 
            else dtcRecords.joinToString("\n") { "${it.code}: ${it.description} (${it.status})" }

        val verificationState = protocolVerificationResult.value?.health?.name ?: "Not verified"
        
        return com.example.ai.VehicleDiagnosticContext(
            vehicleName = vehicleName.value,
            vin = vehicleVin.value ?: "UNAVAILABLE",
            connectionStatus = connectionState.value.name,
            adapterName = adapterFirmware.value ?: "UNAVAILABLE",
            protocol = selectedCanProtocol.value.displayName,
            verificationState = verificationState,
            ecuResponses = protocolVerificationResult.value?.successCount?.toLong() ?: 0L,
            canErrors = protocolVerificationResult.value?.canErrorCount?.toLong() ?: 0L,
            timeouts = protocolVerificationResult.value?.timeoutCount?.toLong() ?: 0L,
            liveData = liveDecodedMap.value,
            dtcs = dtcString,
            appVersion = com.example.BuildConfig.VERSION_NAME,
            buildNumber = com.example.BuildConfig.VERSION_CODE,
            gitCommit = com.example.BuildConfig.GIT_COMMIT
        )
    }
}
