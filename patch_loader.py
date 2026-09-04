import re
with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "r") as f:
    loader = f.read()

old_variant = """                                    CatalogVariantEntity(
                                        id = v.getString("id"),
                                        generationId = genId,
                                        name = v.getString("name"),
                                        engineId = if (v.isNull("engineId")) null else v.getString("engineId"),
                                        transmissionId = if (v.isNull("transmissionId")) null else v.getString("transmissionId"),
                                        bodyType = if (v.isNull("bodyType")) null else v.getString("bodyType"),
                                        drivetrain = if (v.isNull("drivetrain")) null else v.getString("drivetrain")
                                    )"""

new_variant = """                                    CatalogVariantEntity(
                                        id = v.getString("id"),
                                        generationId = genId,
                                        name = v.getString("name"),
                                        engineId = if (v.isNull("engineId")) null else v.getString("engineId"),
                                        transmissionId = if (v.isNull("transmissionId")) null else v.getString("transmissionId"),
                                        bodyType = if (v.isNull("bodyType")) null else v.getString("bodyType"),
                                        drivetrain = if (v.isNull("drivetrain")) null else v.getString("drivetrain"),
                                        startYear = if (v.isNull("startYear")) null else v.getInt("startYear"),
                                        endYear = if (v.isNull("endYear")) null else v.getInt("endYear")
                                    )"""

loader = loader.replace(old_variant, new_variant)
with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "w") as f:
    f.write(loader)
