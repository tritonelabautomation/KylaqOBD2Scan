import re

with open("app/src/main/java/com/example/protocol/VinDecoder.kt", "r") as f:
    text = f.read()

# Replace kotlinx.coroutines.flow.firstOrNull with nothing
text = text.replace("import kotlinx.coroutines.flow.firstOrNull\n", "")

text = text.replace("val variants = catalogRepository.getAllVariants().firstOrNull() ?: emptyList()", "val variants = catalogRepository.getAllVariants()")
text = text.replace("val mfgId = catalogRepository.getAllManufacturers().firstOrNull()?.find {", "val mfgId = catalogRepository.getAllManufacturers().find {")
text = text.replace("val mfgModels = catalogRepository.getModelsForManufacturer(mfgId).firstOrNull()?.map { it.id }?.toSet() ?: emptySet()", "val mfgModels = catalogRepository.getAllModels().filter { it.manufacturerId == mfgId }.map { it.id }.toSet()")
text = text.replace("val mfgGens = catalogRepository.getAllGenerations().firstOrNull()?.filter { mfgModels.contains(it.modelId) }?.map { it.id }?.toSet() ?: emptySet()", "val mfgGens = catalogRepository.getAllGenerations().filter { mfgModels.contains(it.modelId) }.map { it.id }.toSet()")

with open("app/src/main/java/com/example/protocol/VinDecoder.kt", "w") as f:
    f.write(text)
