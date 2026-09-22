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
     * Restart-refuel candidate (owner 2026-09-19: "when restart my car after refuel obviously you
     * can scan what is fuel % before start and after start ... you can popup in the app fuel
     * change detection do you want to add receipt"). The pump visit happens engine-off, between
     * sessions; the FIRST valid fuel-level row after the restart is the "after" half against the
     * persisted stamp - so the event is written and the receipt prompt raised the moment the car
     * starts, not silently at session end.
     */
    data class RestartRefuel(
        /** Also the event id and the restart row's own IST instant (window end). */
        val idMs: Long,
        val tsStartMs: Long,
        val levelBeforePct: Double,
        val levelAfterPct: Double,
        /** Level-rise estimate from tank capacity - labelled estimated everywhere, never pump truth. */
        val estLitres: Double,
        val odoKm: Double?
    )

    private val _restartRefuel = MutableStateFlow<RestartRefuel?>(null)
    val restartRefuel: StateFlow<RestartRefuel?> = _restartRefuel

    /** Armed per session; the first level row disarms it, so the check runs exactly once. */
    @Volatile
    private var restartLevelCheckArmed = false

    fun clearRestartRefuel() { _restartRefuel.value = null }

    /**
     * The crash-proof journal (owner 2026-09-17: "it never ever loose the logs").
     * Every OBD transaction is appended here AND FLUSHED before `recordTransaction` returns, so a
     * process kill costs at most the row in flight instead of the whole drive.
     */
    val journal: SessionJournal = SessionJournal(File(recordingsDir, "journal"))

    /**
     * Tombstones of every session id a merge has consumed (owner 2026-09-22: *"When I merge
     * fragmented same trip it's showing as recovered and merging multiple times"*).
     *
     * A merge used to delete the fragments' session dirs, Room rows and journals but leave their
     * `raw_logs/raw_log_<id>.txt` behind. Recovery asks only "is this id already a saved trip?",
     * and after a merge the answer is no - the merged trip has a NEW id - so the next launch
     * rebuilt every fragment as a fresh "Recovered Run" and the owner merged the same drive
     * again, doubling its telemetry each pass. The merge now folds those raw logs into the merged
     * bundle and removes them from the scan directory; this ledger is the belt that also covers
     * recovery triggered from the keep-alive service, a boot receiver or a Drive restore.
     */
    val mergeLedger: MergeLedger = MergeLedger(File(context.filesDir, "merged_sessions.log"))

    private val _journalRecovery = MutableStateFlow<RecoverySummary?>(null)

    /** Result of the automatic recovery that runs on app start. Null = nothing was pending. */
    val journalRecovery: StateFlow<RecoverySummary?> = _journalRecovery.asStateFlow()

    private val _recoveryRunning = MutableStateFlow(false)
    val recoveryRunning: StateFlow<Boolean> = _recoveryRunning.asStateFlow()

    init {
        // KILL-AUDIT FIX C: this constructor runs inside AppContainer.init, which MainViewModel
        // triggers on the MAIN thread - and loadSavedRecordings() walks every session directory
        // parsing JSON. As trips pile up that is seconds of disk IO on the UI thread, and Android
        // kills even a foreground process for an ANR. Load on the manager's IO scope instead; the
        // trip list is a StateFlow, so the UI simply receives it a moment later.
        managerScope.launch { loadSavedRecordings() }
    }

    companion object {
        const val SILENT_LIMIT_MS: Long = 5 * 60_000L

        /**
         * Grams-per-second (PID 019D) to litres-per-hour at petrol density 0.745 kg/L. The same
         * factor `ObdScheduler.effectiveFuelRateLh` and `TripFuelSummary.buildFuelSeries` use, so
         * the live dashboard, the trip summary and the exported wide rows finally agree.
         */
        const val MASS_GS_TO_LH: Double = 3600.0 / 745.0

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
                // Fuel rate, in L/h, from whichever PID answered.
                //
                // 015E is ALREADY litres per hour ((A*256+B)/20). 019D is grams per second
                // ((A*256+B)/50, the 2026-09-13 real-car calibration) and this fold used to copy
                // that number straight into a column named `fuelRateLh` - so every samples CSV,
                // every ZIP bundle and every wide row of a recovered trip carried g/s labelled as
                // L/h, ~4.85x too high (0.68 g/s idle written as "0.680 L/h" instead of 3.29).
                // `ObdScheduler` and `TripFuelSummary` both convert at 745 g/L; the wide row was
                // the only place that did not, and it is the one that reaches the trip log.
                "5E" -> base.copy(fuelRateLh = tx.decodedValue)
                "9D" -> base.copy(fuelRateLh = tx.decodedValue?.let { it * MASS_GS_TO_LH })
                // Tank level (PID 012F): what makes the trip's start/end fuel percentage
                // reconstructible from the log files, not only from the database.
                "2F" -> base.copy(fuelLevelPct = tx.decodedValue)
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
            "5E" -> "fuelRateLh"
            "9D" -> "fuelRateLh"
            "2F" -> "fuelLevelPct"
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
        restartLevelCheckArmed = true
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

    /** Wall-clock age of the open session, for the auto-record young-session guard. */
    fun currentSessionAgeMs(nowMs: Long = System.currentTimeMillis()): Long? =
        _currentSessionMetadata.value?.let {
            nowMs - (RecordTime.parseMillis(it.startTimeUtc) ?: nowMs)
        }

    /**
     * Resumes a session a process death cut off, in the same journal files, instead of
     * opening a new one (owner 2026-09-21: "you see what it did to my 31km trip nothing
     * logged" - every mid-drive death used to finalize the live journal as its own
     * "Recovered Run" and the restart began a brand-new session, shredding one drive
     * into one trip per death). The rows the previous process journaled are reloaded
     * wall-anchored, so the finalized trip carries every leg: RAM lists of this process
     * start empty and finalizeSession trusts them. Reloaded rows keep wall millis in the
     * monotonic column while rows recorded after the resume keep uptime - harmless by
     * construction, because the trip window and every stored stamp come from IST stamps
     * (see [SessionRecoveryPolicy.wallEpochMs]).
     */
    fun resumeRecording(sessionId: String): RecordingMetadata? {
        if (_isRecording.value) return _currentSessionMetadata.value
        val meta = journal.readMeta(sessionId)
        if (meta.isEmpty()) return null
        val openedMs = meta["startEpochMillis"]?.toLongOrNull()
            ?: RecordTime.parseMillis(meta["startTime"])
            ?: System.currentTimeMillis()
        val metadata = RecordingMetadata(
            sessionId = sessionId,
            sessionName = meta["sessionName"]?.takeIf { it.isNotBlank() }
                ?: "Kylaq Run " + RecordTime.display(openedMs),
            vehicle = meta["vehicle"]?.ifBlank { null } ?: "Škoda Kylaq 1.0 TSI (EA211)",
            vehicleId = meta["vehicleId"]?.ifBlank { null },
            profile = meta["profile"]?.ifBlank { null } ?: "India-Market 1.0 TSI",
            adapter = meta["adapter"]?.ifBlank { null } ?: "ELM327 v1.5 Bluetooth Classic",
            protocol = meta["protocol"]?.ifBlank { null } ?: "ISO 15765-4 CAN 11-bit 500kbps",
            canBitrate = meta["canBitrate"]?.ifBlank { null } ?: "500 kbps",
            startTimeUtc = RecordTime.stamp(openedMs)
        )
        if (!journal.resume(metadata)) return null
        val reloaded = CsvExporter.readTransactionsFromCsv(journal.txFile(sessionId)).map { tx ->
            val wall = SessionRecoveryPolicy.wallEpochMs(tx.timestampUtc, tx.timestampMonotonic)
            if (wall == tx.timestampMonotonic) tx else tx.copy(timestampMonotonic = wall)
        }
        synchronized(activeTransactionList) {
            activeTransactionList.clear()
            activeSampleList.clear()
            activeTransactionList.addAll(reloaded)
            val replayed = SessionRecoveryPolicy.replaySamples(
                transactions = reloaded,
                seed = SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L),
                merge = { acc, tx -> mergeSample(acc, tx).copy(altitudeM = tx.altitudeM) }
            )
            activeSampleList.addAll(replayed)
            currentSample = replayed.lastOrNull()
                ?: SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L)
        }
        _currentSessionMetadata.value = metadata
        _isRecording.value = true
        restartLevelCheckArmed = false
        runCatching { rawLogManager.startFileLogging(sessionId) }
        return metadata
    }

    /**
     * The auto-record supervisors' single entry point: a drive cut off inside the resume
     * window continues in its own session; anything older starts fresh. One drive, one
     * trip, across any number of process deaths.
     */
    fun startOrResumeRecording(): RecordingMetadata? {
        val resumable = journal.unfinishedSessions().firstOrNull { id ->
            SessionRecoveryPolicy.isResumable(
                System.currentTimeMillis() - journal.txFile(id).lastModified()
            )
        }
        return if (resumable != null) resumeRecording(resumable) else startRecording()
    }

    fun recordTransaction(tx: TransactionRecord) {
        if (!_isRecording.value) return

        // Restart-refuel check: the first valid level row of this session, once (RestartRefuel).
        if (restartLevelCheckArmed &&
            tx.pid == com.example.analysis.RefuelEventDetector.PID_LEVEL &&
            tx.decodedValue != null
        ) {
            restartLevelCheckArmed = false
            maybeFlagRestartRefuel(tx)
        }

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
        // KILL-AUDIT FIX A (owner 2026-09-19: "find hidden mechanism which could kill app during
        // trip and lose a trip data"): the `.finished` marker used to be written HERE - before
        // finalizeSession spends seconds writing the CSVs, the ZIP, the Room rows and the
        // analyses. A kill inside that window left a journal that CLAIMED a clean stop while the
        // trip did not exist, and recovery filters finished journals out: the drive was lost with
        // a clean-stop alibi. The writer handles close now (every row was already flushed
        // line-by-line), but the marker moves to AFTER the trip is really persisted - a kill
        // anywhere before that leaves the journal unfinished and recovery rebuilds it.
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
        // KILL-AUDIT FIX A: the marker is earned by the persisted trip, never by the intention to
        // persist one. finalizeSession throwing skips it too - the journal stays unfinished and
        // the next recovery pass rebuilds the drive instead of trusting a lie.
        if (SessionRecoveryPolicy.finishedMarkerAllowed(saved != null)) {
            journal.markFinished(metadata.sessionId, endTimestamp)
        }
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
        recovered: Boolean,
        /**
         * Whether the LIVE GPS accumulator belongs to the trip being written. True only for a
         * clean STOP of the drive in progress.
         *
         * A recovered or merged trip used to inherit it whenever it happened to be non-empty -
         * and it is non-empty exactly when some OTHER drive is being recorded right now, because
         * the keep-alive service belts GPS on for any live recording. The result was one trip's
         * elevation range stamped onto a different trip's summary (owner 2026-09-22: *"Altitude
         * not logging still"* - a merged trip showed the elevation of whatever he drove next).
         * With this false, the window comes only from the rows the trip itself persisted.
         */
        liveGpsWindow: Boolean = !recovered
    ): SavedRecording? {
        if (txList.isEmpty()) return null
        val sessionId = metadata.sessionId

        // The trip window is the DATA window: first row to last row. Deriving it here rather than
        // passing it in means every caller - STOP, an orphaned restart, journal recovery, raw-log
        // recovery - reports the same thing, and no caller can hand over a window that disagrees
        // with the rows it just handed over. The instant comes from each row's IST STAMP, not from
        // the monotonic column: that column is SystemClock.elapsedRealtime() (uptime since boot),
        // and reading it as an epoch put every trip's Room window around 1970 - which is how a
        // same-day recovered drive earned the "recorded by an older build" altitude footnote
        // (owner 2026-09-20: "Altitude still not logging in").
        val startTimestamp = txList.minOf { SessionRecoveryPolicy.wallEpochMs(it.timestampUtc, it.timestampMonotonic) }
        val endTimestamp = txList.maxOf { SessionRecoveryPolicy.wallEpochMs(it.timestampUtc, it.timestampMonotonic) }
        // BOTH ends of the written window come from the rows. `endTimeUtc` always did; `startTimeUtc`
        // used to stay at the instant START was tapped, so one trip carried two disagreeing starts:
        // its Room row and duration said "first OBD answer", while its session JSON, its CSV header
        // and its trip card said "the moment I pressed record". The gap is the whole adapter
        // connect-and-handshake, seconds normally and minutes on a flaky link - and the merge review
        // reads that stamp to measure the silence between two fragments, so a fragment that took a
        // minute to connect reported a minute less gap than the drive really had (owner 2026-09-22:
        // "do a proper review while merging"). One window, derived once, written everywhere.
        val startStamp = RecordTime.stamp(startTimestamp)
        val endStamp = RecordTime.stamp(endTimestamp)

        // GPS altitude window for this trip (owner 2026-09-15 fix: altitude was captured live but
        // never persisted - the trip summary showed an honest "-- m" blank). Null only when the
        // recording never had an accuracy-gated GPS fix with altitude; never 0.0, never invented.
        // KILL-AUDIT FIX B: the live GPS accumulator is RAM - a kill empties it, so a RECOVERED
        // trip used to show "-- m" even though every journaled sample row carries its altitude_m.
        // Fall back to reducing the persisted rows through the same plausibility gate; null only
        // when no row ever held an altitude. Never invented, never lost.
        // Live accumulator (only when it is THIS trip's) widened by every altitude the trip's own
        // rows carry. Neither source alone is enough: a process restart mid-drive resets the live
        // window to the post-restart leg while the rows still hold the whole drive, and a
        // recovered trip's live window is empty RAM while the rows are all that survived.
        val liveAlt = if (liveGpsWindow) {
            com.example.di.AppContainer.tripAltitudeStats()?.takeIf { it.sampleCount > 0 }
        } else {
            null
        }
        val rowAlt = com.example.analysis.AltitudeStats.reduce(
            sampleList.mapNotNull { it.altitudeM } + txList.mapNotNull { it.altitudeM }
        )
        val altStats = com.example.analysis.AltitudeStats.combine(liveAlt, rowAlt)
        // Battery voltage extremes (owner pipeline task 3, 2026-09-16), reduced from the real 0142
        // samples exactly like the altitude window.
        val voltStats = com.example.analysis.VoltageStats.extremes(
            sampleList.mapNotNull { smp -> smp.voltageV?.let { smp.timestampMonotonic to it } }
        )
        // Tank level at the START and at the END of this drive, from its own 012F rows
        // (owner 2026-09-22: "Fuel percentage at the start of trip & end of trip is also not
        // available on trip logs"). Measured, never carried over from the previous trip and
        // never estimated: null when the ECU never answered 012F, so the log says "-- %"
        // instead of printing a plausible number nobody measured.
        val fuelLevel = com.example.analysis.TripFuelSummary.fuelLevelWindow(
            txList,
            pid = { it.pid },
            value = { it.decodedValue },
            timestamp = { SessionRecoveryPolicy.wallEpochMs(it.timestampUtc, it.timestampMonotonic) }
        )
        // The trip log files must carry the same altitude window as the database row, or a
        // backup -> reinstall -> import round trip silently strips elevation from every past trip.
        val metadataForFiles = metadata.copy(
            startTimeUtc = startStamp,
            maxAltitudeM = altStats?.maxAltitudeM,
            minAltitudeM = altStats?.minAltitudeM,
            minVoltageV = voltStats?.minV,
            maxVoltageV = voltStats?.maxV,
            startFuelLevelPct = fuelLevel.startPct ?: metadata.startFuelLevelPct,
            endFuelLevelPct = fuelLevel.endPct ?: metadata.endFuelLevelPct,
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
            voltStats = voltStats,
            fuelLevel = fuelLevel
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
        voltStats: com.example.analysis.VoltageExtremes?,
        fuelLevel: com.example.analysis.TripFuelSummary.FuelLevelWindow =
            com.example.analysis.TripFuelSummary.FuelLevelWindow.NONE
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
                    maxVoltageV = voltStats?.maxV,
                    startFuelLevelPct = fuelLevel.startPct,
                    endFuelLevelPct = fuelLevel.endPct
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
        // Car-pool orphan linking (owner 2026-09-19): rides logged by date and time while this
        // session was driving - including sessions that died abruptly and were recovered only
        // now - join the trip whose window covers them, the moment the trip exists.
        runCatching { relinkCarpoolEntries() }
            .onFailure { android.util.Log.e("RecordingManager", "carpool relink failed", it) }
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
                persistRefuelEvents(
                    com.example.analysis.RefuelSessionScan.scan(
                        rows.map {
                            com.example.analysis.SinceRefuelStats.Row(it.timestamp, it.pid, it.numericValue)
                        }
                    ),
                    settings
                )
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
        for (ev in events) insertEventCarryCalibration(ev, capacity)
    }

    private suspend fun relinkCarpoolEntries() {
        val carpool = com.example.di.AppContainer.carpoolRepository
        val orphans = carpool.entries().filter { it.tripId == null }
        if (orphans.isEmpty()) return
        val windows = tripRepository.allTripsChronological().map {
            com.example.data.CarpoolCodec.TripWindow(it.id, it.startTimestamp, it.endTimestamp)
        }
        orphans.forEach { o ->
            com.example.data.CarpoolCodec.whenMs(o)?.let { ms ->
                com.example.data.CarpoolCodec.tripLinkFor(ms, windows)?.let { link ->
                    carpool.save(o.copy(tripId = link))
                }
            }
        }
    }

    /**
     * One event to disk, REPLACE-idempotent by idMs (window end). The restart write and the
     * finalize write of the SAME between-sessions refuel share that id, so finalize upgrades the
     * row (with this session's own first odometer) instead of duplicating it - and a rewrite must
     * never drop pump litres a receipt already matched, so the calibration is carried across.
     */
    private suspend fun insertEventCarryCalibration(
        ev: com.example.analysis.RefuelEventDetector.Detected,
        capacity: Double
    ) {
        val existingPumpL =
            runCatching { tripRepository.calibratedPumpFor(ev.windowEndMs) }.getOrNull()
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
                calibratedPumpL = existingPumpL,
                capacityL = capacity
            )
        )
    }

    /**
     * The "after" half of a between-sessions refuel, captured live: first valid 012F row of the
     * new session against the previous session's persisted stamp. Writes the event immediately -
     * the popup offers a receipt against an event that already exists in the ledger, and a
     * session that dies again still keeps the detection - then raises the prompt unless the owner
     * already answered it or a receipt already matched it.
     */
    private fun maybeFlagRestartRefuel(tx: TransactionRecord) {
        val ts = RecordTime.instantOf(tx.timestampMonotonic, tx.timestampUtc)
            ?: tx.timestampMonotonic
        val level = tx.decodedValue ?: return
        managerScope.launch {
            runCatching {
                val settings = com.example.di.AppContainer.settingsRepository
                val stamp = settings.lastLevelStamp() ?: return@runCatching
                val detected = com.example.analysis.RefuelEventDetector.crossSession(
                    stamp.first, stamp.second, stamp.third, ts, level, null
                ) ?: return@runCatching
                val capacity = settings.tankCapacityL()
                insertEventCarryCalibration(detected, capacity)
                val receipted = tripRepository.calibratedPumpFor(detected.windowEndMs) != null
                val answered = settings.restartRefuelDismissedMs() == detected.windowEndMs
                if (!receipted && !answered) {
                    _restartRefuel.value = RestartRefuel(
                        idMs = detected.windowEndMs,
                        tsStartMs = detected.windowStartMs,
                        levelBeforePct = detected.levelBeforePct,
                        levelAfterPct = detected.levelAfterPct,
                        estLitres = detected.risePct / 100.0 * capacity,
                        odoKm = detected.odoKm
                    )
                }
            }.onFailure { android.util.Log.e("RecordingManager", "restart refuel check failed", it) }
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
    suspend fun recoverUnfinishedSessions(skipFreshMs: Long = 0L): RecoverySummary? = withContext(Dispatchers.IO) {
        if (_recoveryRunning.value) return@withContext _journalRecovery.value
        _recoveryRunning.value = true
        val summary = try {
            // KILL-AUDIT FIX C2: the dedup below trusts _savedRecordings, whose first load is now
            // asynchronous (FIX C). Reload synchronously here - inside this IO context it is
            // cheap - so `savedIds` is complete before any journal is touched and a restart can
            // never rebuild a trip that already exists.
            loadSavedRecordings()
            var recovered = 0
            var transactions = 0
            val ids = mutableListOf<String>()
            val lost = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val savedIds = _savedRecordings.value.map { it.metadata.sessionId }.toSet()
            // Sessions a merge already consumed. Their data is inside a merged trip, so rebuilding
            // one would resurrect the fragments the owner stitched away and let him merge the same
            // drive again (owner 2026-09-22). Loaded once: the ledger is a small file and this pass
            // can run from the app, the keep-alive service and a boot receiver.
            val mergedAwayIds = mergeLedger.ids()
            // The session THIS process is recording right now has an unfinished journal by
            // definition - it only gets its `.finished` marker at STOP. Rebuilding it would write a
            // half trip and then fight the live recorder over the same files.
            val liveId = if (_isRecording.value) _currentSessionMetadata.value?.sessionId else null

            for (id in journal.unfinishedSessions()) {
                if (id == liveId) continue
                if (id in mergedAwayIds) { journal.discard(id); continue }
                // Resume window (owner 2026-09-21: the 31 km drive that came back as three
                // fragments): a journal cut off moments ago may belong to a drive that is
                // STILL running - the supervisors resume it into the same session instead
                // of letting recovery finalize one trip per process death. Past the window
                // the drive is genuinely over and recovery proceeds as always.
                if (skipFreshMs > 0L &&
                    SessionRecoveryPolicy.isResumable(
                        System.currentTimeMillis() - journal.txFile(id).lastModified(),
                        skipFreshMs
                    )
                ) continue
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
                // findUnsavedRawLogs already filters the ledger; re-checked here because the two
                // loops share `lost`/`ids` state and a raw log must never be rebuilt twice.
                if (id in mergedAwayIds) continue
                // Same resume window as the journal loop: a raw log still being written (or
                // whose journal was cut off moments ago) belongs to a drive that may still
                // be running - recovering it now would finalize half a trip under the live
                // recorder's own session id.
                if (skipFreshMs > 0L &&
                    SessionRecoveryPolicy.isResumable(
                        System.currentTimeMillis() - file.lastModified(),
                        skipFreshMs
                    )
                ) continue
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

            // The journal's monotonic column is UPTIME (SystemClock.elapsedRealtime), not an
            // instant: re-anchor every row to its own IST stamp before anything downstream -
            // window, replayed wide rows, Room rows - reads a clock that says 1970.
            val wallTx = txList.map { tx ->
                val wall = SessionRecoveryPolicy.wallEpochMs(tx.timestampUtc, tx.timestampMonotonic)
                if (wall == tx.timestampMonotonic) tx else tx.copy(timestampMonotonic = wall)
            }

            val meta = journal.readMeta(sessionId)
            // The trip window is the DATA window: first frame to last frame. The journal also
            // records when the session was opened, but using that as the start would add the
            // connect-to-first-response gap to every recovered duration - and the raw-log recovery
            // path has always used the first telemetry line, so the two now agree.
            val startMs = wallTx.minOf { it.timestampMonotonic }
            val openedMs = meta["startEpochMillis"]?.toLongOrNull()
                ?: RecordTime.parseMillis(meta["startTime"])
                ?: startMs

            val sampleRows = CsvExporter.readSamplesFromCsv(journal.sampleFile(sessionId))
            // Samples journal missing or short (an older build, a write failure): replay the wide
            // rows from the transactions so the trip still gets them. Same pure fold as live.
            val samples = if (SessionRecoveryPolicy.samplesUsable(sampleRows.size, wallTx.size)) {
                sampleRows
            } else {
                SessionRecoveryPolicy.replaySamples(
                    transactions = wallTx,
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
                txList = wallTx,
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
                    // STREAM, never materialise (owner crash log 2026-09-20: OOM at the
                    // 256 MB heap, thrown on the UI thread mid-recomposition). The old
                    // readText()+JSONObject built the ENTIRE drive - every journaled OBD
                    // row of every session - in RAM on each app start; once trips piled
                    // up that alone filled the heap and the next Compose allocation died.
                    // The metadata is streamed field by field, the transactions array is
                    // skipped, and the count comes from the CSV's data rows.
                    val meta = SessionJsonReader.readMetadata(jsonFile.reader())
                    if (meta != null) {
                        result.add(
                            SavedRecording(
                                metadata = meta,
                                transactionCount = SessionJsonReader.countTransactionRows(txCsv),
                                transactionCsvFile = txCsv,
                                samplesCsvFile = sampleCsv,
                                jsonFile = jsonFile,
                                rawLogFile = if (rawFile.exists()) rawFile else null,
                                zipFile = if (zipFile.exists()) zipFile else null
                            )
                        )
                    }
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

    /**
     * Raw-log sessions on disk that were never finalized into a saved trip, newest first.
     *
     * A session id in [mergeLedger] is excluded: its rows are already inside a merged trip, so
     * offering it here - or rebuilding it in the automatic pass - would hand the owner the same
     * fragments he just stitched and let him double that drive (owner 2026-09-22: "it's showing
     * as recovered and merging multiple times"). The merge deletes these files, and this is the
     * belt for the ones it could not: a file restored from Drive, a delete that failed on a full
     * disk, a raw log written by a process that died mid-merge.
     */
    fun findUnsavedRawLogs(): List<File> {
        val saved = _savedRecordings.value.map { it.metadata.sessionId }.toSet()
        val mergedAway = mergeLedger.ids()
        return rawLogsDir.listFiles()?.filter { f ->
            val id = com.example.analysis.RawLogRecovery.sessionIdOf(f.name)
            id != null && id !in saved && id !in mergedAway && f.length() > 64L
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
                // Streamed rename (same 2026-09-20 OOM class): the old path read the whole
                // drive into a JSONObject and re-serialised it just to change one string.
                // Token-by-token copy with sessionName replaced; peak memory is one token.
                val tmp = File(sessionDir, "$sessionId.json.renaming")
                val renamed = jsonFile.reader().use { input ->
                    tmp.writer().use { output ->
                        SessionJsonReader.writeRenamedSession(input, output, newName)
                    }
                }
                if (renamed) {
                    if (!tmp.renameTo(jsonFile)) {
                        jsonFile.delete()
                        tmp.renameTo(jsonFile)
                    }
                } else {
                    // Not a JsonExporter document: leave the original untouched.
                    tmp.delete()
                }
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
        // Deleting a MERGED trip releases its tombstones: the fragments it consumed are no longer
        // duplicated by a live trip, so a raw log that survived the merge's cleanup may honestly be
        // recovered again instead of staying forbidden forever.
        pruneMergeLedger()
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
        // Every merged trip is gone, so every tombstone is stale: keeping them would forbid
        // recovering raw logs the owner may still want after clearing storage.
        runCatching { mergeLedger.retainOnly(emptySet()) }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tripRepository.deleteAllTrips() }
                .onFailure { android.util.Log.e("RecordingManager", "delete-all failed", it) }
        }
    }

    // ── Merging fragments of one drive back together ──────────────────────────────────
    //
    // Owner 2026-09-21: "there is no option make two trips to merge and make it one trip" — a
    // 31 km drive came back as 13 717 + 45 957 transactions plus a 3-tx stub, each its own trip.
    // Owner 2026-09-22: "When I merge fragmented same trip it's showing as recovered and merging
    // multiple times man do a proper review while merging. If more than 40 min difference between
    // merging files ask user to confirm. For different dates ask user confirmation are you sure.
    // Once it's merged properly no need to keep old fragmented trips for recovered logs
    // immediately after merged auto backup."
    //
    // Four separate defects, all in this block:
    //  1. the merge never LOOKED at what it was joining, so two unrelated drives stitched as
    //     silently as two fragments of one - [planMerge] + [MergeReviewPolicy] now describe the
    //     selection and [MergeResult.NeedsConfirmation] stops the UI before anything is written;
    //  2. the fragments' raw ELM logs stayed in files/raw_logs, so the next recovery pass rebuilt
    //     them as fresh "Recovered Run" trips and the owner merged the same drive again, doubling
    //     it every time - the raw logs are now FOLDED into the merged bundle and removed from the
    //     scan directory, and [mergeLedger] remembers every id a merge consumed;
    //  3. the merged trip inherited the LIVE GPS altitude window, i.e. the elevation of whatever
    //     drive was being recorded when he tapped Merge;
    //  4. the fragments' transactions CSV carried no altitude column, so a merged trip came back
    //     with a blank altitude trend even though every piece had recorded fixes.

    /** What a merge did, or what it needs from the owner before it may touch a single file. */
    sealed class MergeResult {
        /** The merged trip is persisted, the fragments and their recovered logs are gone. */
        data class Merged(
            val saved: SavedRecording,
            val review: MergeReviewPolicy.Review
        ) : MergeResult()

        /**
         * NOTHING was written. The review found something the owner has to agree to first: a
         * silence longer than [MergeReviewPolicy.GAP_CONFIRM_MS] between two pieces, pieces on
         * different dates, a piece that was already merged into another trip, two pieces that
         * look like the same drive twice, pieces that overlap in time, or a selection with
         * nothing mergeable in it at all.
         */
        data class NeedsConfirmation(val review: MergeReviewPolicy.Review) : MergeResult()

        /** The merge could not run, with the reason worded for the owner rather than a logcat. */
        data class Failed(
            val reason: String,
            val review: MergeReviewPolicy.Review? = null
        ) : MergeResult()
    }

    /**
     * Reviews a selection WITHOUT merging it: reads each trip's own window and row count and
     * reports what joining them would mean. Cheap by design - metadata and a row count, never the
     * rows themselves - because the UI calls it the moment the owner taps Merge and again to draw
     * the confirmation, and a 45 000-row fragment must not be parsed twice for a yes/no question.
     */
    suspend fun planMerge(sessionIds: List<String>): MergeReviewPolicy.Review =
        withContext(Dispatchers.IO) {
            // The in-memory list is loaded asynchronously at init, and a review built from an
            // empty list would call every real trip "not in the trip list" with zero rows and
            // report the selection unmergeable. Reload whenever a selected id is not in it.
            val known = _savedRecordings.value
            if (known.isEmpty() || sessionIds.any { id -> known.none { it.metadata.sessionId == id } }) {
                loadSavedRecordings()
            }
            val savedById = _savedRecordings.value.associateBy { it.metadata.sessionId }
            val tombstones = mergeLedger.ids()
            val fragments = sessionIds.distinct().map { id ->
                val meta = savedById[id]?.metadata
                var startMs = meta?.startTimeUtc?.let { RecordTime.parseMillis(it) }
                var endMs = meta?.endTimeUtc?.let { RecordTime.parseMillis(it) }
                var rows = savedById[id]?.transactionCount ?: 0
                // The session JSON is the file of record, but a trip whose end stamp cannot be
                // read back still has an honest window in Room. Take whichever source knows more;
                // never average the two, and never accept a column holding uptime as an instant.
                if (startMs == null || endMs == null || rows == 0) {
                    runCatching { tripRepository.getTripById(id) }.getOrNull()?.let { trip ->
                        if (startMs == null && RecordTime.isPlausibleEpoch(trip.startTimestamp)) {
                            startMs = trip.startTimestamp
                        }
                        if (endMs == null && RecordTime.isPlausibleEpoch(trip.endTimestamp)) {
                            endMs = trip.endTimestamp
                        }
                        if (rows == 0) rows = trip.sampleCount
                    }
                }
                MergeReviewPolicy.Fragment(
                    sessionId = id,
                    name = meta?.sessionName ?: "session $id (not in the trip list)",
                    startMs = startMs,
                    endMs = endMs ?: startMs,
                    transactionCount = rows,
                    alreadyMergedAway = id in tombstones
                )
            }
            MergeReviewPolicy.review(fragments)
        }

    /**
     * Merges saved trips into one, sorted by wall time.
     *
     * @param confirmed the owner has seen [planMerge]'s review and agreed to it. Required whenever
     *   [MergeReviewPolicy.Review.needsConfirmation] is true; ignored when it is not, so a clean
     *   same-drive stitch still happens in one tap.
     * @return [MergeResult.NeedsConfirmation] with the review to show, [MergeResult.Merged] with
     *   the persisted trip, or [MergeResult.Failed] with a reason. Never null-with-no-explanation:
     *   the old signature returned `SavedRecording?` and the UI could only say "Merge failed".
     */
    suspend fun mergeSessions(
        sessionIds: List<String>,
        newName: String? = null,
        confirmed: Boolean = false
    ): MergeResult = withContext(Dispatchers.IO) {
        val ids = sessionIds.distinct()
        if (ids.size < 2) {
            return@withContext MergeResult.Failed(
                "Select at least 2 trips to merge.",
                MergeReviewPolicy.review(emptyList())
            )
        }

        // REVIEW BEFORE WRITE. Nothing is read, moved or deleted until this has been looked at -
        // and when it needs the owner's agreement, the call returns instead of guessing.
        val review = planMerge(ids)
        // An empty selection is a refusal, not a question. Only a stitch we COULD perform cleanly
        // is ever offered for confirmation - asking "are you sure?" about a merge that cannot
        // happen, and then failing anyway once he says yes, just burns a tap.
        if (!review.mergeable) {
            // Confirmation cannot conjure rows: an unmergeable selection stays unmergeable.
            return@withContext MergeResult.Failed(review.headline(), review)
        }
        if (review.needsConfirmation && !confirmed) {
            return@withContext MergeResult.NeedsConfirmation(review)
        }
        val usableIds = review.usable.map { it.sessionId }

        val allTx = mutableListOf<TransactionRecord>()
        var firstMeta: RecordingMetadata? = null
        var vehicleName = "Škoda Kylaq 1.0 TSI (EA211)"
        var vehicleId: String? = null
        var adapterName = "ELM327 v1.5 Bluetooth Classic"
        var protocolName = "ISO 15765-4 CAN 11-bit 500kbps"
        var earliestMs = Long.MAX_VALUE
        val rawFragments = mutableListOf<Pair<String, File>>()
        val stitched = mutableListOf<String>()

        // usableIds is already chronological: the review sorted the fragments by their own window.
        for (id in usableIds) {
            val dir = File(recordingsDir, "session_$id")
            val txFile = File(dir, "${id}_transactions.csv")
            val loaded = if (txFile.exists()) {
                CsvExporter.readTransactionsFromCsv(txFile)
            } else {
                emptyList()
            }
            if (loaded.isEmpty()) continue

            val wall = loaded.map { tx ->
                val w = SessionRecoveryPolicy.wallEpochMs(tx.timestampUtc, tx.timestampMonotonic)
                if (w == tx.timestampMonotonic) tx else tx.copy(timestampMonotonic = w)
            }

            // Elevation the fragment recorded but its transactions CSV could not carry (the
            // `altitude_m` column is new): re-associate the fragment's OWN fixes, from its wide
            // rows and its Room rows, onto the rows that have none. Nearest fix within 1.5 s -
            // GPS delivers at 1 Hz, the poller at ~11 rows/s, so that IS the fix the recorder
            // would have stamped. Beyond it the row stays honestly null.
            val fixes = altitudeIndexFor(id, dir)
            val withAltitude = SessionRecoveryPolicy.attachAltitude(
                rows = wall,
                timestamp = { it.timestampMonotonic },
                hasAltitude = { it.altitudeM != null },
                fixes = fixes,
                withAltitude = { tx, alt -> tx.copy(altitudeM = alt) }
            )

            allTx += withAltitude
            stitched += id
            for (tx in withAltitude) {
                val ms = tx.timestampMonotonic
                // Only a plausible epoch counts as the drive's start: a row stamped with uptime
                // would name the merged trip after 1970 and put its window before every other trip.
                if (ms in RecordTime.MIN_PLAUSIBLE_EPOCH_MS..RecordTime.MAX_PLAUSIBLE_EPOCH_MS) {
                    if (ms < earliestMs) earliestMs = ms
                }
            }
            // The EARLIEST fragment's metadata describes the drive: vehicle, adapter, protocol.
            if (firstMeta == null) {
                val jsonFile = File(dir, "$id.json")
                if (jsonFile.exists()) {
                    runCatching {
                        SessionJsonReader.readMetadata(jsonFile.reader())?.let { meta ->
                            firstMeta = meta
                            vehicleName = meta.vehicle
                            vehicleId = meta.vehicleId
                            adapterName = meta.adapter
                            protocolName = meta.protocol
                        }
                    }
                }
            }
            rawLogForFragment(id)?.let { rawFragments += id to it }
        }

        if (allTx.size < 2 || stitched.size < 2) {
            return@withContext MergeResult.Failed(
                "Merge failed - the selected trips hold no readable OBD rows " +
                    "(${allTx.size} row(s) in ${stitched.size} trip(s)). Nothing was changed.",
                review
            )
        }
        val sorted = allTx.sortedBy { it.timestampMonotonic }
        if (earliestMs == Long.MAX_VALUE) {
            // No row carried a plausible epoch (a legacy import stamped with uptime). The merged
            // trip still needs a name and a window, so fall back to the first row's own key rather
            // than inventing one.
            earliestMs = sorted.first().timestampMonotonic
        }

        val newId = UUID.randomUUID().toString().take(8)
        val displayStart = RecordTime.display(earliestMs)
        val mergedName = newName?.takeIf { it.isNotBlank() }
            ?: "Merged Run $displayStart (${stitched.size} trips, ${sorted.size} tx)"

        val metadata = RecordingMetadata(
            sessionId = newId,
            sessionName = mergedName,
            vehicle = vehicleName,
            vehicleId = vehicleId,
            profile = firstMeta?.profile ?: "India-Market 1.0 TSI",
            adapter = "$adapterName (merged ${stitched.size} trips)",
            protocol = protocolName,
            canBitrate = firstMeta?.canBitrate ?: "500 kbps",
            startTimeUtc = RecordTime.stamp(earliestMs)
        )

        val sampleList = SessionRecoveryPolicy.replaySamples(
            transactions = sorted,
            seed = SynchronizedSample(timestampUtc = "", timestampMonotonic = 0L),
            merge = { acc, tx -> mergeSample(acc, tx).copy(altitudeM = tx.altitudeM) }
        )

        // The fragments' raw ELM logs are folded into ONE file so the merged bundle stays
        // self-contained - a merged trip with no raw log is a trip that cannot be re-recovered or
        // audited, and the owner's rule for logs is that they are never lost.
        val foldedRaw = foldRawLogs(
            dest = File(recordingsDir, "merge_raw_$newId.txt"),
            sources = rawFragments
        )

        val saved: SavedRecording? = try {
            finalizeSession(
                metadata = metadata,
                txList = sorted,
                sampleList = sampleList,
                rawLogFile = foldedRaw,
                recovered = false,
                // The live GPS accumulator belongs to whatever drive is being recorded NOW, not to
                // the trips being stitched. Its window comes from the fragments' own rows only.
                liveGpsWindow = false
            )
        } catch (e: Exception) {
            // A merge that dies HERE has written nothing but the folded raw log: the fragments are
            // still on disk, still in Room, still not tombstoned. Say so instead of leaving the
            // owner guessing whether his trips survived.
            android.util.Log.e("RecordingManager", "merge finalize failed", e)
            runCatching { foldedRaw?.delete() }
            return@withContext MergeResult.Failed(
                "Merge failed while writing the trip: ${e.message ?: e.javaClass.simpleName}. " +
                    "The selected trips were left untouched.",
                review
            )
        }
        if (saved == null) {
            runCatching { foldedRaw?.delete() }
            return@withContext MergeResult.Failed(
                "Merge failed - nothing could be written from the selected trips.",
                review
            )
        }

        // TOMBSTONE FIRST. The merged trip exists, so from this instant every fragment is data
        // that is already inside a saved trip. Recording that before any deletion means a crash
        // half-way through the cleanup leaves duplicates the owner can delete, never a
        // resurrection that silently doubles the drive again.
        runCatching { mergeLedger.record(stitched, newId) }
            .onFailure { android.util.Log.e("RecordingManager", "merge ledger failed", it) }

        runCatching { foldedRaw?.delete() }

        // Now remove the fragments: session files, Room rows, journals, and the raw logs recovery
        // would otherwise rebuild them from ("no need to keep old fragmented trips for recovered
        // logs"). Their content lives on inside the merged bundle.
        for (oldId in stitched) {
            runCatching { File(recordingsDir, "session_$oldId").deleteRecursively() }
                .onFailure { android.util.Log.e("RecordingManager", "merge: session dir delete failed", it) }
            runCatching { tripRepository.deleteTrip(oldId) }
                .onFailure { android.util.Log.e("RecordingManager", "merge: room delete failed", it) }
            runCatching { journal.discard(oldId) }
            runCatching { File(rawLogsDir, "raw_log_$oldId.txt").takeIf { it.exists() }?.delete() }
                .onFailure { android.util.Log.e("RecordingManager", "merge: raw log delete failed", it) }
        }

        loadSavedRecordings()
        MergeResult.Merged(saved, review)
    }

    /**
     * The raw ELM log of a fragment, wherever it lives: the original in `files/raw_logs` first
     * (it is the one that kept growing while the drive ran), then the copy finalize put in the
     * session bundle. Null when the fragment has neither - an imported ZIP trip usually does not.
     */
    private fun rawLogForFragment(sessionId: String): File? {
        val live = File(rawLogsDir, "raw_log_$sessionId.txt")
        if (live.isFile && live.length() > 0L) return live
        val bundled = File(File(recordingsDir, "session_$sessionId"), "${sessionId}_raw.txt")
        return if (bundled.isFile && bundled.length() > 0L) bundled else null
    }

    /**
     * Streams several fragments' raw logs into one file, each under a header naming the session it
     * came from. Streamed, never `readText()`: these are the largest files in the app and the
     * 2026-09-20 OOM was exactly a whole drive materialised as a String.
     */
    private fun foldRawLogs(dest: File, sources: List<Pair<String, File>>): File? {
        if (sources.isEmpty()) return null
        return runCatching {
            dest.parentFile?.mkdirs()
            java.io.BufferedOutputStream(java.io.FileOutputStream(dest)).use { out ->
                out.write(
                    ("--- MERGED RAW OBD-II LOG | ${sources.size} fragments folded into one trip | " +
                        "built ${RecordTime.stamp()} (${RecordTime.zoneLabel()} " +
                        "${RecordTime.offsetLabel()}) ---\n").toByteArray()
                )
                for ((id, src) in sources) {
                    out.write(
                        ("--- FRAGMENT session $id | ${src.name} | ${src.length()} bytes | " +
                            "last modified ${RecordTime.logStamp(src.lastModified())} ---\n").toByteArray()
                    )
                    src.inputStream().use { it.copyTo(out) }
                    out.write("\n".toByteArray())
                }
            }
            dest
        }.onFailure {
            android.util.Log.e("RecordingManager", "could not fold raw logs for a merge", it)
            runCatching { dest.delete() }
        }.getOrNull()
    }

    /**
     * (instant, altitude) pairs a fragment already recorded, from its wide sample rows and from
     * its Room rows. Projected, not full entities - see `altitudeRowsForTrip`.
     */
    private suspend fun altitudeIndexFor(sessionId: String, dir: File): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        runCatching { CsvExporter.readSamplesFromCsv(File(dir, "${sessionId}_samples.csv")) }
            .getOrDefault(emptyList())
            .forEach { smp ->
                val alt = smp.altitudeM ?: return@forEach
                val ms = RecordTime.parseMillis(smp.timestampUtc) ?: return@forEach
                out += ms to alt
            }
        tripRepository.altitudeRowsForTrip(sessionId).forEach { row ->
            val alt = row.altitudeM ?: return@forEach
            val ms = RecordTime.instantOf(row.timestamp, row.timestampUtc) ?: return@forEach
            if (RecordTime.isPlausibleEpoch(ms)) out += ms to alt
        }
        return out
    }

    /**
     * Drops tombstones whose merged trip no longer exists, so deleting a trip never leaves an id
     * permanently forbidden from recovery.
     */
    private fun pruneMergeLedger() {
        runCatching {
            val live = _savedRecordings.value.map { it.metadata.sessionId }.toSet()
            mergeLedger.retainOnly(live)
        }
    }

    /**
     * Imports a single ZIP archive from a content URI.
     */
    suspend fun importZipFile(uri: android.net.Uri): ZipImportResult {
        val result = ZipImporter.importTripZip(context, uri, recordingsDir, tripRepository)
        if (result.success) {
            loadSavedRecordings()
            // A RESTORED trip is a recovered trip: car-pool rides logged standalone while it was
            // missing must re-check their time windows against it (owner's v2 acceptance: links
            // appear "when a recovered trip covers the ride"). finalizeSession does this for live
            // sessions; a Drive/ZIP restore must do exactly the same or the restore looks broken.
            runCatching { relinkCarpoolEntries() }
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
        if (results.any { it.success }) runCatching { relinkCarpoolEntries() }
        return results
    }
}


