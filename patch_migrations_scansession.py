with open("app/src/main/java/com/example/data/db/Migrations.kt", "r") as f:
    text = f.read()

migration_7_8 = """
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            \"\"\"
            CREATE TABLE IF NOT EXISTS `scan_sessions` (
                `id` TEXT NOT NULL,
                `vehicleId` TEXT,
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `connectionType` TEXT NOT NULL,
                `adapterName` TEXT NOT NULL,
                `adapterAddress` TEXT NOT NULL,
                `protocol` TEXT,
                `ecuCount` INTEGER NOT NULL,
                `pidCount` INTEGER NOT NULL,
                `dtcCount` INTEGER NOT NULL,
                `readinessAvailable` INTEGER NOT NULL,
                `completionStatus` TEXT NOT NULL,
                `errorCount` INTEGER NOT NULL,
                `warningCount` INTEGER NOT NULL,
                `rawEvidenceReference` TEXT,
                PRIMARY KEY(`id`)
            )
            \"\"\".trimIndent()
        )
        
        db.execSQL(
            \"\"\"
            CREATE TABLE IF NOT EXISTS `ecu_topologies` (
                `id` TEXT NOT NULL,
                `vehicleId` TEXT,
                `address` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `protocol` TEXT,
                `lastSeen` INTEGER NOT NULL,
                `responseTime` INTEGER NOT NULL,
                `supportedServices` TEXT NOT NULL,
                `supportedPids` TEXT NOT NULL,
                `dtcCount` INTEGER NOT NULL,
                `confidence` TEXT NOT NULL,
                `rawEvidence` TEXT,
                PRIMARY KEY(`id`)
            )
            \"\"\".trimIndent()
        )
        
        db.execSQL(
            \"\"\"
            CREATE TABLE IF NOT EXISTS `pid_capabilities` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `vehicleId` TEXT,
                `ecuAddress` TEXT NOT NULL,
                `pid` TEXT NOT NULL,
                `supported` INTEGER NOT NULL,
                `lastVerified` INTEGER NOT NULL,
                `responseLatency` INTEGER NOT NULL,
                `failureCount` INTEGER NOT NULL,
                `confidence` TEXT NOT NULL
            )
            \"\"\".trimIndent()
        )
    }
}
"""

text += migration_7_8

with open("app/src/main/java/com/example/data/db/Migrations.kt", "w") as f:
    f.write(text)

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "r") as f:
    app_db = f.read()

app_db = app_db.replace("version = 7,", "version = 8,")
app_db = app_db.replace("MIGRATION_6_7", "MIGRATION_6_7, MIGRATION_7_8")

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "w") as f:
    f.write(app_db)
