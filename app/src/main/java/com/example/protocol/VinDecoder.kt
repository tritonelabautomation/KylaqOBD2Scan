package com.example.protocol

import com.example.data.catalog.CatalogRepository
import com.example.data.db.entities.CatalogVariantEntity

data class VinDecodeResult(
    val vin: String,
    val wmi: String,
    val vds: String,
    val vis: String,
    val modelYearCandidate: Int?,
    val manufacturerCandidate: String?,
    val candidateVariants: List<CatalogVariantEntity>,
    val confidence: String, // VERIFIED, LIKELY, UNVERIFIED
    val verificationStatus: String,
    val source: String
)

object VinDecoder {

    /**
     * Attempts to decode a 17-character VIN and match it against the catalog.
     */
    suspend fun decodeVin(vin: String, catalogRepository: CatalogRepository): VinDecodeResult {
        val cleanVin = vin.uppercase().replace(Regex("[^A-HJ-NPR-Z0-9]"), "")
        if (cleanVin.length != 17) {
            return VinDecodeResult(
                vin = vin,
                wmi = "",
                vds = "",
                vis = "",
                modelYearCandidate = null,
                manufacturerCandidate = null,
                candidateVariants = emptyList(),
                confidence = "UNVERIFIED",
                verificationStatus = "INVALID_LENGTH",
                source = "LOCAL"
            )
        }

        val wmi = cleanVin.substring(0, 3)
        val vds = cleanVin.substring(3, 9)
        val vis = cleanVin.substring(9, 17)

        val yearChar = cleanVin[9]
        val modelYear = decodeYearChar(yearChar)

        val mfgMatch = decodeWmi(wmi)

        // Try to match variants based on year and manufacturer
        // This is a naive approach. A real decoder would match VDS to specific models.
        var candidates = emptyList<CatalogVariantEntity>()
        var confidence = "UNVERIFIED"

        try {
            val variants = catalogRepository.getAllVariants()
            if (mfgMatch != null && variants.isNotEmpty()) {
                val mfgId = catalogRepository.getAllManufacturers().find { 
                    it.name.contains(mfgMatch, ignoreCase = true) 
                }?.id
                
                if (mfgId != null) {
                    val mfgModels = catalogRepository.getAllModels().filter { it.manufacturerId == mfgId }.map { it.id }.toSet()
                    val mfgGens = catalogRepository.getAllGenerations().filter { mfgModels.contains(it.modelId) }.map { it.id }.toSet()
                    
                    val possibleVariants = variants.filter { mfgGens.contains(it.generationId) }
                    
                    candidates = if (modelYear != null) {
                        possibleVariants.filter { 
                            (it.startYear == null || it.startYear <= modelYear) && 
                            (it.endYear == null || it.endYear >= modelYear)
                        }
                    } else {
                        possibleVariants
                    }
                    
                    if (candidates.isNotEmpty()) {
                        confidence = "LIKELY"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore DB errors
        }

        return VinDecodeResult(
            vin = cleanVin,
            wmi = wmi,
            vds = vds,
            vis = vis,
            modelYearCandidate = modelYear,
            manufacturerCandidate = mfgMatch,
            candidateVariants = candidates,
            confidence = confidence,
            verificationStatus = "DECODED",
            source = "LOCAL"
        )
    }

    private fun decodeYearChar(c: Char): Int? {
        val yearMap = mapOf(
            'A' to 2010, 'B' to 2011, 'C' to 2012, 'D' to 2013, 'E' to 2014,
            'F' to 2015, 'G' to 2016, 'H' to 2017, 'J' to 2018, 'K' to 2019,
            'L' to 2020, 'M' to 2021, 'N' to 2022, 'P' to 2023, 'R' to 2024,
            'S' to 2025, 'T' to 2026, 'V' to 2027, 'W' to 2028, 'X' to 2029,
            'Y' to 2030,
            
            // Older
            '1' to 2001, '2' to 2002, '3' to 2003, '4' to 2004, '5' to 2005,
            '6' to 2006, '7' to 2007, '8' to 2008, '9' to 2009
        )
        return yearMap[c]
    }

    private fun decodeWmi(wmi: String): String? {
        // India WMIs
        if (wmi.startsWith("MA1") || wmi.startsWith("MA3")) return "Maruti Suzuki"
        if (wmi.startsWith("MAL")) return "Hyundai"
        if (wmi.startsWith("MAT")) return "Tata"
        if (wmi.startsWith("MA7")) return "Skoda"
        if (wmi.startsWith("MDH")) return "Nissan"
        if (wmi.startsWith("MBH")) return "Nissan" // Often shared or specific models
        if (wmi.startsWith("MHF")) return "Toyota"
        if (wmi.startsWith("MAK")) return "Honda"
        if (wmi.startsWith("MZB")) return "Kia"
        if (wmi.startsWith("MA6")) return "Mahindra"
        
        // VW India
        if (wmi.startsWith("WVW") || wmi.startsWith("MDV")) return "Volkswagen"
        
        return null
    }
}
