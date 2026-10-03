package com.example.scheduler

import com.example.bluetooth.ElmTransport
import com.example.model.ResponseStatus
import com.example.data.db.entities.ScanSessionEntity
import com.example.data.db.entities.EcuTopologyEntity
import com.example.data.db.entities.PidCapabilityEntity
import com.example.data.db.entities.DtcRecordEntity
import com.example.data.db.AppDatabase
import com.example.protocol.SafetyValidator
import com.example.protocol.DtcDecoder
import com.example.protocol.IsoTpParser
import com.example.protocol.VinAuthority
import com.example.protocol.VinSelectionResult
import com.example.discovery.EcuDiscoveryManager
import com.example.discovery.PidCapabilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import java.util.UUID

enum class ScanPhase {
    IDLE,
    INIT_ADAPTER,
    PROTOCOL_DETECT,
    ECU_DISCOVERY,
    PID_DISCOVERY,
    READ_VIN,
    READ_READINESS,
    READ_DTCS,
    READ_FREEZE_FRAME,
    LIVE_PIDS,
    SUMMARY,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ScanProgress(
    val phase: ScanPhase,
    val message: String,
    val progress: Float
)

class ScanCoordinator(
    private val transport: ElmTransport,
    private val database: AppDatabase,
    private val vehicleId: String? = null
) {
    /**
     * Captures the adapter's MAC address at coordinator construction time.
     * Falls back to the synthetic "SIM:..." or "UNKNOWN" address if the transport
     * (e.g. an older or third-party one) does not implement the property.
     */
    private val adapterAddress: String = transport.deviceAddress ?: "UNKNOWN"
    private val _progress = MutableStateFlow(ScanProgress(ScanPhase.IDLE, "Ready", 0f))
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    private var sessionId: String = UUID.randomUUID().toString()
    private var startTime: Long = 0
    private var isCancelled = false

    // Collected Data
    private var protocol: String? = null
    private var vin: String? = null
    private val ecus = mutableListOf<EcuTopologyEntity>()
    private val pidCapabilities = mutableListOf<PidCapabilityEntity>()
    private val dtcs = mutableListOf<DtcRecordEntity>()
    private var errorCount = 0

    fun cancel() {
        isCancelled = true
        _progress.value = ScanProgress(ScanPhase.CANCELLED, "Cancelling scan...", 1f)
    }

    suspend fun runFullScan(): ScanSessionEntity? {
        startTime = System.currentTimeMillis()
        sessionId = UUID.randomUUID().toString()
        isCancelled = false
        errorCount = 0
        ecus.clear()
        pidCapabilities.clear()
        dtcs.clear()

        try {
            // 1. Adapter Init & Protocol Detect via DiagnosticSession
            _progress.value = ScanProgress(ScanPhase.INIT_ADAPTER, "Initializing ELM327 for Škoda Kylaq...", 0.05f)
            val initResult = com.example.protocol.DiagnosticSession.initialize(transport)
            if (!initResult.isSuccess) {
                return failScan("Adapter initialization failed")
            }

            _progress.value = ScanProgress(ScanPhase.PROTOCOL_DETECT, "Verifying ISO 15765-4 protocol...", 0.1f)
            val verifyResult = com.example.protocol.DiagnosticSession.verifyProtocol(
                transport,
                com.example.model.KylaqProtocolProfile.DEFAULT_CAN_PROTOCOL
            )

            val activeProto = if (verifyResult.isVerified) {
                com.example.model.KylaqProtocolProfile.DEFAULT_CAN_PROTOCOL
            } else {
                val fallbackReport = com.example.protocol.DiagnosticSession.attemptProtocolFallback(transport)
                fallbackReport.verifiedProtocol ?: return failScan("Protocol verification failed: No ECU response")
            }
            protocol = activeProto.displayName

            if (isCancelled) return cancelScan()

            // 2. ECU Discovery
            _progress.value = ScanProgress(ScanPhase.ECU_DISCOVERY, "Discovering ECUs...", 0.2f)
            val capabilityManager = PidCapabilityManager()
            val ecuDiscovery = EcuDiscoveryManager(capabilityManager)
            val report = ecuDiscovery.runDiscovery(transport)

            // Evidence-based ECU registration (Rule 2: Never guess ECU roles from CAN IDs)
            for (discovered in report.detectedEcus) {
                val ecuAddress = discovered.rxCanId
                val ecuRole = discovered.ecuRole
                val ecuType = when {
                    ecuRole.contains("Engine", ignoreCase = true) -> "ENGINE"
                    ecuRole.contains("Transmission", ignoreCase = true) -> "TRANSMISSION"
                    ecuRole.contains("Brake", ignoreCase = true) || ecuRole.contains("ABS", ignoreCase = true) -> "BRAKE"
                    ecuRole.contains("Body", ignoreCase = true) -> "BODY"
                    ecuRole.contains("Airbag", ignoreCase = true) -> "AIRBAG"
                    else -> "OTHER"
                }

                ecus.add(
                    EcuTopologyEntity(
                        id = UUID.randomUUID().toString(),
                        vehicleId = vehicleId,
                        address = ecuAddress,
                        name = ecuRole,
                        type = ecuType,
                        protocol = protocol,
                        lastSeen = System.currentTimeMillis(),
                        responseTime = discovered.averageLatencyMs,
                        supportedServices = discovered.supportedServices.joinToString(","),
                        supportedPids = discovered.supportedPids.joinToString(","),
                        dtcCount = 0,
                        confidence = if (ecuRole.contains("(")) "CONFIRMED" else "OBSERVED",
                        rawEvidence = discovered.ecuName ?: discovered.calibrationId
                    )
                )

                if (vin == null && !discovered.vin.isNullOrBlank()) {
                    vin = discovered.vin
                }
            }

            if (isCancelled) return cancelScan()

            // 3. PID Discovery
            _progress.value = ScanProgress(ScanPhase.PID_DISCOVERY, "Checking Supported PIDs...", 0.3f)
            for (discovered in report.detectedEcus) {
                for (pid in discovered.supportedPids) {
                    pidCapabilities.add(PidCapabilityEntity(
                        vehicleId = vehicleId,
                        ecuAddress = discovered.rxCanId,
                        pid = pid,
                        supported = true,
                        lastVerified = System.currentTimeMillis(),
                        responseLatency = discovered.averageLatencyMs,
                        failureCount = 0,
                        confidence = "OBSERVED"
                    ))
                }
            }
            
            if (isCancelled) return cancelScan()

            // 4. VIN
            // VIN ECU Authority Model: Only 7E8 (Engine) and 7E1 (Transmission)
            // are authoritative for VIN. Other ECUs cannot provide VIN.
            _progress.value = ScanProgress(ScanPhase.READ_VIN, "Reading VIN...", 0.4f)
            val vinResp = transport.sendCommand("0902", 3000)
            if (vinResp.status == ResponseStatus.OK && vinResp.lines.isNotEmpty()) {
                try {
                    val allMessages = IsoTpParser.reassembleLines(vinResp.lines)
                    val candidates = VinAuthority.collectVinCandidates(allMessages)
                    val result = VinAuthority.selectVinByAuthority(candidates)
                    
                    when (result) {
                        is VinSelectionResult.Success -> {
                            vin = result.vin
                        }
                        is VinSelectionResult.Ambiguous -> {
                            vin = "VIN Ambiguous"
                        }
                        is VinSelectionResult.Unavailable -> {
                            // VIN not available or not from authoritative ECU
                        }
                    }
                } catch (e: Exception) {
                    // Ignore parsing errors
                }
            }
            if (isCancelled) return cancelScan()

            // 5. Readiness
            _progress.value = ScanProgress(ScanPhase.READ_READINESS, "Checking Readiness Monitors...", 0.5f)
            safeCommand("0101", 2000)

            if (isCancelled) return cancelScan()

            // 6. Current & Pending DTCs
            _progress.value = ScanProgress(ScanPhase.READ_DTCS, "Scanning for Faults (DTCs)...", 0.6f)

            // FIX (DTC scan always reported zero faults): the raw ELM327 lines were glued
            // together ("7E803430104") and handed straight to DtcDecoder, which requires a
            // positive ack at offset 0 — so nothing was ever decoded. Responses are now
            // reassembled per CAN id with IsoTpParser first, exactly like MainViewModel does.
            collectDtcs(transport, "03", 0x03, "Active Fault", "ACTIVE")
            collectDtcs(transport, "07", 0x07, "Pending Fault", "PENDING")
            collectDtcs(transport, "0A", 0x0A, "Permanent Fault", "PERMANENT")

            if (isCancelled) return cancelScan()

            // 7. Freeze Frame
            _progress.value = ScanProgress(ScanPhase.READ_FREEZE_FRAME, "Checking Freeze Frames...", 0.8f)
            safeCommand("020200", 2000) // Checking PID 02 for freeze frame

            if (isCancelled) return cancelScan()

            // 8. Summary & Persistence
            _progress.value = ScanProgress(ScanPhase.SUMMARY, "Saving Scan Session...", 0.95f)
            
            val session = ScanSessionEntity(
                id = sessionId,
                vehicleId = vehicleId,
                startedAt = startTime,
                completedAt = System.currentTimeMillis(),
                connectionType = "BLUETOOTH",
                adapterName = "ELM327",
                adapterAddress = adapterAddress, // FIX CR-1: real BT MAC, was hardcoded zero
                protocol = protocol,
                ecuCount = ecus.size,
                pidCount = pidCapabilities.size,
                dtcCount = dtcs.size,
                readinessAvailable = true,
                completionStatus = "COMPLETED",
                errorCount = errorCount,
                warningCount = 0,
                rawEvidenceReference = null
            )

            // Save to DB
            val dao = database.newEntitiesDao()
            dao.insertScanSession(session)
            ecus.forEach { dao.insertEcuTopology(it) }
            pidCapabilities.forEach { dao.insertPidCapability(it) }
            dtcs.forEach { dao.insertDtcRecord(it) }

            _progress.value = ScanProgress(ScanPhase.COMPLETED, "Scan Complete", 1.0f)
            return session

        } catch (e: Exception) {
            return failScan("Exception during scan: ${e.message}")
        }
    }

    private suspend fun safeCommand(cmd: String, timeoutMs: Long): Boolean {
        if (SafetyValidator.validateCommand(cmd) !is com.example.protocol.ValidationResult.Allowed) return false
        val res = transport.sendCommand(cmd, timeoutMs)
        if (res.status != com.example.model.ResponseStatus.OK) errorCount++
        return res.status == com.example.model.ResponseStatus.OK
    }

    /**
     * Reads one DTC mode over the bus and stores the decoded codes.
     *
     * Each ECU answer is reassembled with [IsoTpParser] (multi-ECU and multi-frame safe)
     * before decoding, and the mode is passed through so [DtcDecoder] can require the
     * matching positive ack (43 / 47 / 4A).
     */
    private suspend fun collectDtcs(
        transport: ElmTransport,
        command: String,
        mode: Int,
        description: String,
        status: String
    ) {
        val resp = transport.sendCommand(command, 3000)
        if (resp.status != ResponseStatus.OK) {
            errorCount++
            return
        }
        val messages = try {
            IsoTpParser.reassembleLines(resp.lines)
        } catch (_: Exception) {
            emptyList()
        }
        val codes = LinkedHashSet<String>()
        for (msg in messages) {
            if (msg.isMalformed || msg.reconstructedBytes.isEmpty()) continue
            codes.addAll(DtcDecoder.extractDtcs(msg.reconstructedPayloadHex, mode = mode))
        }
        if (messages.isEmpty()) {
            // No ISO-TP frames recovered — fall back to the raw lines so adapters that answer
            // without headers still produce a result. Line by line, never glued together:
            // two ECUs answering ("7E803430104" + "7E903430108") would otherwise be decoded
            // as one long payload and invent DTCs out of the second frame's CAN id.
            resp.lines.forEach { line ->
                codes.addAll(DtcDecoder.extractDtcs(line, mode = mode))
            }
        }
        val timestamp = System.currentTimeMillis()
        codes.forEach { code ->
            dtcs.add(
                DtcRecordEntity(
                    vehicleId = vehicleId,
                    tripId = sessionId,
                    timestamp = timestamp,
                    code = code,
                    description = description,
                    status = status
                )
            )
        }
    }

    private suspend fun failScan(reason: String): ScanSessionEntity {
        _progress.value = ScanProgress(ScanPhase.FAILED, "Scan failed: $reason", 1f)
        val session = createPartialSession("FAILED")
        database.newEntitiesDao().insertScanSession(session)
        return session
    }

    private suspend fun cancelScan(): ScanSessionEntity {
        val session = createPartialSession("CANCELLED")
        database.newEntitiesDao().insertScanSession(session)
        return session
    }

    private fun createPartialSession(status: String): ScanSessionEntity {
        return ScanSessionEntity(
            id = sessionId,
            vehicleId = vehicleId,
            startedAt = startTime,
            completedAt = System.currentTimeMillis(),
            connectionType = "BLUETOOTH",
            adapterName = "ELM327",
            adapterAddress = adapterAddress, // FIX CR-1: real BT MAC, was hardcoded zero
            protocol = protocol,
            ecuCount = ecus.size,
            pidCount = pidCapabilities.size,
            dtcCount = dtcs.size,
            readinessAvailable = false,
            completionStatus = status,
            errorCount = errorCount,
            warningCount = 0,
            rawEvidenceReference = null
        )
    }
}


