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
        ServiceRecordEntity::class,
        CatalogMetadataEntity::class,
        CatalogEngineEntity::class,
        CatalogTransmissionEntity::class,
        CatalogManufacturerEntity::class,
        CatalogModelEntity::class,
        CatalogGenerationEntity::class,
        CatalogVariantEntity::class,
        ScanSessionEntity::class,
        EcuTopologyEntity::class,
        PidCapabilityEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun telemetrySampleDao(): TelemetrySampleDao
    abstract fun rawLogDao(): RawLogDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun aiAnalysisDao(): AiAnalysisDao
    abstract fun newEntitiesDao(): NewEntitiesDao // We'll create this DAO
    abstract fun catalogDao(): CatalogDao // Create this next

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
