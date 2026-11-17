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

    /**
     * AUDIT MEM-02 (2026-11-12): the "recent trips" screens asked for 50 and 300 trips but went
     * through `getAllTrips().take(n)`, which loads *every* trip ever recorded into memory and
     * then throws most of them away. On an owner with 500 saved trips that is 500 rows read and
     * 500 objects allocated to display 50. The LIMIT now happens in SQLite, so the row count
     * that reaches the heap is the row count that reaches the screen.
     */
    @Query("SELECT * FROM trips ORDER BY startTimestamp DESC LIMIT :limit")
    suspend fun getRecentTrips(limit: Int): List<TripEntity>

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
    @Query(
        "SELECT timestamp, pid, numericValue FROM telemetry_samples " +
            "WHERE timestamp >= :ts AND pid IN (:pids) ORDER BY timestamp ASC"
    )
    suspend fun samplesSince(ts: Long, pids: List<String>): List<SampleRow>

    @Query(
        "SELECT timestamp, pid, numericValue FROM telemetry_samples " +
            "WHERE tripId = :tripId AND pid IN (:pids) ORDER BY timestamp ASC"
    )
    suspend fun samplesForTripPids(tripId: String, pids: List<String>): List<SampleRow>

    /** Newest stored value of one PID: the odometer/level readout when no session is live. */
    @Query(
        "SELECT numericValue FROM telemetry_samples WHERE pid = :pid AND numericValue IS NOT NULL " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun latestNumericFor(pid: String): Double?

    @Query("SELECT * FROM telemetry_samples WHERE tripId = :tripId ORDER BY sequence ASC")
    fun getSamplesForTripFlow(tripId: String): Flow<List<TelemetrySampleEntity>>

    @Query("SELECT * FROM telemetry_samples WHERE tripId = :tripId ORDER BY sequence ASC")
    suspend fun getSamplesForTrip(tripId: String): List<TelemetrySampleEntity>

    @Query("SELECT COUNT(*) FROM telemetry_samples WHERE tripId = :tripId")
    suspend fun getSampleCountForTrip(tripId: String): Int

    @Query(
        "SELECT * FROM telemetry_samples WHERE tripId = :tripId AND (sequence % :stride) = 0 " +
            "ORDER BY sequence ASC"
    )
    suspend fun getSamplesForTripStrided(tripId: String, stride: Int): List<TelemetrySampleEntity>

    @Query("SELECT * FROM telemetry_samples WHERE tripId = :tripId AND pid = :pid ORDER BY sequence ASC")
    suspend fun getSamplesForPid(tripId: String, pid: String): List<TelemetrySampleEntity>

    /** Trend projection: only the PIDs the cross-trip trend charts need, for several trips. */
    @Query(
        "SELECT * FROM telemetry_samples WHERE tripId IN (:tripIds) AND pid IN (:pids) " +
            "ORDER BY tripId ASC, sequence ASC"
    )
    suspend fun getSamplesForTrips(tripIds: List<String>, pids: List<String>): List<TelemetrySampleEntity>

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

    /**
     * AUDIT MEM-02 (2026-11-12): the trip-detail diagnostic console used to load the trip's
     * *entire* raw log - one row per OBD frame, tens of thousands for a long drive - to render
     * a scrolling list that only ever shows a screenful. The newest [limit] rows are enough for
     * that view, and nothing is lost from disk: the full log stays in the table and the
     * exporter and recovery paths still read it in full.
     *
     * DESC + LIMIT selects the newest rows efficiently; the caller re-sorts ascending.
     */
    @Query("SELECT * FROM raw_logs WHERE tripId = :tripId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRawLogsForTrip(tripId: String, limit: Int): List<RawLogEntity>

    @Query("SELECT COUNT(*) FROM raw_logs WHERE tripId = :tripId")
    suspend fun getRawLogCountForTrip(tripId: String): Int

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

/** Projection for since-refuel aggregation: only the columns the integrator reads. */
data class SampleRow(val timestamp: Long, val pid: String, val numericValue: Double?)

@Dao
interface RefuelEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRefuelEvent(e: RefuelEventEntity)

    @Query("SELECT * FROM refuel_events ORDER BY tsEndMs DESC")
    fun refuelEventsFlow(): Flow<List<RefuelEventEntity>>

    @Query("SELECT * FROM refuel_events ORDER BY tsEndMs DESC")
    suspend fun refuelEvents(): List<RefuelEventEntity>

    @Query("UPDATE refuel_events SET calibratedPumpL = :pumpL WHERE idMs = :idMs")
    suspend fun calibrate(idMs: Long, pumpL: Double)

    /** Pump litres a receipt already matched to this event: a rewrite must never drop them. */
    @Query("SELECT calibratedPumpL FROM refuel_events WHERE idMs = :idMs")
    suspend fun calibratedPumpFor(idMs: Long): Double?
}
