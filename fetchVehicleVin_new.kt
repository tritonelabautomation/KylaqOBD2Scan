    fun fetchVehicleVin() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return

        viewModelScope.launch {
            val resp = transport.sendCommand("0902", 3000L)

            if (resp.status != com.example.model.ResponseStatus.OK || resp.lines.isEmpty()) {
                _vehicleVin.value = "VIN Unavailable"
                _vinDecodeResult.value = null
                return@launch
            }

            try {
                // Mode 09 PID 02 response structure (SAE J1979):
                // Byte 0 = 0x49 (Mode 09 + 0x40)
                // Byte 1 = 0x02 (PID for VIN)
                // Byte 2 = 0x01 (VIN record indicator)
                // Bytes 3-19 = exactly 17 ASCII VIN characters
                //
                // Total payload: exactly 20 bytes.
                //
                // Response may arrive as ISO-TP multi-frame:
                // 7E8 10 14 49 02 01 ...
                // 7E8 21 ...
                // 7E8 22 ...
                //
                // ISO-TP ensures frames from different CAN IDs are kept separate.
                // VIN ECU Authority Model: Only 7E8 (Engine) and 7E1 (Transmission)
                // are authoritative for VIN. Other ECUs cannot provide VIN.

                val allMessages = com.example.protocol.IsoTpParser.reassembleLines(resp.lines)

                // Collect all valid VIN candidates with their source ECU
                val candidates = com.example.protocol.VinAuthority.collectVinCandidates(allMessages)

                // Apply VIN ECU authority policy
                val result = com.example.protocol.VinAuthority.selectVinByAuthority(candidates)

                when (result) {
                    is com.example.protocol.VinSelectionResult.Success -> {
                        _vehicleVin.value = result.vin
                        _vinDecodeResult.value = com.example.protocol.VinDecoder.decodeVin(result.vin, catalogRepository)
                    }
                    is com.example.protocol.VinSelectionResult.Ambiguous -> {
                        _vehicleVin.value = "VIN Ambiguous"
                        _vinDecodeResult.value = null
                    }
                    is com.example.protocol.VinSelectionResult.Unavailable -> {
                        _vehicleVin.value = "VIN Unavailable"
                        _vinDecodeResult.value = null
                    }
                }

            } catch (e: Exception) {
                _vehicleVin.value = "Failed to parse VIN"
                _vinDecodeResult.value = null
            }
        }
    }
