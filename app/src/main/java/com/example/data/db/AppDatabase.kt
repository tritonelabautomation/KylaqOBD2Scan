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
        PidCapabilityEntity::class,
        RefuelEventEntity::class
    ],
    version = 14,
    // AUDIT DB-01 (2026-11-12): was false. Room now writes its schema JSON to
    // app/schemas so migrations can be validated and schema changes reviewed.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun telemetrySampleDao(): TelemetrySampleDao
    abstract fun rawLogDao(): RawLogDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun aiAnalysisDao(): AiAnalysisDao
    abstract fun newEntitiesDao(): NewEntitiesDao // We'll create this DAO
    abstract fun catalogDao(): CatalogDao // Create this next
    abstract fun refuelEventDao(): RefuelEventDao

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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    // AUDIT DB-02 (2026-11-12): this builder used to call
                    // `fallbackToDestructiveMigrationOnDowngrade()`, which DELETES the entire
                    // database when the on-disk version is newer than the running APK - exactly
                    // what happens when the owner rolls back a bad update. Every trip, sample,
                    // raw log and DTC record disappeared silently, with no warning.
                    // A downgrade must never trade recorded data for a clean start, so the
                    // destructive fallback is gone: Room now raises instead of deleting, which
                    // leaves the .db file byte-for-byte intact for a later, newer build to open.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
