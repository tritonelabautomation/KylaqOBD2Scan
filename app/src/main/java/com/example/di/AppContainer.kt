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

    /**
     * AUDIT DB-02: set when the database could not be opened because it was written by a newer
     * build. Null means "no database problem". The UI shows this instead of a silent crash loop.
     */
    @Volatile
    var fatalDatabaseMessage: String? = null
        private set
    
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            
            // AUDIT DB-02 (2026-11-12): a downgrade is now refused rather than wiping the
            // database, so this open can throw. Record what happened in a place the UI can
            // show, then rethrow - the container must not come up half-initialised.
            val appDb = try {
                AppDatabase.getInstance(context)
            } catch (t: Throwable) {
                if (AppDatabase.isDowngradeRefusal(t)) {
                    fatalDatabaseMessage = AppDatabase.downgradeHelpMessage()
                    android.util.Log.e("AppContainer", fatalDatabaseMessage, t)
                }
                throw t
            }
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
