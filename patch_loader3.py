with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "r") as f:
    loader = f.read()

loader = loader.replace("import kotlinx.coroutines.flow.firstOrNull\n", "")
loader = loader.replace("import kotlinx.coroutines.withContext", "import kotlinx.coroutines.withContext\nimport kotlinx.coroutines.flow.firstOrNull")
loader = loader.replace("metadata.catalogVersion != assetVersion", "metadata?.catalogVersion != assetVersion")

with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "w") as f:
    f.write(loader)
