with open("app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt", "r") as f:
    text = f.read()

dtc_methods = """
    fun fetchActiveDtcs() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            val resp = transport.sendCommand("03", 5000L)
            val isoTp = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)
            val allDtcs = mutableListOf<String>()
            for (msg in isoTp) {
                allDtcs.addAll(com.example.protocol.DtcDecoder.extractDtcs(msg.reconstructedPayloadHex))
            }
            saveDtcs(allDtcs.distinct(), "CONFIRMED")
        }
    }

    fun fetchPendingDtcs() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            val resp = transport.sendCommand("07", 5000L)
            val isoTp = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)
            val allDtcs = mutableListOf<String>()
            for (msg in isoTp) {
                allDtcs.addAll(com.example.protocol.DtcDecoder.extractDtcs(msg.reconstructedPayloadHex))
            }
            saveDtcs(allDtcs.distinct(), "PENDING")
        }
    }

    fun clearDtcs() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            transport.sendCommand("04", 5000L)
            // After clearing, fetch again to confirm
            kotlinx.coroutines.delay(1000)
            fetchActiveDtcs()
        }
    }

    private suspend fun saveDtcs(dtcs: List<String>, status: String) {
        val vehicleId = recordingManager.tripRepository.allVehiclesFlow.kotlinx.coroutines.flow.firstOrNull()?.firstOrNull()?.id // Simplified
        val timestamp = System.currentTimeMillis()
        
        for (code in dtcs) {
            val entity = com.example.data.db.entities.DtcRecordEntity(
                vehicleId = vehicleId,
                tripId = recordingManager.currentTripId,
                timestamp = timestamp,
                code = code,
                description = "Diagnostic Trouble Code",
                status = status
            )
            recordingManager.tripRepository.insertDtcRecord(entity)
        }
    }
"""

text = text.replace("    fun stopRecording() {", dtc_methods + "\n    fun stopRecording() {")

import_add = "import kotlinx.coroutines.flow.firstOrNull\n"
text = text.replace("import kotlinx.coroutines.flow.StateFlow", import_add + "import kotlinx.coroutines.flow.StateFlow")

with open("app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt", "w") as f:
    f.write(text)
