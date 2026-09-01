package com.example.data.db

import android.content.Context
import com.example.ai.AiAnalysisEngine
import com.example.ai.CarDoctorReport
import com.example.ai.DoctorObservation
import com.example.ai.RuleBasedAnalysisEngine
import com.example.data.db.entities.*
import com.example.model.TransactionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TripRepository(context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val tripDao = db.tripDao()
    private val sampleDao = db.telemetrySampleDao()
    private val rawLogDao = db.rawLogDao()
    private val eventDao = db.diagnosticEventDao()
    private val aiAnalysisDao = db.aiAnalysisDao()
    private val newEntitiesDao = db.newEntitiesDao()

    private val ruleBasedEngine: AiAnalysisEngine = RuleBasedAnalysisEngine()

    val allTripsFlow: Flow<List<TripEntity>> = tripDao.getAllTripsFlow()
    val allVehiclesFlow: Flow<List<VehicleEntity>> = newEntitiesDao.getAllVehicles()
    val protocolTestResultsFlow: Flow<List<ProtocolTestResultEntity>> = newEntitiesDao.getProtocolTestResults()
    val dtcRecordsFlow: Flow<List<DtcRecordEntity>> = newEntitiesDao.getDtcRecords()

    suspend fun insertVehicle(vehicle: VehicleEntity) = withContext(Dispatchers.IO) {
        newEntitiesDao.insertVehicle(vehicle)
    }

    suspend fun insertProtocolTestResult(result: ProtocolTestResultEntity) = withContext(Dispatchers.IO) {
        newEntitiesDao.insertProtocolTestResult(result)
    }

    suspend fun insertDtcRecord(record: DtcRecordEntity) = withContext(Dispatchers.IO) {
        newEntitiesDao.insertDtcRecord(record)
    }

    suspend fun insertServiceRecord(record: ServiceRecordEntity) = withContext(Dispatchers.IO) {
        newEntitiesDao.insertServiceRecord(record)
    }
    
    fun getServiceRecordsForVehicle(vehicleId: String): Flow<List<ServiceRecordEntity>> = newEntitiesDao.getServiceRecords(vehicleId)

    fun getTripByIdFlow(tripId: String): Flow<TripEntity?> = tripDao.getTripByIdFlow(tripId)

    fun getSamplesForTripFlow(tripId: String): Flow<List<TelemetrySampleEntity>> =
        sampleDao.getSamplesForTripFlow(tripId)

    fun getRawLogsForTripFlow(tripId: String): Flow<List<RawLogEntity>> =
        rawLogDao.getRawLogsForTripFlow(tripId)

    fun getEventsForTripFlow(tripId: String): Flow<List<DiagnosticEventEntity>> =
        eventDao.getEventsForTripFlow(tripId)

    fun getAnalysisForTripFlow(tripId: String): Flow<AiAnalysisEntity?> =
        aiAnalysisDao.getAnalysisForTripFlow(tripId)

    suspend fun getTripById(tripId: String): TripEntity? = withContext(Dispatchers.IO) {
        tripDao.getTripById(tripId)
    }

    suspend fun getSamplesForTrip(tripId: String): List<TelemetrySampleEntity> = withContext(Dispatchers.IO) {
        sampleDao.getSamplesForTrip(tripId)
    }

    suspend fun getRawLogsForTrip(tripId: String): List<RawLogEntity> = withContext(Dispatchers.IO) {
        rawLogDao.getRawLogsForTrip(tripId)
    }

    suspend fun getEventsForTrip(tripId: String): List<DiagnosticEventEntity> = withContext(Dispatchers.IO) {
        eventDao.getEventsForTrip(tripId)
    }

    suspend fun getAnalysisForTrip(tripId: String): AiAnalysisEntity? = withContext(Dispatchers.IO) {
        aiAnalysisDao.getAnalysisForTrip(tripId)
    }

    suspend fun insertTrip(trip: TripEntity) = withContext(Dispatchers.IO) {
        tripDao.insertTrip(trip)
    }

    suspend fun updateTrip(trip: TripEntity) = withContext(Dispatchers.IO) {
        tripDao.updateTrip(trip)
    }

    suspend fun insertSamples(samples: List<TelemetrySampleEntity>) = withContext(Dispatchers.IO) {
        if (samples.isNotEmpty()) {
            sampleDao.insertSamples(samples)
        }
    }

    suspend fun insertRawLogs(logs: List<RawLogEntity>) = withContext(Dispatchers.IO) {
        if (logs.isNotEmpty()) {
            rawLogDao.insertRawLogs(logs)
        }
    }

    suspend fun insertDiagnosticEvent(event: DiagnosticEventEntity) = withContext(Dispatchers.IO) {
        eventDao.insertEvent(event)
    }

    suspend fun runAiCarDoctorAnalysis(tripId: String): CarDoctorReport = withContext(Dispatchers.IO) {
        val trip = tripDao.getTripById(tripId) ?: throw IllegalArgumentException("Trip $tripId not found")
        val samples = sampleDao.getSamplesForTrip(tripId)
        val events = eventDao.getEventsForTrip(tripId)

        val report = ruleBasedEngine.analyzeTrip(trip, samples, events)

        // Store analysis in Room database
        val nowUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val obsArray = JSONArray()
        report.observations.forEach { obs ->
            val obj = JSONObject().apply {
                put("category", obs.category)
                put("severity", obs.severity)
                put("title", obs.title)
                put("desc", obs.description)
                put("cause", obs.possibleCause)
                put("rec", obs.recommendation)
            }
            obsArray.put(obj)
        }

        val recArray = JSONArray()
        report.recommendedChecks.forEach { recArray.put(it) }

        val entity = AiAnalysisEntity(
            tripId = tripId,
            timestamp = System.currentTimeMillis(),
            timestampUtc = nowUtc,
            provider = report.provider,
            model = report.model,
            overallHealth = report.overallHealth,
            healthScore = report.healthScore,
            drivingSummary = report.drivingSummary,
            engineBehavior = report.engineBehavior,
            temperatureBehavior = report.temperatureBehavior,
            voltageBehavior = report.voltageBehavior,
            throttleLoadBehavior = report.throttleLoadBehavior,
            potentialAnomalies = obsArray.toString(),
            recommendedChecks = recArray.toString(),
            confidence = report.confidence,
            privacyMode = "LOCAL_ONLY"
        )
        aiAnalysisDao.insertAnalysis(entity)

        // Update trip health score
        tripDao.updateTrip(trip.copy(healthScore = report.healthScore))

        report
    }

    suspend fun deleteTrip(tripId: String) = withContext(Dispatchers.IO) {
        tripDao.deleteTripById(tripId)
        sampleDao.deleteSamplesForTrip(tripId)
        rawLogDao.deleteRawLogsForTrip(tripId)
        eventDao.deleteEventsForTrip(tripId)
        aiAnalysisDao.deleteAnalysisForTrip(tripId)
    }

    suspend fun deleteAllTrips() = withContext(Dispatchers.IO) {
        tripDao.deleteAllTrips()
        sampleDao.deleteAllSamples()
        rawLogDao.deleteAllRawLogs()
        eventDao.deleteAllEvents()
        aiAnalysisDao.deleteAllAnalyses()
    }

    suspend fun getStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        val tripCount = tripDao.getTripCount()
        val sampleCount = sampleDao.getTotalSampleCount()
        val rawLogCount = rawLogDao.getTotalRawLogCount()
        StorageStats(
            tripCount = tripCount,
            sampleCount = sampleCount,
            rawLogCount = rawLogCount
        )
    }
}

data class StorageStats(
    val tripCount: Int,
    val sampleCount: Int,
    val rawLogCount: Int
)
