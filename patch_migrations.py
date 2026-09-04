with open("app/src/main/java/com/example/data/db/Migrations.kt", "r") as f:
    text = f.read()

migration_5_6 = """
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val tables = listOf("catalog_engines", "catalog_transmissions", "catalog_manufacturers", "catalog_models", "catalog_generations", "catalog_variants")
        for (table in tables) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `source` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `sourceUrl` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `sourceDate` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `market` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `confidence` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `verificationStatus` TEXT")
        }
    }
}
"""

text += migration_5_6

with open("app/src/main/java/com/example/data/db/Migrations.kt", "w") as f:
    f.write(text)

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "r") as f:
    app_db = f.read()

app_db = app_db.replace("version = 5,", "version = 6,")
app_db = app_db.replace("MIGRATION_4_5", "MIGRATION_4_5, MIGRATION_5_6")

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "w") as f:
    f.write(app_db)
