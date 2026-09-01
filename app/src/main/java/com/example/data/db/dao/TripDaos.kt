package com.example.data.db.dao

import androidx.room.*
import com.example.data.db.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTimestamp DESC")
    fun getAllTripsFlow(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startTimestamp DESC")
    suspend fun getAllTrips(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    suspend fun getTripById(tripId: String): TripEntity?

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    fun getTripByIdFlow(tripId: String): Flow<TripEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: String)

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun getTripCount(): Int
}

@Dao
interface TelemetrySampleDao {
    @Query("SELECT * FROM telemetry_samples WHERE tripId = :tripId ORDER BY sequence ASC")
    fun getSamplesForTripFlow(tripId: String): Flow<List<TelemetrySampleEntity>>

    @Query("SELECT * FROM telemetry_samples WHERE tripId = :tripId ORDER BY sequence ASC")
    suspend fun getSamplesForTrip(tripId: String): List<TelemetrySampleEntity>

    @Query("SELECT * FROM telemetry_samples WHERE tripId = :tripId AND pid = :pid ORDER BY sequence ASC")
    suspend fun getSamplesForPid(tripId: String, pid: String): List<TelemetrySampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<TelemetrySampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: TelemetrySampleEntity)

    @Query("DELETE FROM telemetry_samples WHERE tripId = :tripId")
    suspend fun deleteSamplesForTrip(tripId: String)

    @Query("DELETE FROM telemetry_samples")
    suspend fun deleteAllSamples()

    @Query("SELECT COUNT(*) FROM telemetry_samples")
    suspend fun getTotalSampleCount(): Int
}

@Dao
interface RawLogDao {
    @Query("SELECT * FROM raw_logs WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun getRawLogsForTripFlow(tripId: String): Flow<List<RawLogEntity>>

    @Query("SELECT * FROM raw_logs WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getRawLogsForTrip(tripId: String): List<RawLogEntity>

    @Query("SELECT * FROM raw_logs WHERE tripId = :tripId AND category = :category ORDER BY timestamp ASC")
    suspend fun getRawLogsByCategory(tripId: String, category: String): List<RawLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawLogs(logs: List<RawLogEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawLog(log: RawLogEntity)

    @Query("DELETE FROM raw_logs WHERE tripId = :tripId")
    suspend fun deleteRawLogsForTrip(tripId: String)

    @Query("DELETE FROM raw_logs")
    suspend fun deleteAllRawLogs()

    @Query("SELECT COUNT(*) FROM raw_logs")
    suspend fun getTotalRawLogCount(): Int
}

@Dao
interface DiagnosticEventDao {
    @Query("SELECT * FROM diagnostic_events WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun getEventsForTripFlow(tripId: String): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT * FROM diagnostic_events WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getEventsForTrip(tripId: String): List<DiagnosticEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<DiagnosticEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: DiagnosticEventEntity)

    @Query("DELETE FROM diagnostic_events WHERE tripId = :tripId")
    suspend fun deleteEventsForTrip(tripId: String)

    @Query("DELETE FROM diagnostic_events")
    suspend fun deleteAllEvents()
}

@Dao
interface AiAnalysisDao {
    @Query("SELECT * FROM ai_analyses WHERE tripId = :tripId LIMIT 1")
    fun getAnalysisForTripFlow(tripId: String): Flow<AiAnalysisEntity?>

    @Query("SELECT * FROM ai_analyses WHERE tripId = :tripId LIMIT 1")
    suspend fun getAnalysisForTrip(tripId: String): AiAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnalysis(analysis: AiAnalysisEntity)

    @Query("DELETE FROM ai_analyses WHERE tripId = :tripId")
    suspend fun deleteAnalysisForTrip(tripId: String)

    @Query("DELETE FROM ai_analyses")
    suspend fun deleteAllAnalyses()
}
