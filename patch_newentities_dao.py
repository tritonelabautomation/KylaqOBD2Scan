import re

with open("app/src/main/java/com/example/data/db/dao/NewEntitiesDao.kt", "r") as f:
    text = f.read()

import_code = """
import com.example.data.db.entities.ScanSessionEntity
import com.example.data.db.entities.EcuTopologyEntity
import com.example.data.db.entities.PidCapabilityEntity
"""

text = text.replace("import com.example.data.db.entities.ServiceRecordEntity", "import com.example.data.db.entities.ServiceRecordEntity\n" + import_code)

dao_code = """
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanSession(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    fun getScanSessions(): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM scan_sessions WHERE id = :sessionId")
    suspend fun getScanSession(sessionId: String): ScanSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEcuTopology(ecu: EcuTopologyEntity)

    @Query("SELECT * FROM ecu_topologies WHERE vehicleId = :vehicleId")
    fun getEcuTopologies(vehicleId: String): Flow<List<EcuTopologyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPidCapability(capability: PidCapabilityEntity)

    @Query("SELECT * FROM pid_capabilities WHERE vehicleId = :vehicleId")
    fun getPidCapabilities(vehicleId: String): Flow<List<PidCapabilityEntity>>
"""

text = text + "\n" + dao_code

with open("app/src/main/java/com/example/data/db/dao/NewEntitiesDao.kt", "w") as f:
    f.write(text)
