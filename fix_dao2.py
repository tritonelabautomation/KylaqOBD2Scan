import re

with open("app/src/main/java/com/example/data/db/dao/NewEntitiesDao.kt", "r") as f:
    text = f.read()

text = text.replace("}\n    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun insertScanSession", "    @Insert(onConflict = OnConflictStrategy.REPLACE)\n    suspend fun insertScanSession")

with open("app/src/main/java/com/example/data/db/dao/NewEntitiesDao.kt", "w") as f:
    f.write(text)
