package com.example.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Robust database migrations to prevent destructive wipes between app versions.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add new columns to vehicles table
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogVariantId` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `nickname` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `licensePlate` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `odometerKm` INTEGER")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `isLegacy` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `notes` TEXT")

        // Set existing vehicles as legacy
        db.execSQL("UPDATE `vehicles` SET `isLegacy` = 1")

        // Create Catalog Tables
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_metadata` (`id` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, `catalogVersion` TEXT NOT NULL, `market` TEXT NOT NULL, `country` TEXT NOT NULL, `lastUpdated` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_engines` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `code` TEXT, `displacementCc` INTEGER, `cylinders` INTEGER, `aspiration` TEXT, `fuelType` TEXT, `powerPs` INTEGER, `torqueNm` INTEGER, PRIMARY KEY(`id`))")
        
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_transmissions` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT, `gearCount` INTEGER, PRIMARY KEY(`id`))")
        
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_manufacturers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
        
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_models` (`id` TEXT NOT NULL, `manufacturerId` TEXT NOT NULL, `name` TEXT NOT NULL, `isCurrent` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`manufacturerId`) REFERENCES `catalog_manufacturers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_models_manufacturerId` ON `catalog_models` (`manufacturerId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_generations` (`id` TEXT NOT NULL, `modelId` TEXT NOT NULL, `name` TEXT NOT NULL, `startYear` INTEGER, `endYear` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`modelId`) REFERENCES `catalog_models`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_generations_modelId` ON `catalog_generations` (`modelId`)")

        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_variants` (`id` TEXT NOT NULL, `generationId` TEXT NOT NULL, `name` TEXT NOT NULL, `engineId` TEXT, `transmissionId` TEXT, `bodyType` TEXT, `drivetrain` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`generationId`) REFERENCES `catalog_generations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`engineId`) REFERENCES `catalog_engines`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`transmissionId`) REFERENCES `catalog_transmissions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_variants_generationId` ON `catalog_variants` (`generationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_variants_engineId` ON `catalog_variants` (`engineId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_variants_transmissionId` ON `catalog_variants` (`transmissionId`)")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create vehicles table if not exists
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vehicles` (
                `id` TEXT NOT NULL,
                `make` TEXT NOT NULL,
                `model` TEXT NOT NULL,
                `year` TEXT NOT NULL,
                `vin` TEXT,
                `defaultProtocol` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        // Create protocol_test_results table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `protocol_test_results` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `protocol` TEXT NOT NULL,
                `atCommand` TEXT NOT NULL,
                `resultStatus` TEXT NOT NULL,
                `ecuResponses` INTEGER NOT NULL,
                `canErrors` INTEGER NOT NULL,
                `timeouts` INTEGER NOT NULL,
                `averageLatency` INTEGER NOT NULL,
                `appVersion` TEXT NOT NULL,
                `buildNumber` INTEGER NOT NULL,
                `gitCommit` TEXT NOT NULL
            )
            """.trimIndent()
        )

        // Create dtc_records table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `dtc_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `vehicleId` TEXT,
                `tripId` TEXT,
                `timestamp` INTEGER NOT NULL,
                `code` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `status` TEXT NOT NULL
            )
            """.trimIndent()
        )

        // Create service_records table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `service_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `vehicleId` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `dateString` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `mileage` INTEGER
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `catalog_variants` ADD COLUMN `startYear` INTEGER")
        db.execSQL("ALTER TABLE `catalog_variants` ADD COLUMN `endYear` INTEGER")
    }
}
