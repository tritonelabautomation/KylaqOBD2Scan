package com.example.di

import android.content.Context
import com.example.bluetooth.BluetoothManager
import com.example.data.GpsManager
import com.example.data.RawLogManager
import com.example.data.RecordingManager
import com.example.data.SettingsRepository
import com.example.scheduler.ObdScheduler
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import com.example.model.ProtocolHealth

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
    lateinit var cloudBackupManager: com.example.backup.CloudBackupManager
    
    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val appContext = context.applicationContext
            val logDir = File(appContext.filesDir, "raw_logs")
            rawLogManager = RawLogManager(logDir)
            gpsManager = GpsManager(appContext)
            settingsRepository = SettingsRepository(appContext)
            recordingManager = RecordingManager(appContext, rawLogManager)
            bluetoothManager = BluetoothManager(appContext)
            obdScheduler = ObdScheduler(recordingManager, settingsRepository)
            cloudBackupManager = com.example.backup.CloudBackupManager(appContext, settingsRepository, recordingManager)
            isInitialized = true
        }
    }
}
