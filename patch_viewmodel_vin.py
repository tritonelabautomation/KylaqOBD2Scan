import re

with open("app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt", "r") as f:
    text = f.read()

# Add _vinDecodeResult and vinDecodeResult
vin_result_state = """    private val _vehicleVin = MutableStateFlow<String?>(null)
    val vehicleVin: StateFlow<String?> = _vehicleVin.asStateFlow()
    
    private val _vinDecodeResult = MutableStateFlow<com.example.protocol.VinDecodeResult?>(null)
    val vinDecodeResult: StateFlow<com.example.protocol.VinDecodeResult?> = _vinDecodeResult.asStateFlow()"""

text = re.sub(r'    private val _vehicleVin = MutableStateFlow<String\?>\(null\)\n    val vehicleVin: StateFlow<String\?> = _vehicleVin\.asStateFlow\(\)', vin_result_state, text)

# Modify fetchVehicleVin
fetch_vin_replacement = """                    val vinMatch = Regex("[A-HJ-NPR-Z0-9]{17}").find(ascii.toString())
                    val extractedVin = vinMatch?.value
                    _vehicleVin.value = extractedVin ?: "VIN Decoded: $ascii"
                    if (extractedVin != null) {
                        _vinDecodeResult.value = com.example.protocol.VinDecoder.decodeVin(extractedVin, catalogRepository)
                    } else {
                        _vinDecodeResult.value = null
                    }"""

text = re.sub(r'                    val vinMatch = Regex\("\[A-HJ-NPR-Z0-9\]\{17\}"\)\.find\(ascii\.toString\(\)\)\n                    _vehicleVin\.value = vinMatch\?\.value \?: "VIN Decoded: \$ascii"', fetch_vin_replacement, text)

with open("app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt", "w") as f:
    f.write(text)
