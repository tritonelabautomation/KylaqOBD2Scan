package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.db.dao.*
import com.example.data.db.entities.*

@Database(
    entities = [
        TripEntity::class,
        TelemetrySampleEntity::class,
        RawLogEntity::class,
        DiagnosticEventEntity::class,
        AiAnalysisEntity::class,
        VehicleEntity::class,
        ProtocolTestResultEntity::class,
        DtcRecordEntity::class,
        ServiceRecordEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun telemetrySampleDao(): TelemetrySampleDao
    abstract fun rawLogDao(): RawLogDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun aiAnalysisDao(): AiAnalysisDao
    abstract fun newEntitiesDao(): NewEntitiesDao // We'll create this DAO

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "obd_research_logger.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
