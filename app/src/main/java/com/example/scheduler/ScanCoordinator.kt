package com.example.scheduler

import com.example.bluetooth.ElmTransport
import com.example.data.db.entities.ScanSessionEntity
import com.example.data.db.entities.EcuTopologyEntity
import com.example.data.db.entities.PidCapabilityEntity
import com.example.data.db.entities.DtcRecordEntity
import com.example.data.db.AppDatabase
import com.example.protocol.SafetyValidator
import com.example.protocol.DtcDecoder
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
            // 1. Adapter Init & Protocol Detect (Assume transport is already connected, but we reset and detect)
            _progress.value = ScanProgress(ScanPhase.INIT_ADAPTER, "Initializing ELM327...", 0.05f)
            if (!safeCommand("ATZ", 2000)) return failScan("Reset failed")
            delay(500)
            if (!safeCommand("ATE0", 1000)) return failScan("Echo off failed")
            if (!safeCommand("ATH1", 1000)) return failScan("Headers on failed")
            if (!safeCommand("ATL0", 1000)) return failScan("Linefeeds off failed")

            _progress.value = ScanProgress(ScanPhase.PROTOCOL_DETECT, "Detecting Protocol...", 0.1f)
            val protocolResp = transport.sendCommand("0100", 3000)
            if (protocolResp.status != com.example.model.ResponseStatus.OK) {
                return failScan("Failed to connect to ECU / Protocol detect failed")
            }
            
            val dpnResp = transport.sendCommand("ATDPN", 1000)
            protocol = dpnResp.lines.firstOrNull() ?: "UNKNOWN"

            if (isCancelled) return cancelScan()

            // 2. ECU Discovery
            _progress.value = ScanProgress(ScanPhase.ECU_DISCOVERY, "Discovering ECUs...", 0.2f)
            // Simplified ECU discovery logic
            val capabilityManager = PidCapabilityManager()
            val ecuDiscovery = EcuDiscoveryManager(capabilityManager)
            val report = ecuDiscovery.runDiscovery(transport)
            for (discovered in report.detectedEcus) {
                val ecuAddress = discovered.rxCanId
                ecus.add(
                    EcuTopologyEntity(
                        id = UUID.randomUUID().toString(),
                        vehicleId = vehicleId,
                        address = ecuAddress,
                        name = if (ecuAddress == "7E8") "Engine" else if (ecuAddress == "7E9" || ecuAddress == "7EA") "Transmission" else "Unknown",
                        type = if (ecuAddress == "7E8") "ENGINE" else if (ecuAddress == "7E9" || ecuAddress == "7EA") "TRANSMISSION" else "OTHER",
                        protocol = protocol,
                        lastSeen = System.currentTimeMillis(),
                        responseTime = 0,
                        supportedServices = "01,03,07,09",
                        supportedPids = "",
                        dtcCount = 0,
                        confidence = "OBSERVED",
                        rawEvidence = null
                    )
                )
            }

            if (isCancelled) return cancelScan()

            // 3. PID Discovery
            _progress.value = ScanProgress(ScanPhase.PID_DISCOVERY, "Checking Supported PIDs...", 0.3f)
            // Save Pid Capabilities
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
            _progress.value = ScanProgress(ScanPhase.READ_VIN, "Reading VIN...", 0.4f)
            val vinResp = transport.sendCommand("0902", 3000)
            if (vinResp.status == com.example.model.ResponseStatus.OK) {
                val hexString = vinResp.lines.joinToString("") { it.replace(" ", "") }
                try {
                    val ascii = StringBuilder()
                    var i = 0
                    while (i < hexString.length - 1) {
                        val num = hexString.substring(i, i + 2).toIntOrNull(16)
                        if (num != null && num in 32..126) ascii.append(num.toChar())
                        i += 2
                    }
                    vin = Regex("[A-HJ-NPR-Z0-9]{17}").find(ascii.toString())?.value
                } catch (e: Exception) {
                    // Ignore
                }
            }

            if (isCancelled) return cancelScan()

            // 5. Readiness
            _progress.value = ScanProgress(ScanPhase.READ_READINESS, "Checking Readiness Monitors...", 0.5f)
            safeCommand("0101", 2000)

            if (isCancelled) return cancelScan()

            // 6. Current & Pending DTCs
            _progress.value = ScanProgress(ScanPhase.READ_DTCS, "Scanning for Faults (DTCs)...", 0.6f)
            val dtcDecoder = DtcDecoder
            
            // Mode 03 (Current)
            val mode03 = transport.sendCommand("03", 3000)
            if (mode03.status == com.example.model.ResponseStatus.OK) {
                val codes = dtcDecoder.extractDtcs(mode03.lines.joinToString(""))
                codes.forEach { code ->
                    dtcs.add(DtcRecordEntity(vehicleId = vehicleId, tripId = sessionId, timestamp = System.currentTimeMillis(), code = code, description = "Active Fault", status = "ACTIVE"))
                }
            }

            // Mode 07 (Pending)
            val mode07 = transport.sendCommand("07", 3000)
            if (mode07.status == com.example.model.ResponseStatus.OK) {
                val codes = dtcDecoder.extractDtcs(mode07.lines.joinToString(""))
                codes.forEach { code ->
                    dtcs.add(DtcRecordEntity(vehicleId = vehicleId, tripId = sessionId, timestamp = System.currentTimeMillis(), code = code, description = "Pending Fault", status = "PENDING"))
                }
            }
            
            // Mode 0A (Permanent)
            val mode0A = transport.sendCommand("0A", 3000)
            if (mode0A.status == com.example.model.ResponseStatus.OK) {
                val codes = dtcDecoder.extractDtcs(mode0A.lines.joinToString(""))
                codes.forEach { code ->
                    dtcs.add(DtcRecordEntity(vehicleId = vehicleId, tripId = sessionId, timestamp = System.currentTimeMillis(), code = code, description = "Permanent Fault", status = "PERMANENT"))
                }
            }

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
                adapterAddress = "00:00:00:00:00:00",
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
            adapterAddress = "00:00:00:00:00:00",
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
