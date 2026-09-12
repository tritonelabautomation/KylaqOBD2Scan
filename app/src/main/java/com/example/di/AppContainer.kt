package com.example.di

import android.content.Context
import com.example.bluetooth.BluetoothManager
import com.example.data.DocumentRepository
import com.example.data.ExpenseRepository
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
    lateinit var bluetoothManager: BluetoothManager
    lateinit var obdScheduler: ObdScheduler
    lateinit var pidDiscoveryService: com.example.discovery.PidDiscoveryService
    lateinit var cloudBackupManager: com.example.backup.CloudBackupManager
    lateinit var catalogRepository: CatalogRepository
    lateinit var fuelLogRepository: FuelLogRepository
    lateinit var maintenanceRepository: MaintenanceRepository
    lateinit var expenseRepository: ExpenseRepository
    lateinit var documentRepository: DocumentRepository
    lateinit var reminderRepository: ReminderRepository
    lateinit var tripPlanRepository: TripPlanRepository
    
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
            bluetoothManager = BluetoothManager(appContext)
            obdScheduler = ObdScheduler(recordingManager, settingsRepository)
            pidDiscoveryService = com.example.discovery.PidDiscoveryService(obdScheduler.capabilityManager)
            cloudBackupManager = com.example.backup.CloudBackupManager(appContext, settingsRepository, recordingManager)
            fuelLogRepository = FuelLogRepository(appContext)
            maintenanceRepository = MaintenanceRepository(appContext)
            expenseRepository = ExpenseRepository(appContext)
            documentRepository = DocumentRepository(appContext)
            reminderRepository = ReminderRepository(appContext)
            tripPlanRepository = TripPlanRepository(appContext)
            isInitialized = true
        }
    }
}
