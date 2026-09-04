with open("app/src/main/java/com/example/data/db/Migrations.kt", "r") as f:
    migrations = f.read()

new_migration = """val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogSource` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogConfidence` TEXT")
        db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `catalogEvidence` TEXT")
    }
}
"""

migrations += "\n" + new_migration

with open("app/src/main/java/com/example/data/db/Migrations.kt", "w") as f:
    f.write(migrations)

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "r") as f:
    db = f.read()

db = db.replace("version = 4", "version = 5")
db = db.replace("MIGRATION_3_4)", "MIGRATION_3_4, MIGRATION_4_5)")

with open("app/src/main/java/com/example/data/db/AppDatabase.kt", "w") as f:
    f.write(db)
