import re

with open("app/src/main/java/com/example/data/db/dao/NewEntitiesDao.kt", "r") as f:
    text = f.read()

# It currently looks like:
# }
#     @Insert(onConflict = OnConflictStrategy.REPLACE)
#     suspend fun insertScanSession(session: ScanSessionEntity)
# ...

# We need to move the } to the end of the file.
text = text.replace("}\n\n    @Insert", "\n    @Insert")
text = text + "\n}"

with open("app/src/main/java/com/example/data/db/dao/NewEntitiesDao.kt", "w") as f:
    f.write(text)
