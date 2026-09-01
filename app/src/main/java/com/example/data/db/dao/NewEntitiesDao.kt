package com.example.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.db.entities.VehicleEntity
import com.example.data.db.entities.ProtocolTestResultEntity
import com.example.data.db.entities.DtcRecordEntity
import com.example.data.db.entities.ServiceRecordEntity
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
}
