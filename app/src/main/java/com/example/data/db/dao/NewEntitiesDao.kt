package com.example.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.db.entities.VehicleEntity
import com.example.data.db.entities.ProtocolTestResultEntity
import com.example.data.db.entities.DtcRecordEntity
import com.example.data.db.entities.ServiceRecordEntity
import com.example.data.db.entities.ScanSessionEntity
import com.example.data.db.entities.EcuTopologyEntity
import com.example.data.db.entities.PidCapabilityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NewEntitiesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicle(vehicle: VehicleEntity)

    @Query("SELECT * FROM vehicles")
    fun getAllVehicles(): Flow<List<VehicleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProtocolTestResult(result: ProtocolTestResultEntity)

    @Query("SELECT * FROM protocol_test_results ORDER BY timestamp DESC")
    fun getProtocolTestResults(): Flow<List<ProtocolTestResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDtcRecord(record: DtcRecordEntity)

    @Query("SELECT * FROM dtc_records ORDER BY timestamp DESC")
    fun getDtcRecords(): Flow<List<DtcRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServiceRecord(record: ServiceRecordEntity)

    @Query("SELECT * FROM service_records WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    fun getServiceRecords(vehicleId: String): Flow<List<ServiceRecordEntity>>

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
}
