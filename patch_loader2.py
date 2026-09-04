import re
with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "r") as f:
    loader = f.read()

old_func = """    suspend fun loadCatalogFromJsonIfEmpty() {
        withContext(Dispatchers.IO) {
            val count = database.catalogDao().getVariantCount()
            if (count == 0) {
                Log.d("CatalogLoader", "Catalog empty, loading from JSON...")
                loadCatalog()
            } else {
                Log.d("CatalogLoader", "Catalog already populated ($count variants)")
            }
        }
    }"""

new_func = """    suspend fun loadCatalogFromJsonIfEmpty() {
        withContext(Dispatchers.IO) {
            val count = database.catalogDao().getVariantCount()
            val metadataFlow = database.catalogDao().getMetadata()
            val metadata = kotlinx.coroutines.flow.firstOrNull(metadataFlow)

            val inputStream = context.assets.open("vehicle_catalog_india_v1.json")
            val jsonString = java.io.InputStreamReader(inputStream).readText()
            val json = JSONObject(jsonString)
            val assetVersion = json.getString("catalogVersion")
            
            if (count == 0 || metadata == null || metadata.catalogVersion != assetVersion) {
                Log.d("CatalogLoader", "Catalog needs update, loading from JSON (Asset Version: $assetVersion)...")
                loadCatalog(json)
            } else {
                Log.d("CatalogLoader", "Catalog already populated and up to date (Version: $assetVersion, $count variants)")
            }
        }
    }"""

loader = loader.replace(old_func, new_func)

old_load = """    suspend fun loadCatalog() {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.assets.open("vehicle_catalog_india_v1.json")
                val jsonString = InputStreamReader(inputStream).readText()
                val json = JSONObject(jsonString)"""

new_load = """    suspend fun loadCatalog(json: JSONObject) {
        withContext(Dispatchers.IO) {
            try {"""

loader = loader.replace(old_load, new_load)

with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "w") as f:
    f.write(loader)
