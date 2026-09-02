package com.example.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Robust database migrations to prevent destructive wipes between app versions.
 */
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
