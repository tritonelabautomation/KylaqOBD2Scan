with open("app/src/main/java/com/example/data/db/Migrations.kt", "r") as f:
    text = f.read()

migration_6_7 = """
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogManufacturerId` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogModelId` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogGenerationId` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogEngineId` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogTransmissionId` TEXT")
    }
}
"""

text += migration_6_7

with open("app/src/main/java/com/example/data/db/Migrations.kt", "w") as f:
    f.write(text)

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "r") as f:
    app_db = f.read()

app_db = app_db.replace("version = 6,", "version = 7,")
app_db = app_db.replace("MIGRATION_5_6", "MIGRATION_5_6, MIGRATION_6_7")

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "w") as f:
    f.write(app_db)
