import re

with open("app/src/main/java/com/example/data/db/entities/NewEntities.kt", "r") as f:
    text = f.read()

scan_session_code = """
@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val vehicleId: String?,
    val startedAt: Long,
    val completedAt: Long?,
    val connectionType: String,
    val adapterName: String,
    val adapterAddress: String,
    val protocol: String?,
    val ecuCount: Int = 0,
    val pidCount: Int = 0,
    val dtcCount: Int = 0,
    val readinessAvailable: Boolean = false,
    val completionStatus: String, // RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val rawEvidenceReference: String?
)

@Entity(tableName = "ecu_topologies")
data class EcuTopologyEntity(
    @PrimaryKey val id: String,
    val vehicleId: String?,
    val address: String, // e.g. "7E8", "18DA10F1"
    val name: String,
    val type: String, // ENGINE, TRANSMISSION, ABS, BCM
    val protocol: String?,
    val lastSeen: Long,
    val responseTime: Long,
    val supportedServices: String, // Comma separated
    val supportedPids: String, // Comma separated
    val dtcCount: Int,
    val confidence: String, // OBSERVED, INFERRED
    val rawEvidence: String?
)

@Entity(tableName = "pid_capabilities")
data class PidCapabilityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val vehicleId: String?,
    val ecuAddress: String,
    val pid: String, // e.g. "010C"
    val supported: Boolean,
    val lastVerified: Long,
    val responseLatency: Long,
    val failureCount: Int,
    val confidence: String // OBSERVED, INFERRED
)
"""

text = text + "\n" + scan_session_code

with open("app/src/main/java/com/example/data/db/entities/NewEntities.kt", "w") as f:
    f.write(text)
