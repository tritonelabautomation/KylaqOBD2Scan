package com.example.di

import android.content.Context
import com.example.bluetooth.BluetoothManager
import com.example.data.DocumentRepository
import com.example.data.ExpenseRepository
import com.example.data.CarpoolRepository
import com.example.data.FuelLogRepository
import com.example.data.ReminderRepository
import com.example.data.TripPlanRepository
import com.example.data.MaintenanceRepository
import com.example.data.GpsManager
import com.example.data.RawLogManager
import com.example.data.RecordingManager
import com.example.data.SettingsRepository
import com.example.data.catalog.CatalogLoader
import com.example.data.catalog.CatalogRepository
import com.example.data.db.AppDatabase
import com.example.scheduler.ObdScheduler
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.model.ProtocolHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AppContainer {
    @Volatile
    private var isInitialized = false
    
    val protocolHealth = MutableStateFlow(ProtocolHealth.UNKNOWN)
    
    lateinit var rawLogManager: RawLogManager
    lateinit var gpsManager: GpsManager
    lateinit var settingsRepository: SettingsRepository
    lateinit var recordingManager: RecordingManager
    lateinit var welcomeSpeaker: com.example.data.WelcomeSpeaker

    /**
     * Altitude range captured for the CURRENT recording (owner 2026-09-15 trip-summary
     * fix). Null only before container init; RecordingManager persists min/max at stop.
     */
    fun tripAltitudeStats(): com.example.analysis.AltitudeStats? =
        if (::gpsManager.isInitialized) gpsManager.tripAltitude else null

    /**
     * Altitude of the current GPS fix, for the per-sample trip-log column (owner 2026-09-15:
     * "did you add altitude info from GPS into trip log?"). Null when GPS is off, no fix has
     * passed the accuracy gate yet, or the fix reported no altitude - the meaningless 0.0
     * default of [GpsData] is never handed out as a measurement.
     */
    fun currentAltitudeM(): Double? =
        if (::gpsManager.isInitialized) {
            gpsManager.gpsData.value
                .takeIf { it.isAvailable && it.hasAltitude }
                ?.altitudeMeters
        } else {
            null
        }

    fun currentCoordinates(): Pair<Double, Double>? =
        if (::gpsManager.isInitialized) {
            gpsManager.gpsData.value
                .takeIf { it.isAvailable && it.latitude != 0.0 && it.longitude != 0.0 }
                ?.let { it.latitude to it.longitude }
        } else null

    fun currentGps(): com.example.data.GpsData =
        if (::gpsManager.isInitialized) gpsManager.gpsData.value else com.example.data.GpsData()
    lateinit var bluetoothManager: BluetoothManager
    lateinit var obdScheduler: ObdScheduler
    lateinit var pidDiscoveryService: com.example.discovery.PidDiscoveryService
    lateinit var cloudBackupManager: com.example.backup.CloudBackupManager
    lateinit var catalogRepository: CatalogRepository
    lateinit var fuelLogRepository: FuelLogRepository
    lateinit var carpoolRepository: CarpoolRepository
    lateinit var maintenanceRepository: MaintenanceRepository
    lateinit var expenseRepository: ExpenseRepository
    lateinit var documentRepository: DocumentRepository
    lateinit var reminderRepository: ReminderRepository
    lateinit var tripPlanRepository: TripPlanRepository
    lateinit var manualRepository: com.example.manual.ManualRepository
    
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            
            val appDb = AppDatabase.getInstance(context)
            catalogRepository = CatalogRepository(appDb)
            
            // Load catalog asynchronously if empty
            CoroutineScope(Dispatchers.IO).launch {
                CatalogLoader(context, appDb).loadCatalogFromJsonIfEmpty()
            }
            val appContext = context.applicationContext
            val logDir = File(appContext.filesDir, "raw_logs")
            rawLogManager = RawLogManager(logDir)
            gpsManager = GpsManager(appContext)
            settingsRepository = SettingsRepository(appContext)
            recordingManager = RecordingManager(appContext, rawLogManager)
            welcomeSpeaker = com.example.data.WelcomeSpeaker(appContext, settingsRepository.welcomeVoiceId.value)
            bluetoothManager = BluetoothManager(appContext)
            val capabilityStore = com.example.discovery.SharedPrefsCapabilityStore(
                appContext.getSharedPreferences("pid_capability_snapshot", android.content.Context.MODE_PRIVATE)
            )
            obdScheduler = ObdScheduler(
                recordingManager,
                settingsRepository,
                com.example.discovery.PidCapabilityManager(capabilityStore)
            )
            pidDiscoveryService = com.example.discovery.PidDiscoveryService(obdScheduler.capabilityManager)
            cloudBackupManager = com.example.backup.CloudBackupManager(appContext, settingsRepository, recordingManager)
            fuelLogRepository = FuelLogRepository(appContext)
            carpoolRepository = CarpoolRepository(appContext)
            maintenanceRepository = MaintenanceRepository(appContext)
            expenseRepository = ExpenseRepository(appContext)
            documentRepository = DocumentRepository(appContext)
            reminderRepository = ReminderRepository(appContext)
            tripPlanRepository = TripPlanRepository(appContext)
            manualRepository = com.example.manual.ManualRepository(appContext)
            isInitialized = true
        }
    }
}
