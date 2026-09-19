package com.example.data

import android.content.Context
import com.example.data.db.TripRepository
import com.example.data.db.entities.RawLogEntity
import com.example.data.db.entities.TelemetrySampleEntity
import com.example.data.db.entities.TripEntity
import com.example.model.Direction
import com.example.model.RecordingMetadata
import com.example.model.ResponseStatus
import com.example.model.SynchronizedSample
import com.example.model.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedRecording(
    val metadata: RecordingMetadata,
    val transactionCount: Int,
    val transactionCsvFile: File,
    val samplesCsvFile: File,
    val jsonFile: File,
    val rawLogFile: File?,
    val zipFile: File? = null
)

/**
 * Manages active recording lifecycle and saved sessions storage, backed by Room database and file system
 */
class RecordingManager(
    private val context: Context,
    private val rawLogManager: RawLogManager,
    val tripRepository: TripRepository = TripRepository(context)
) {

    val recordingsDir: File = File(context.filesDir, "recordings").apply {
        if (!exists()) mkdirs()
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentSessionMetadata = MutableStateFlow<RecordingMetadata?>(null)
    val currentSessionMetadata: StateFlow<RecordingMetadata?> = _currentSessionMetadata.asStateFlow()

    private val _currentTransactions = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val currentTransactions: StateFlow<List<TransactionRecord>> = _currentTransactions.asStateFlow()

    private val _savedRecordings = MutableStateFlow<List<SavedRecording>>(emptyList())

    // OWNER BUG 2026-09-16: a recording ran 99 minutes against a SILENT link (ignition
    // off / adapter hung) and then died as another "unsaved session" corpse. Watchdog:
    // while recording, if no ECU line arrives for SILENT_LIMIT_MS, auto-stop AND SAVE,
    // so a dead link can never again eat a trip or burn an hour of nothing.
    @Volatile private var lastRxAtMs: Long = 0L
    private var watchdogJob: kotlinx.coroutines.Job? = null
    private val _autoStopNotice = MutableStateFlow<String?>(null)
    val autoStopNotice: StateFlow<String?> = _autoStopNotice.asStateFlow()
    val savedRecordings: StateFlow<List<SavedRecording>> = _savedRecordings.asStateFlow()

    private val activeTransactionList = mutableListOf<TransactionRecord>()
    private val activeSampleList = mutableListOf<SynchronizedSample>()
    private var currentSample = SynchronizedSample(
        timestampUtc = "",
        timestampMonotonic = 0L
    )

    private var sessionStartTimestamp = 0L

    /**
     * Manager-owned scope for the watchdog, the initial Room insert and background recovery.
     * It used to be a fresh `CoroutineScope(Dispatchers.IO)` per launch - unmanaged, uncancelled,
     * and invisible to tests. One scope means one place to cancel and one place to look.
     */
    private val managerScope =
        CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * The crash-proof journal (owner 2026-09-17: "it never ever loose the logs").
     * Every OBD transaction is appended here AND FLUSHED before `recordTransaction` returns, so a
     * process kill costs at most the row in flight instead of the whole drive.
     */
    val journal: SessionJournal = SessionJournal(File(recordingsDir, "journal"))

    private val _journalRecovery = MutableStateFlow<RecoverySummary?>(null)

    /** Result of the automatic recovery that runs on app start. Null = nothing was pending. */
    val journalRecovery: StateFlow<RecoverySummary?> = _journalRecovery.asStateFlow()

    private val _recoveryRunning = MutableStateFlow(false)
    val recoveryRunning: StateFlow<Boolean> = _recoveryRunning.asStateFlow()

    init {
        loadSavedRecordings()
    }

    companion object {
        const val SILENT_LIMIT_MS: Long = 5 * 60_000L

        /** Pure so the watchdog rule is testable: silent only counts once a first RX existed. */
        fun shouldAutoStop(lastRxMs: Long, nowMs: Long, silentLimitMs: Long = SILENT_LIMIT_MS): Boolean =
            lastRxMs > 0L && nowMs - lastRxMs > silentLimitMs

        /**
         * The wide-row merge: one OBD answer folded into the current synchronized sample, taking
         * that line's own stamp.
         *
         * Pure and public for two reasons. It is the single definition of how a sample row is
         * built, and [recoverJournalSession] has to REPLAY it over the journaled transactions
         * when a killed session left no usable samples file - so a recovered trip gets exactly
         * the rows the live recorder would have written, not an approximation of them.
         */
        fun mergeSample(current: SynchronizedSample, tx: TransactionRecord): SynchronizedSample {
            val base = current.copy(
                timestampUtc = tx.timestampUtc,
                timestampMonotonic = tx.timestampMonotonic
            )
            return when (tx.pid.uppercase()) {
                "0C" -> base.copy(rpm = tx.decodedValue)
                "0D" -> base.copy(speedKmh = tx.decodedValue)
                "04" -> base.copy(engineLoadPct = tx.decodedValue)
                "0B" -> base.copy(mapKpa = tx.decodedValue)
                "11" -> base.copy(throttlePct = tx.decodedValue)
                "49" -> base.copy(acceleratorPct = tx.decodedValue)
                "05" -> base.copy(coolantC = tx.decodedValue)
                "0F" -> base.copy(iatC = tx.decodedValue)
                "46" -> base.copy(ambientC = tx.decodedValue)
                "9D" -> base.copy(fuelRateLh = tx.decodedValue)
                "62" -> base.copy(engineTorquePct = tx.decodedValue)
                "42" -> base.copy(voltageV = tx.decodedValue)
                "6D" -> base.copy(fuelPressureRaw = tx.rawPayload)
                "70" -> base.copy(boostPressureRaw = tx.rawPayload)
                else -> base
            }
        }

        /** Which of a sample's channels PID [pid] fills. Pure; mirrors [mergeSample]. */
        fun sampleFieldForPid(pid: String): String? = when (pid.uppercase()) {
            "0C" -> "rpm"
            "0D" -> "speedKmh"
            "04" -> "engineLoadPct"
            "0B" -> "mapKpa"
            "11" -> "throttlePct"
            "49" -> "acceleratorPct"
            "05" -> "coolantC"
            "0F" -> "iatC"
            "46" -> "ambientC"
            "9D" -> "fuelRateLh"
            "62" -> "engineTorquePct"
            "42" -> "voltageV"
            "6D" -> "fuelPressureRaw"
            "70" -> "boostPressureRaw"
            else -> null
        }
    }

    fun startRecording(
        vehicleName: String = "Škoda Kylaq 1.0 TSI (EA211)",
        vehicleId: String? = null,  // CRITICAL FIX: VehicleId for proper DTC/trip association
        profileName: String = "India-Market 1.0 TSI",
        adapterName: String = "ELM327 v1.5 Bluetooth Classic",
        protocolName: String = "ISO 15765-4 CAN 11-bit 500kbps"
    ): RecordingMetadata? {
        // Two supervisors run the auto-record rule now - MainViewModel while the UI is open and
        // ObdKeepAliveService for as long as the process lives - so both can see "engine on, nothing
        // recording" on the same tick. Without this guard the second START would orphan-save the
        // first session and open a new one, shredding one drive into two trips. The session that is
        // already open is the truth; the caller is told there was nothing to do.
        _currentSessionMetadata.value?.let { live ->
            if (_isRecording.value) return live
        }

        // DATA-LOSS FIX 1 (owner 2026-09-17). This method used to call clear() on both RAM lists
        // unconditionally, so a second START - auto-reconnect, screen re-entry, a retry after a
        // dropped link - threw the whole previous session away without writing a byte of it. Any
        // session still in RAM is now persisted BEFORE the lists are cleared, and the snapshot is
        // taken under the same lock so the two cannot interleave.
        val orphanMeta = _currentSessionMetadata.value
        val orphanTx: List<TransactionRecord>
        val orphanSamples: List<SynchronizedSample>
        synchronized(activeTransactionList) {
            orphanTx = activeTransactionList.toList()
            orphanSamples = activeSampleList.toList()
            activeTransactionList.clear()
            activeSampleList.clear()
            currentSample = SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L)
        }
        if (orphanMeta != null && orphanTx.isNotEmpty()) {
            // Defence in depth. The guard above means a live session never reaches this point, so
            // this fires only when the two state flows have diverged - a stopRecording() abandoned
            // part-way leaves _isRecording false with the metadata and the RAM lists still set. The
            // snapshot was taken under the same lock that clears the lists, so it cannot race the
            // new session, and finalizeSession is suspend (Room) while startRecording is called from
            // the main thread, so it runs on the manager scope.
            managerScope.launch {
                runCatching { finalizeSession(orphanMeta, orphanTx, orphanSamples, null, recovered = false) }
                    .onFailure { android.util.Log.e("RecordingManager", "orphan session save failed", it) }
            }
        }

        val sessionId = UUID.randomUUID().toString().take(8)
        sessionStartTimestamp = System.currentTimeMillis()
        // IST with its offset - owner mandate 2026-09-17: "For all records use IST time only no
        // UTC." The field is still called startTimeUtc because trip JSON, Room and every existing
        // backup use that key; the VALUE is now local time that says which zone it is in.
        val startStamp = RecordTime.stamp(sessionStartTimestamp)

        val defaultName = "Kylaq Run ${RecordTime.display(sessionStartTimestamp)}"

        val metadata = RecordingMetadata(
            sessionId = sessionId,
            sessionName = defaultName,
            vehicle = vehicleName,
            vehicleId = vehicleId,
            profile = profileName,
            adapter = adapterName,
            protocol = protocolName,
            canBitrate = "500 kbps",
            startTimeUtc = startStamp
        )

        _currentSessionMetadata.value = metadata
        _currentTransactions.value = emptyList()
        _isRecording.value = true
        _autoStopNotice.value = null
        lastRxAtMs = System.currentTimeMillis()
        watchdogJob?.cancel()
        watchdogJob = managerScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(20_000L)
                if (_isRecording.value && shouldAutoStop(lastRxAtMs, System.currentTimeMillis())) {
                    _autoStopNotice.value =
                        "Link silent for 5 min (no ECU responses) - recording auto-stopped and " +
                        "saved, so a dead link can never leave an unsaved corpse session."
                    runCatching { stopRecording() }
                    break
                }
            }
        }

        rawLogManager.startFileLogging(sessionId)

        // DATA-LOSS FIX 2. Open the journal BEFORE the first OBD line can arrive, so the very
        // first transaction is already on disk. If the journal cannot be opened the recording
        // still runs - but the owner is told that this drive is not crash-protected, instead of
        // finding out after the kill.
        if (!journal.open(metadata)) {
            _autoStopNotice.value =
                "WARNING: could not open the crash journal (${journal.lastFailureReason}). This " +
                "recording is not kill-protected - check free storage."
        }

        // Asynchronously insert initial Trip record in Room
        managerScope.launch {
            // QA H2: uncaught Room exception here would crash the process mid-recording.
            runCatching {
                tripRepository.insertTrip(
                    TripEntity(
                        id = sessionId,
                        title = defaultName,
                        vehicleName = vehicleName,
                        adapterName = adapterName,
                        protocolName = protocolName,
                        startTimeUtc = startStamp,
                        startTimestamp = sessionStartTimestamp,
                        status = "RECORDING"
                    )
                )
            }.onFailure { android.util.Log.e("RecordingManager", "initial trip insert failed", it) }
        }

        return metadata
    }

    fun recordTransaction(tx: TransactionRecord) {
        if (!_isRecording.value) return

        val journaled: TransactionRecord
        val row: SynchronizedSample
        synchronized(activeTransactionList) {
            // GPS altitude per OBD line (owner 2026-09-16: "why altitude is missing in
            // trend and trip logs?"): stamp at intake so EVERY persisted sample row
            // carries the elevation of its own moment; null when no accuracy-gated
            // fix exists at that instant - gaps stay gaps.
            val stamped = tx.copy(altitudeM = com.example.di.AppContainer.currentAltitudeM())
            lastRxAtMs = System.currentTimeMillis()
            activeTransactionList.add(stamped)
            _currentTransactions.value = activeTransactionList.toList()

            // Wide-row merge, extracted to the pure companion RecordingManager.mergeSample so
            // journal recovery replays the identical rows.
            val updated = mergeSample(currentSample, stamped)
            // Per-sample GPS altitude for the trip log (owner 2026-09-15). Stamped on every
            // row so the samples CSV carries the elevation profile; null when no accuracy-gated
            // fix with altitude exists at that moment - never the 0.0 default.
            val withAltitude = updated.copy(
                altitudeM = com.example.di.AppContainer.currentAltitudeM()
            )
            currentSample = withAltitude
            activeSampleList.add(withAltitude)
            journaled = stamped
            row = withAltitude
        }

        // DATA-LOSS FIX 3 - the whole point of this change. The transaction is appended to the
        // on-disk journal and FLUSHED before this call returns, so a process kill after this line
        // costs at most one row instead of the entire drive. Outside the list lock: file I/O must
        // never hold the lock the UI reads through.
        _currentSessionMetadata.value?.let { meta ->
            journal.appendTransaction(meta, journaled)
            journal.appendSample(row)
        }
    }

    suspend fun stopRecording(): SavedRecording? = withContext(Dispatchers.IO) {
        watchdogJob?.cancel()
        val metadata = _currentSessionMetadata.value ?: return@withContext null
        val endTimestamp = System.currentTimeMillis()
        val endStamp = RecordTime.stamp(endTimestamp)
        metadata.endTimeUtc = endStamp

        val txList: List<TransactionRecord>
        val sampleList: List<SynchronizedSample>
        synchronized(activeTransactionList) {
            txList = activeTransactionList.toList()
            sampleList = activeSampleList.toList()
        }

        val rawLogFile = rawLogManager.stopFileLogging()

        // DATA-LOSS FIX 4. A clean STOP closes the journal with its `.finished` marker, which is
        // what tells the next app start "this one is done, do not rebuild it".
        //
        // A journal that is LARGER than RAM is used instead of RAM. That happens when the process
        // was restarted mid-drive (auto-reconnect after a kill): the old rows are on disk, the new
        // process only holds the rows it saw itself. Taking the longer source keeps the drive whole
        // rather than silently truncating it to whatever survived in memory.
        val journalTx = if (journal.txFile(metadata.sessionId).exists()) {
            runCatching { CsvExporter.readTransactionsFromCsv(journal.txFile(metadata.sessionId)) }
                .getOrDefault(emptyList())
        } else emptyList()
        val journalSamples = if (journal.sampleFile(metadata.sessionId).exists()) {
            runCatching { CsvExporter.readSamplesFromCsv(journal.sampleFile(metadata.sessionId)) }
                .getOrDefault(emptyList())
        } else emptyList()
        val effectiveTx = SessionRecoveryPolicy.preferLonger(txList, journalTx)
        val effectiveSamples = SessionRecoveryPolicy.preferLonger(sampleList, journalSamples)
        journal.markFinished(metadata.sessionId, endTimestamp)
        journal.close()

        val journalFailures = journal.writeFailures
        if (journalFailures > 0) {
            _autoStopNotice.value =
                "Saved, but $journalFailures journal write(s) failed (${journal.lastFailureReason}). " +
                "The crash journal was incomplete for part of this drive - check free storage."
        }

        _isRecording.value = false
        _currentSessionMetadata.value = null

        val saved = finalizeSession(
            metadata = metadata,
            txList = effectiveTx,
            sampleList = effectiveSamples,
            rawLogFile = rawLogFile,
            recovered = false
        )
        loadSavedRecordings()
        saved
    }

    // ── Session finalization (shared by STOP, orphan save and crash recovery) ──────────
    //
    // One code path writes a trip: the two CSVs, the session JSON, the ZIP bundle, the Room trip
    // row, the Room telemetry rows and the AI analysis. STOP used to own it, raw-log recovery had
    // a near-copy of it, and the two had already drifted (recovery wrote an empty samples CSV and
    // skipped the altitude/voltage extremes). Three callers, one implementation.

    /**
     * Writes a session's files and Room rows.
     *
     * @param recovered true when the session was rebuilt after the process died, so the trip says
     *                  so in its adapter field instead of pretending it ended with a clean STOP.
     * @return the saved recording, or null when [txList] holds nothing worth saving.
     */
    internal suspend fun finalizeSession(
        metadata: RecordingMetadata,
        txList: List<TransactionRecord>,
        sampleList: List<SynchronizedSample>,
        rawLogFile: File?,
        recovered: Boolean
    ): SavedRecording? {
        if (txList.isEmpty()) return null
        val sessionId = metadata.sessionId

        // The trip window is the DATA window: first row to last row. Deriving it here rather than
        // passing it in means every caller - STOP, an orphaned restart, journal recovery, raw-log
        // recovery - reports the same thing, and no caller can hand over a window that disagrees
        // with the rows it just handed over. Every row carries its own epoch, so this is exact.
        val startTimestamp = txList.minOf { it.timestampMonotonic }
        val endTimestamp = txList.maxOf { it.timestampMonotonic }
        val endStamp = RecordTime.stamp(endTimestamp)

        // GPS altitude window for this trip (owner 2026-09-15 fix: altitude was captured live but
        // never persisted - the trip summary showed an honest "-- m" blank). Null only when the
        // recording never had an accuracy-gated GPS fix with altitude; never 0.0, never invented.
        val altStats = com.example.di.AppContainer.tripAltitudeStats()
        // Battery voltage extremes (owner pipeline task 3, 2026-09-16), reduced from the real 0142
        // samples exactly like the altitude window.
        val voltStats = com.example.analysis.VoltageStats.extremes(
            sampleList.mapNotNull { smp -> smp.voltageV?.let { smp.timestampMonotonic to it } }
        )
        // The trip log files must carry the same altitude window as the database row, or a
        // backup -> reinstall -> import round trip silently strips elevation from every past trip.
        val metadataForFiles = metadata.copy(
            maxAltitudeM = altStats?.maxAltitudeM,
            minAltitudeM = altStats?.minAltitudeM,
            minVoltageV = voltStats?.minV,
            maxVoltageV = voltStats?.maxV,
            adapter = if (recovered) metadata.adapter + " (recovered after the app was killed)" else metadata.adapter
        ).apply { endTimeUtc = endStamp }

        val sessionDir = File(recordingsDir, "session_$sessionId").apply { mkdirs() }
        val txCsvFile = File(sessionDir, "${sessionId}_transactions.csv")
        val sampleCsvFile = File(sessionDir, "${sessionId}_samples.csv")
        val jsonFile = File(sessionDir, "$sessionId.json")

        CsvExporter.exportTransactionsToCsv(txCsvFile, metadataForFiles, txList)
        CsvExporter.exportSynchronizedSamplesToCsv(sampleCsvFile, sampleList)
        JsonExporter.exportToJson(jsonFile, metadataForFiles, txList)

        // Copy the raw log into the session folder so the bundle is self-contained. The original
        // stays in files/raw_logs: findUnsavedRawLogs() filters by saved session id, so it will no
        // longer be offered for recovery, and keeping it costs nothing.
        val destRawLog = if (rawLogFile != null && rawLogFile.exists()) {
            runCatching {
                val dest = File(sessionDir, "${sessionId}_raw.txt")
                rawLogFile.copyTo(dest, overwrite = true)
                dest
            }.getOrNull()
        } else null

        val zipFile = File(sessionDir, "${sessionId}_bundle.zip")
        runCatching {
            ZipExporter.createTripZip(zipFile, listOfNotNull(txCsvFile, sampleCsvFile, jsonFile, destRawLog))
        }.onFailure { android.util.Log.e("RecordingManager", "zip bundle failed", it) }

        persistTripToRoom(
            sessionId = sessionId,
            metadata = metadataForFiles,
            txList = txList,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            endStamp = endStamp,
            altStats = altStats,
            voltStats = voltStats
        )

        return SavedRecording(
            metadata = metadataForFiles,
            transactionCount = txList.size,
            transactionCsvFile = txCsvFile,
            samplesCsvFile = sampleCsvFile,
            jsonFile = jsonFile,
            rawLogFile = destRawLog,
            zipFile = if (zipFile.exists()) zipFile else null
        )
    }

    /** Room reductions + rows. Shared so a recovered trip and a stopped trip are stored alike.
     *  Suspend because the Room DAOs are: a non-suspend wrapper would have to block a thread or
     *  fire-and-forget, and fire-and-forget is how a trip row goes missing while its files exist. */
    private suspend fun persistTripToRoom(
        sessionId: String,
        metadata: RecordingMetadata,
        txList: List<TransactionRecord>,
        startTimestamp: Long,
        endTimestamp: Long,
        endStamp: String,
        altStats: com.example.analysis.AltitudeStats?,
        voltStats: com.example.analysis.VoltageExtremes?
    ) {
        val maxRpm = txList.filter { it.pid.equals("0C", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
        val maxSpeed = txList.filter { it.pid.equals("0D", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
        val maxCoolant = txList.filter { it.pid.equals("05", ignoreCase = true) }.mapNotNull { it.decodedValue }.maxOrNull() ?: 0.0
        val voltList = txList.filter { it.pid.equals("42", ignoreCase = true) }.mapNotNull { it.decodedValue }
        val avgVolt = if (voltList.isNotEmpty()) voltList.average() else 0.0
        val detectedEcus = txList.mapNotNull { it.canRxId.takeIf { id -> id.isNotBlank() } }.distinct()
            .joinToString(", ").ifBlank { "7E8" }
        val durationSec = maxOf(1L, (endTimestamp - startTimestamp) / 1000)

        runCatching {
            tripRepository.insertTrip(
                TripEntity(
                    id = sessionId,
                    title = metadata.sessionName,
                    vehicleName = metadata.vehicle,
                    adapterName = metadata.adapter,
                    protocolName = metadata.protocol,
                    startTimeUtc = metadata.startTimeUtc,
                    endTimeUtc = endStamp,
                    startTimestamp = startTimestamp,
                    endTimestamp = endTimestamp,
                    durationSeconds = durationSec,
                    status = "COMPLETED",
                    sampleCount = txList.size,
                    rawLogCount = txList.size,
                    maxRpm = maxRpm,
                    maxSpeedKmh = maxSpeed,
                    maxCoolantC = maxCoolant,
                    avgVoltageV = avgVolt,
                    detectedEcus = detectedEcus,
                    healthScore = 100,
                    maxAltitudeM = altStats?.maxAltitudeM,
                    minAltitudeM = altStats?.minAltitudeM,
                    minVoltageV = voltStats?.minV,
                    maxVoltageV = voltStats?.maxV
                )
            )
        }.onFailure { android.util.Log.e("RecordingManager", "trip insert failed for $sessionId", it) }

        runCatching {
            tripRepository.insertSamples(
                txList.mapIndexed { idx, tx ->
                    TelemetrySampleEntity(
                        tripId = sessionId,
                        // Was `tx.timestampMonotonic`, which the live transport fills with
                        // SystemClock.elapsedRealtime() - uptime, not an instant. This indexed
                        // column is the trend chart's X axis and the fuel integrator's timeline, so
                        // storing uptime made every axis label a 1970 clock time and made
                        // cross-trip trends compare one phone's uptime against another's. The stamp
                        // beside it states the real instant; store that. instantOf falls back to the
                        // monotonic value only if the stamp cannot be read at all, which keeps the
                        // column monotonic for ordering either way.
                        timestamp = com.example.data.RecordTime.instantOf(
                            tx.timestampMonotonic, tx.timestampUtc
                        ) ?: tx.timestampMonotonic,
                        timestampUtc = tx.timestampUtc,
                        ecuCanId = tx.canRxId.ifBlank { "7E8" },
                        pid = tx.pid,
                        parameterName = tx.decodedParameter.ifBlank { "PID ${tx.pid}" },
                        rawHex = tx.responseHex,
                        numericValue = tx.decodedValue,
                        displayValue = tx.decodedValueDisplay,
                        unit = tx.unit,
                        quality = "VALID",
                        altitudeM = tx.altitudeM,
                        sequence = idx.toLong()
                    )
                }
            )
        }.onFailure { android.util.Log.e("RecordingManager", "sample insert failed for $sessionId", it) }

        // Event-driven refuel detection (owner 2026-09-19): the tank level PID's rise across a
        // stationary window - or across the session gap, engine off at the pump - is the event.
        // Runs at finalize so recovered sessions detect their refuels through the same rows.
        runCatching { detectRefuelEvents(txList) }
            .onFailure { android.util.Log.e("RecordingManager", "refuel detection failed", it) }

        // Auto-run local AI Doctor analysis
        runCatching { tripRepository.runAiCarDoctorAnalysis(sessionId) }
            .onFailure { android.util.Log.e("RecordingManager", "AI doctor failed for $sessionId", it) }
    }

    /**
     * Feeds a finalized session's decoded rows through [com.example.analysis.RefuelEventDetector]
     * and persists what it emits. The previous session's last level stamp supplies the gap event:
     * the owner's 09-17 fill rose 37.6 -> 93.7 % entirely between sessions, because auto-record
     * stops with the engine and the pump cut off at 12:05:06 with the key off.
     */
    private suspend fun detectRefuelEvents(txList: List<TransactionRecord>) {
        val settings = com.example.di.AppContainer.settingsRepository
        val rows = txList.map { tx ->
            com.example.analysis.SinceRefuelStats.Row(
                com.example.data.RecordTime.instantOf(tx.timestampMonotonic, tx.timestampUtc)
                    ?: tx.timestampMonotonic,
                tx.pid,
                tx.decodedValue
            )
        }
        persistRefuelEvents(com.example.analysis.RefuelSessionScan.scan(rows), settings)
    }

    /**
     * One-time backfill (owner 2026-09-19: "does the current logic detect the fuel refill
     * automatically?"): sessions finalized BEFORE the detector shipped were never scanned, so the
     * first launch on a build that has it replays every saved trip chronologically through the
     * same scanner - which is also how the 2026-09-17 fill (37.6 % at the end of one trip, 93.7 %
     * at the start of the next) becomes a visible event without re-driving anything. Idempotent:
     * event ids REPLACE, and the pref guard runs the pass exactly once.
     */
    suspend fun backfillRefuelEvents() = withContext(Dispatchers.IO) {
        val settings = com.example.di.AppContainer.settingsRepository
        if (settings.refuelBackfillDone()) return@withContext
        runCatching {
            for (trip in tripRepository.allTripsChronological()) {
                val rows = tripRepository.samplesForTripPids(
                    trip.id,
                    listOf(
                        com.example.analysis.RefuelEventDetector.PID_LEVEL,
                        com.example.analysis.RefuelEventDetector.PID_SPEED,
                        com.example.analysis.RefuelEventDetector.PID_ODO
                    )
                )
                if (rows.isEmpty()) continue
                persistRefuelEvents(com.example.analysis.RefuelSessionScan.scan(rows), settings)
            }
            settings.setRefuelBackfillDone()
        }.onFailure { android.util.Log.e("RecordingManager", "refuel backfill failed", it) }
    }

    private suspend fun persistRefuelEvents(
        scan: com.example.analysis.RefuelSessionScan.Result,
        settings: com.example.data.SettingsRepository
    ) {
        val capacity = settings.tankCapacityL()
        val events = scan.events.toMutableList()
        settings.lastLevelStamp()?.let { stamp ->
            scan.firstLevel?.let { fl ->
                com.example.analysis.RefuelEventDetector.crossSession(
                    stamp.first, stamp.second, stamp.third, fl.first, fl.second, scan.firstOdo
                )?.let { events += it }
            }
        }
        scan.lastLevel?.let { settings.setLastLevelStamp(it.first, it.second, it.third) }
        for (ev in events) {
            tripRepository.insertRefuelEvent(
                com.example.data.db.entities.RefuelEventEntity(
                    idMs = ev.windowEndMs,
                    tsStartMs = ev.windowStartMs,
                    tsEndMs = ev.windowEndMs,
                    levelBeforePct = ev.levelBeforePct,
                    levelAfterPct = ev.levelAfterPct,
                    odoKm = ev.odoKm,
                    // An ESTIMATE until a pump-litre calibration replaces it; labelled as such
                    // in the UI, never presented as a measurement (no-fake-values rule).
                    estLitres = ev.risePct / 100.0 * capacity,
                    betweenSessions = if (ev.betweenSessions) 1 else 0,
                    capacityL = capacity
                )
            )
        }
    }

    // ── AUTOMATIC recovery of killed sessions (owner 2026-09-17) ───────────────────────
    //
    // Recovery used to be a banner on the Trips & Recordings screen that the owner had to notice
    // and tap. He did not, and a full day of driving stayed unrecovered. Recovery now runs BY
    // ITSELF on app start, rebuilds every cut-off session, and reports what it did.

    /** What the automatic pass did. Worded for the owner, not for a logcat. */
    data class RecoverySummary(
        val recoveredSessions: Int = 0,
        val recoveredTransactions: Int = 0,
        val sessionIds: List<String> = emptyList(),
        val lostSessions: List<String> = emptyList(),
        val failures: List<String> = emptyList()
    ) {
        val isClean: Boolean
            get() = recoveredSessions == 0 && lostSessions.isEmpty() && failures.isEmpty()

        fun notice(): String {
            val parts = mutableListOf<String>()
            if (recoveredSessions > 0) {
                parts += "Recovered $recoveredSessions killed session(s) - $recoveredTransactions " +
                    "OBD lines saved. They are in Trips & Recordings now; nothing to tap."
            }
            if (lostSessions.isNotEmpty()) {
                parts += "Could not recover ${lostSessions.size} session(s) " +
                    "(${lostSessions.joinToString()}) - no journal rows and no readable raw log."
            }
            if (failures.isNotEmpty()) parts += failures.joinToString(" | ")
            return parts.joinToString(" ")
        }
    }

    /**
     * Rebuilds every session the process died during. Safe to call repeatedly: a session is
     * removed from the pending set as soon as it is rebuilt, and one that cannot be rebuilt is
     * archived so it is reported once instead of forever.
     */
    suspend fun recoverUnfinishedSessions(): RecoverySummary? = withContext(Dispatchers.IO) {
        if (_recoveryRunning.value) return@withContext _journalRecovery.value
        _recoveryRunning.value = true
        val summary = try {
            var recovered = 0
            var transactions = 0
            val ids = mutableListOf<String>()
            val lost = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val savedIds = _savedRecordings.value.map { it.metadata.sessionId }.toSet()
            // The session THIS process is recording right now has an unfinished journal by
            // definition - it only gets its `.finished` marker at STOP. Rebuilding it would write a
            // half trip and then fight the live recorder over the same files.
            val liveId = if (_isRecording.value) _currentSessionMetadata.value?.sessionId else null

            for (id in journal.unfinishedSessions()) {
                if (id == liveId) continue
                if (id in savedIds) { journal.discard(id); continue }
                val rawLog = File(rawLogsDir, "raw_log_$id.txt").takeIf { it.exists() }
                val source = SessionRecoveryPolicy.chooseSource(
                    journalRowCount = CsvExporter.readTransactionsFromCsv(journal.txFile(id)).size,
                    rawLogBytes = rawLog?.length() ?: 0L
                )
                when (val r = if (source == SessionRecoveryPolicy.RecoverySource.JOURNAL) {
                    recoverJournalSession(id, rawLog)
                } else {
                    RecoveryOutcome.NothingToRecover
                }) {
                    is RecoveryOutcome.Recovered -> {
                        recovered++; transactions += r.samples; ids += id
                        journal.discard(id)
                    }
                    else -> {
                        // The journal is empty or unreadable. Fall back to the raw log, which is
                        // the older recovery path and still holds every frame that reached the phone.
                        val fromRaw = if (rawLog != null) {
                            runCatching { recoverFromRawLog(rawLog) }.getOrNull()
                        } else null
                        if (fromRaw is RecoveryOutcome.Recovered) {
                            recovered++; transactions += fromRaw.samples; ids += id
                            journal.discard(id)
                        } else {
                            lost += id
                            (fromRaw as? RecoveryOutcome.Failed)?.reason?.let { failures += "$id: $it" }
                            // Archive it so the next launch does not offer the same corpse again.
                            if (rawLog != null) runCatching { archiveUnrecoverableRawLog(rawLog) }
                            journal.discard(id)
                        }
                    }
                }
            }

            // Raw logs with no journal at all: sessions recorded by an older build, or a drive
            // whose journal could not be opened. Still recoverable, still automatic.
            for (file in findUnsavedRawLogs()) {
                val id = com.example.analysis.RawLogRecovery.sessionIdOf(file.name) ?: continue
                if (id in savedIds || id in ids || id in lost || id == liveId) continue
                when (val r = runCatching { recoverFromRawLog(file) }.getOrNull()) {
                    is RecoveryOutcome.Recovered -> {
                        recovered++; transactions += r.samples; ids += id
                    }
                    is RecoveryOutcome.Failed -> {
                        lost += id; failures += "$id: ${r.reason}"
                        runCatching { archiveUnrecoverableRawLog(file) }
                    }
                    else -> Unit // NothingToRecover: leave it, it may still be mid-drive.
                }
            }

            if (recovered > 0) loadSavedRecordings()
            if (SessionRecoveryPolicy.shouldAnnounce(recovered, lost.size, failures.size)) {
                RecoverySummary(recovered, transactions, ids, lost, failures)
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("RecordingManager", "auto recovery failed", e)
            RecoverySummary(failures = listOf("auto-recovery error: ${e.message ?: e.javaClass.simpleName}"))
        }
        _recoveryRunning.value = false
        _journalRecovery.value = summary
        summary
    }

    /**
     * Rebuilds one cut-off session from its journal.
     *
     * The journal already holds the DECODED transactions - parameter name, value, unit, status,
     * per-line GPS altitude - so recovery reads them back instead of re-parsing raw hex and
     * re-decoding, which is what the raw-log path has to do. Rows are replayed through the same
     * pure [mergeSample] the live recorder used, so the wide sample rows are identical to the ones
     * STOP would have written.
     */
    internal suspend fun recoverJournalSession(sessionId: String, rawLogFile: File?): RecoveryOutcome {
        return try {
            val txList = CsvExporter.readTransactionsFromCsv(journal.txFile(sessionId))
            if (txList.isEmpty()) return RecoveryOutcome.NothingToRecover

            val meta = journal.readMeta(sessionId)
            // The trip window is the DATA window: first frame to last frame. The journal also
            // records when the session was opened, but using that as the start would add the
            // connect-to-first-response gap to every recovered duration - and the raw-log recovery
            // path has always used the first telemetry line, so the two now agree.
            val startMs = txList.minOf { it.timestampMonotonic }
            val openedMs = meta["startEpochMillis"]?.toLongOrNull()
                ?: RecordTime.parseMillis(meta["startTime"])
                ?: startMs

            val sampleRows = CsvExporter.readSamplesFromCsv(journal.sampleFile(sessionId))
            // Samples journal missing or short (an older build, a write failure): replay the wide
            // rows from the transactions so the trip still gets them. Same pure fold as live.
            val samples = if (SessionRecoveryPolicy.samplesUsable(sampleRows.size, txList.size)) {
                sampleRows
            } else {
                SessionRecoveryPolicy.replaySamples(
                    transactions = txList,
                    seed = SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L),
                    merge = { acc, tx -> mergeSample(acc, tx).copy(altitudeM = tx.altitudeM) }
                )
            }

            // Keep the name the drive was given when it started, but say it was recovered: the
            // owner looks for "Recovered Run" in the trip list to know a drive was rescued rather
            // than stopped, and losing that marker would make a killed trip indistinguishable from
            // a normal one.
            val recordedName = meta["sessionName"]?.takeIf { it.isNotBlank() }
            val name = when {
                recordedName == null -> "Recovered Run " + RecordTime.display(openedMs)
                recordedName.startsWith("Recovered") -> recordedName
                else -> "$recordedName (recovered)"
            }
            val metadata = RecordingMetadata(
                sessionId = sessionId,
                sessionName = name,
                vehicle = meta["vehicle"]?.ifBlank { null } ?: "Škoda Kylaq 1.0 TSI (EA211)",
                vehicleId = meta["vehicleId"]?.ifBlank { null },
                profile = meta["profile"]?.ifBlank { null } ?: "India-Market 1.0 TSI",
                adapter = meta["adapter"]?.ifBlank { null } ?: "ELM327 v1.5 Bluetooth Classic",
                protocol = meta["protocol"]?.ifBlank { null } ?: "ISO 15765-4 CAN 11-bit 500kbps",
                canBitrate = meta["canBitrate"]?.ifBlank { null } ?: "500 kbps",
                startTimeUtc = RecordTime.stamp(startMs)
            )

            val saved = finalizeSession(
                metadata = metadata,
                txList = txList,
                sampleList = samples,
                rawLogFile = rawLogFile,
                recovered = true
            ) ?: return RecoveryOutcome.NothingToRecover

            RecoveryOutcome.Recovered(sessionId, saved.transactionCount)
        } catch (e: Exception) {
            android.util.Log.e("RecordingManager", "journal recovery failed for $sessionId", e)
            RecoveryOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }


    fun loadSavedRecordings() {
        val result = mutableListOf<SavedRecording>()
        val dirs = recordingsDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("session_") } ?: emptyList()

        for (dir in dirs) {
            val sessionId = dir.name.removePrefix("session_")
            val jsonFile = File(dir, "$sessionId.json")
            val txCsv = File(dir, "${sessionId}_transactions.csv")
            val sampleCsv = File(dir, "${sessionId}_samples.csv")
            val rawFile = File(dir, "${sessionId}_raw.txt")
            val zipFile = File(dir, "${sessionId}_bundle.zip")

            if (jsonFile.exists()) {
                try {
                    val jsonStr = jsonFile.readText()
                    val root = JSONObject(jsonStr)
                    val metaObj = root.getJSONObject("sessionMetadata")
                    val txArray = root.getJSONArray("transactions")

                    val meta = RecordingMetadata(
                        sessionId = metaObj.getString("sessionId"),
                        sessionName = metaObj.optString("sessionName", "Session $sessionId"),
                        vehicle = metaObj.optString("vehicle", "Škoda Kylaq 1.0 TSI"),
                        profile = metaObj.optString("profile", "India-Market 1.0 TSI"),
                        adapter = metaObj.optString("adapter", "ELM327 v1.5"),
                        protocol = metaObj.optString("protocol", "ISO 15765-4"),
                        canBitrate = metaObj.optString("canBitrate", "500 kbps"),
                        startTimeUtc = metaObj.getString("startTimeUtc"),
                        endTimeUtc = metaObj.optString("endTimeUtc", null),
                        appVersion = metaObj.optString("appVersion", "1.0")
                    )

                    result.add(
                        SavedRecording(
                            metadata = meta,
                            transactionCount = txArray.length(),
                            transactionCsvFile = txCsv,
                            samplesCsvFile = sampleCsv,
                            jsonFile = jsonFile,
                            rawLogFile = if (rawFile.exists()) rawFile else null,
                            zipFile = if (zipFile.exists()) zipFile else null
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        _savedRecordings.value = result.sortedByDescending { it.metadata.startTimeUtc }
    }

    // ── Unsaved raw-log recovery (owner 2026-09-15) ────────────────────────────────
    // A recording lives in RAM until STOP finalizes it; a process kill mid-drive
    // (swipe-away / battery optimization / crash) loses the trip — but the raw log was
    // flushed to disk after every line, so the drive survives and can be rebuilt here.

    private val rawLogsDir: File get() = File(context.filesDir, "raw_logs")

    /** Raw-log sessions on disk that were never finalized into a saved trip, newest first. */
    fun findUnsavedRawLogs(): List<File> {
        val saved = _savedRecordings.value.map { it.metadata.sessionId }.toSet()
        return rawLogsDir.listFiles()?.filter { f ->
            val id = com.example.analysis.RawLogRecovery.sessionIdOf(f.name)
            id != null && id !in saved && f.length() > 64L
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * WHY every recovery outcome is reported (owner 2026-09-16: "Still im unable to
     * recover the logs"): recoverFromRawLog used to return null silently and the
     * ViewModel swallowed it, so tapping Recover looked completely dead - including
     * when a half-finished earlier attempt left an EMPTY session dir that blocked all
     * later attempts forever.
     */
    sealed class RecoveryOutcome {
        data class Recovered(val sessionId: String, val samples: Int) : RecoveryOutcome()
        object NothingToRecover : RecoveryOutcome()
        object AlreadyRecovered : RecoveryOutcome()
        data class Failed(val reason: String) : RecoveryOutcome()
    }

    /**
     * Moves a raw log that carries no recoverable telemetry out of the scan dir (kept,
     * never deleted) so the recovery banner stops offering it forever.
     */
    fun archiveUnrecoverableRawLog(file: File) {
        try {
            val archiveDir = File(rawLogsDir, "archived").apply { mkdirs() }
            file.renameTo(File(archiveDir, file.name))
        } catch (_: Exception) {}
    }

    /**
     * Rebuilds and persists a killed recording from its raw log, producing the same
     * artifacts as stopRecording() (session dir, CSV/JSON/ZIP bundle, Room trip +
     * samples, AI analysis). Every outcome is reported - silence was the bug. GPS
     * altitude stays null for recovered trips — honest blank, never invented.
     */
    suspend fun recoverFromRawLog(file: File): RecoveryOutcome = withContext(Dispatchers.IO) {
        try {
            val sessionId = com.example.analysis.RawLogRecovery.sessionIdOf(file.name)
                ?: return@withContext RecoveryOutcome.Failed("unrecognised raw-log file name")
            val sessionDirCheck = File(recordingsDir, "session_$sessionId")
            if (sessionDirCheck.exists()) {
                // A crashed earlier attempt can leave an EMPTY stub dir that made every
                // later Recover tap return null forever. Complete session = already in
                // Trips; incomplete stub = delete it and recover properly this time.
                // Same criterion as loadSavedRecordings(): the session JSON is what makes
                // a session dir a real saved recording. CSV-only dirs are crashed stubs.
                val complete = File(sessionDirCheck, "$sessionId.json").exists()
                if (complete) return@withContext RecoveryOutcome.AlreadyRecovered
                sessionDirCheck.deleteRecursively()
            }

            val telemetry = com.example.analysis.RawLogRecovery.extractTelemetry(
                file.readText(), file.lastModified()
            )
            if (telemetry.size < 10) return@withContext RecoveryOutcome.NothingToRecover

            val txList = telemetry.map { t ->
                val pidDef = com.example.model.StandardPidCatalog.lookup(t.pidHex2)
                // decode() needs the payload WITH its "41 <pid>" header - handing it the
                // bare data bytes returns INVALID_RESPONSE (strict malformed-frame rule).
                val decoded = com.example.protocol.PidDecoder.decode(pidDef, t.decodeBytes)
                TransactionRecord(
                    timestampUtc = iso(t.epochMillis),
                    timestampMonotonic = t.epochMillis,
                    direction = Direction.RX,
                    canRxId = t.canId,
                    requestHex = "01${t.pidHex2}",
                    responseHex = t.responseHex,
                    service = "01",
                    pid = pidDef.pid,
                    rawPayload = t.payloadBytes.joinToString("") { "%02X".format(it) },
                    decodedParameter = decoded.parameterName,
                    decodedValue = decoded.numericValue,
                    decodedValueDisplay = decoded.displayValue,
                    unit = decoded.unit,
                    responseStatus = ResponseStatus.OK
                )
            }
            val startMs = telemetry.first().epochMillis
            val endMs = telemetry.last().epochMillis
            val metadata = RecordingMetadata(
                sessionId = sessionId,
                sessionName = "Recovered Run " + RecordTime.display(startMs),
                startTimeUtc = iso(startMs),
                adapter = "ELM327 Bluetooth (recovered from raw log)"
            )
            metadata.endTimeUtc = iso(endMs)

            // This path used to write an EMPTY samples CSV, so a trip recovered from its raw log
            // came back with a transactions file and no curves, no fuel trend and no X-ray wide
            // rows. Replay the same pure fold the live recorder uses and the recovered trip is
            // shaped exactly like a stopped one.
            val sampleList = SessionRecoveryPolicy.replaySamples(
                transactions = txList,
                seed = SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L),
                merge = { acc, tx -> mergeSample(acc, tx) }
            )

            // One finalization path for STOP, orphan save, journal recovery and raw-log recovery:
            // they had already drifted (this one skipped the altitude/voltage extremes and wrote no
            // sample rows), and drift between copies of the same job is how a trip ends up half
            // saved.
            finalizeSession(
                metadata = metadata,
                txList = txList,
                sampleList = sampleList,
                rawLogFile = file,
                recovered = true
            )

            loadSavedRecordings()
            RecoveryOutcome.Recovered(sessionId, txList.size)
        } catch (e: Exception) {
            e.printStackTrace()
            RecoveryOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Every stamp this class writes goes through here.
     *
     * It used to be `isoUtc()`, formatting in UTC with a trailing `Z`. Owner mandate 2026-09-17:
     * "For all records use IST time only no UTC." The name stays `iso` because the shape is still
     * ISO-8601; the zone is now Asia/Kolkata and the offset is printed (`+05:30`), so a stamp can
     * never again be read as the wrong local time.
     */
    private fun iso(millis: Long): String = RecordTime.stamp(millis)

    fun renameRecording(sessionId: String, newName: String) {
        val sessionDir = File(recordingsDir, "session_$sessionId")
        val jsonFile = File(sessionDir, "$sessionId.json")
        if (jsonFile.exists()) {
            try {
                val jsonStr = jsonFile.readText()
                val root = JSONObject(jsonStr)
                val metaObj = root.getJSONObject("sessionMetadata")
                metaObj.put("sessionName", newName)
                jsonFile.writeText(root.toString(2))
                loadSavedRecordings()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteRecording(sessionId: String) {
        val sessionDir = File(recordingsDir, "session_$sessionId")
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
            loadSavedRecordings()
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tripRepository.deleteTrip(sessionId) }
                .onFailure { android.util.Log.e("RecordingManager", "trip delete failed", it) }
        }
    }

    fun deleteAllRecordings() {
        recordingsDir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        loadSavedRecordings()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tripRepository.deleteAllTrips() }
                .onFailure { android.util.Log.e("RecordingManager", "delete-all failed", it) }
        }
    }

    /**
     * Imports a single ZIP archive from a content URI.
     */
    suspend fun importZipFile(uri: android.net.Uri): ZipImportResult {
        val result = ZipImporter.importTripZip(context, uri, recordingsDir, tripRepository)
        if (result.success) {
            loadSavedRecordings()
        }
        return result
    }

    /**
     * Imports multiple ZIP archives sequentially and returns a combined summary.
     */
    suspend fun importZipFiles(uris: List<android.net.Uri>): List<ZipImportResult> {
        val results = mutableListOf<ZipImportResult>()
        for (uri in uris) {
            val res = ZipImporter.importTripZip(context, uri, recordingsDir, tripRepository)
            results.add(res)
        }
        loadSavedRecordings()
        return results
    }
}


