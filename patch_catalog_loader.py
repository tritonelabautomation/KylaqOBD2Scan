import re

with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "r") as f:
    text = f.read()

def inject_provenance(match):
    fields = match.group(1)
    end = match.group(2)
    return fields + """,
                            source = if (e.isNull("source")) null else e.getString("source"),
                            sourceUrl = if (e.isNull("sourceUrl")) null else e.getString("sourceUrl"),
                            sourceDate = if (e.isNull("sourceDate")) null else e.getString("sourceDate"),
                            market = if (e.isNull("market")) null else e.getString("market"),
                            confidence = if (e.isNull("confidence")) null else e.getString("confidence"),
                            verificationStatus = if (e.isNull("verificationStatus")) null else e.getString("verificationStatus")""" + end

# Replace in CatalogEngineEntity
text = re.sub(r'(torqueNm = if \(e\.isNull\("torqueNm"\)\) null else e\.getInt\("torqueNm"\))(\s*\))', inject_provenance, text)

# Transmission
def inject_provenance_t(match):
    fields = match.group(1)
    end = match.group(2)
    return fields + """,
                            source = if (t.isNull("source")) null else t.getString("source"),
                            sourceUrl = if (t.isNull("sourceUrl")) null else t.getString("sourceUrl"),
                            sourceDate = if (t.isNull("sourceDate")) null else t.getString("sourceDate"),
                            market = if (t.isNull("market")) null else t.getString("market"),
                            confidence = if (t.isNull("confidence")) null else t.getString("confidence"),
                            verificationStatus = if (t.isNull("verificationStatus")) null else t.getString("verificationStatus")""" + end

text = re.sub(r'(gearCount = if \(t\.isNull\("gearCount"\)\) null else t\.getInt\("gearCount"\))(\s*\))', inject_provenance_t, text)

# Manufacturer
def inject_provenance_m(match):
    fields = match.group(1)
    end = match.group(2)
    return fields + """,
                        source = if (m.isNull("source")) null else m.getString("source"),
                        sourceUrl = if (m.isNull("sourceUrl")) null else m.getString("sourceUrl"),
                        sourceDate = if (m.isNull("sourceDate")) null else m.getString("sourceDate"),
                        market = if (m.isNull("market")) null else m.getString("market"),
                        confidence = if (m.isNull("confidence")) null else m.getString("confidence"),
                        verificationStatus = if (m.isNull("verificationStatus")) null else m.getString("verificationStatus")""" + end
text = re.sub(r'(CatalogManufacturerEntity\(id = mId, name = m\.getString\("name"\))(\))', inject_provenance_m, text)

# Model
def inject_provenance_mod(match):
    fields = match.group(1)
    end = match.group(2)
    return fields + """,
                                source = if (mod.isNull("source")) null else mod.getString("source"),
                                sourceUrl = if (mod.isNull("sourceUrl")) null else mod.getString("sourceUrl"),
                                sourceDate = if (mod.isNull("sourceDate")) null else mod.getString("sourceDate"),
                                market = if (mod.isNull("market")) null else mod.getString("market"),
                                confidence = if (mod.isNull("confidence")) null else mod.getString("confidence"),
                                verificationStatus = if (mod.isNull("verificationStatus")) null else mod.getString("verificationStatus")""" + end
text = re.sub(r'(isCurrent = mod\.getBoolean\("isCurrent"\))(\s*\))', inject_provenance_mod, text)

# Generation
def inject_provenance_gen(match):
    fields = match.group(1)
    end = match.group(2)
    return fields + """,
                                    source = if (gen.isNull("source")) null else gen.getString("source"),
                                    sourceUrl = if (gen.isNull("sourceUrl")) null else gen.getString("sourceUrl"),
                                    sourceDate = if (gen.isNull("sourceDate")) null else gen.getString("sourceDate"),
                                    market = if (gen.isNull("market")) null else gen.getString("market"),
                                    confidence = if (gen.isNull("confidence")) null else gen.getString("confidence"),
                                    verificationStatus = if (gen.isNull("verificationStatus")) null else gen.getString("verificationStatus")""" + end
text = re.sub(r'(endYear = if \(gen\.isNull\("endYear"\)\) null else gen\.getInt\("endYear"\))(\s*\))', inject_provenance_gen, text)

# Variant
def inject_provenance_v(match):
    fields = match.group(1)
    end = match.group(2)
    return fields + """,
                                        source = if (v.isNull("source")) null else v.getString("source"),
                                        sourceUrl = if (v.isNull("sourceUrl")) null else v.getString("sourceUrl"),
                                        sourceDate = if (v.isNull("sourceDate")) null else v.getString("sourceDate"),
                                        market = if (v.isNull("market")) null else v.getString("market"),
                                        confidence = if (v.isNull("confidence")) null else v.getString("confidence"),
                                        verificationStatus = if (v.isNull("verificationStatus")) null else v.getString("verificationStatus")""" + end
text = re.sub(r'(endYear = if \(v\.isNull\("endYear"\)\) null else v\.getInt\("endYear"\))(\s*\))', inject_provenance_v, text)


with open("app/src/main/java/com/example/data/catalog/CatalogLoader.kt", "w") as f:
    f.write(text)
