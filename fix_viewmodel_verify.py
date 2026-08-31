import re

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'r') as f:
    content = f.read()

new_verify_method = """    companion object {
        const val PROTOCOL_SETTLE_DELAY_MS = 400L
    }

    fun verifySelectedProtocol() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            _protocolHealth.value = com.example.model.ProtocolHealth.TESTING
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            
            transport.sendCommand("ATPC", 1000)
            kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
            
            val proto = _selectedCanProtocol.value
            transport.sendCommand(proto.atCommand, 1500)
            kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
            
            val result = executeProtocolVerification(transport, proto)
            
            _protocolVerificationResult.value = result
            _protocolHealth.value = result.health
            
            if (result.health == com.example.model.ProtocolHealth.WORKING || result.health == com.example.model.ProtocolHealth.PARTIAL) {
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private suspend fun executeProtocolVerification(transport: com.example.bluetooth.ElmTransport, proto: com.example.model.CanProtocol): com.example.model.ProtocolVerificationResult {
        val pidsToTest = listOf("0100", "010C", "010D", "0105", "010B", "0111", "010F", "0142")
        var success = 0
        var timeout = 0
        var invalid = 0
        var unsupported = 0
        var canErrorCount = 0
        var totalTime = 0L
        var minTime = Long.MAX_VALUE
        var maxTime = Long.MIN_VALUE
        
        val pidResults = mutableListOf<com.example.model.PidTestResult>()
        
        for (pid in pidsToTest) {
            val resp = transport.sendCommand(pid, 2000L)
            val duration = resp.durationMs
            
            if (duration > 0) {
                totalTime += duration
                if (duration < minTime) minTime = duration
                if (duration > maxTime) maxTime = duration
            }
            
            val expectedService = "41"
            val expectedPid = pid.substring(2)
            
            val hasEcuResponse = resp.lines.any { line -> 
                val cleanLine = line.replace(" ", "").uppercase()
                cleanLine.contains(expectedService + expectedPid)
            }
            
            val status = when {
                hasEcuResponse -> {
                    success++
                    com.example.model.PidTestStatus.ECU_RESPONSE
                }
                resp.status == com.example.model.ResponseStatus.NO_DATA -> {
                    unsupported++
                    com.example.model.PidTestStatus.NO_DATA
                }
                resp.status == com.example.model.ResponseStatus.TIMEOUT -> {
                    timeout++
                    com.example.model.PidTestStatus.TIMEOUT
                }
                resp.status == com.example.model.ResponseStatus.CAN_ERROR || resp.status == com.example.model.ResponseStatus.BUS_INIT_ERROR -> {
                    canErrorCount++
                    com.example.model.PidTestStatus.CAN_ERROR
                }
                resp.status == com.example.model.ResponseStatus.UNABLE_TO_CONNECT || resp.status == com.example.model.ResponseStatus.MALFORMED -> {
                    invalid++
                    com.example.model.PidTestStatus.ADAPTER_ERROR
                }
                else -> {
                    invalid++
                    com.example.model.PidTestStatus.MALFORMED
                }
            }
            
            pidResults.add(
                com.example.model.PidTestResult(
                    txCommand = pid,
                    rxResponse = if (resp.lines.isNotEmpty()) resp.lines.joinToString(" ") else resp.rawText.trim(),
                    status = status,
                    latencyMs = duration
                )
            )
            
            kotlinx.coroutines.delay(100)
        }
        
        val avgTime = if (pidsToTest.isNotEmpty()) totalTime / pidsToTest.size else 0L
        
        // Protocol health categorization
        val health = when {
            success > 1 -> com.example.model.ProtocolHealth.WORKING
            success == 1 -> com.example.model.ProtocolHealth.PARTIAL
            canErrorCount > 0 || invalid > 0 || timeout == pidsToTest.size -> com.example.model.ProtocolHealth.NO_RESPONSE
            else -> com.example.model.ProtocolHealth.ADAPTER_ERROR
        }
        
        val appVer = com.example.BuildConfig.VERSION_NAME
        val appBuild = com.example.BuildConfig.VERSION_CODE
        val commit = com.example.BuildConfig.GIT_COMMIT
        
        return com.example.model.ProtocolVerificationResult(
            protocol = proto,
            successCount = success,
            timeoutCount = timeout,
            unsupportedCount = unsupported,
            invalidCount = invalid,
            canErrorCount = canErrorCount,
            totalRequests = pidsToTest.size,
            avgResponseTimeMs = avgTime,
            minResponseTimeMs = if (minTime == Long.MAX_VALUE) 0L else minTime,
            maxResponseTimeMs = if (maxTime == Long.MIN_VALUE) 0L else maxTime,
            health = health,
            pidResults = pidResults,
            appVersion = appVer,
            buildNumber = appBuild,
            commitHash = commit
        )
    }

    fun testAllCanProtocols() {
        val transport = activeTransport ?: return
        if (!transport.isConnected) return
        
        viewModelScope.launch {
            _isBatchTesting.value = true
            _batchTestResults.value = emptyList()
            obdScheduler.stopPolling()
            obdScheduler.resetCounters()
            
            val protocolsToTest = listOf(
                com.example.model.CanProtocol.ISO_15765_11B_500K,
                com.example.model.CanProtocol.ISO_15765_29B_500K,
                com.example.model.CanProtocol.ISO_15765_11B_250K,
                com.example.model.CanProtocol.ISO_15765_29B_250K
            )
            
            val results = mutableListOf<com.example.model.ProtocolVerificationResult>()
            
            for (proto in protocolsToTest) {
                transport.sendCommand("ATPC", 1000)
                kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
                transport.sendCommand(proto.atCommand, 1500)
                kotlinx.coroutines.delay(PROTOCOL_SETTLE_DELAY_MS)
                
                val result = executeProtocolVerification(transport, proto)
                results.add(result)
                _batchTestResults.value = results.toList()
                
                // Safety delay before testing next protocol
                kotlinx.coroutines.delay(500)
            }
            
            _isBatchTesting.value = false
            
            // Auto-select best protocol
            val best = rankProtocols(results).firstOrNull()
            if (best != null && (best.health == com.example.model.ProtocolHealth.WORKING || best.health == com.example.model.ProtocolHealth.PARTIAL)) {
                _selectedCanProtocol.value = best.protocol
                _protocolVerificationResult.value = best
                _protocolHealth.value = best.health
                obdScheduler.startPolling(viewModelScope, transport)
            }
        }
    }

    private fun rankProtocols(results: List<com.example.model.ProtocolVerificationResult>): List<com.example.model.ProtocolVerificationResult> {
        return results.sortedWith(
            compareBy<com.example.model.ProtocolVerificationResult> { 
                when (it.health) {
                    com.example.model.ProtocolHealth.WORKING -> 0
                    com.example.model.ProtocolHealth.PARTIAL -> 1
                    com.example.model.ProtocolHealth.NO_RESPONSE -> 2
                    com.example.model.ProtocolHealth.ADAPTER_ERROR -> 3
                    else -> 4
                }
            }
            .thenByDescending { it.successCount }
            .thenBy { it.canErrorCount + it.invalidCount + it.timeoutCount }
            .thenBy { it.avgResponseTimeMs }
        )
    }
"""

# Replace the verifySelectedProtocol and testAllCanProtocols block
pattern = re.compile(r'    fun verifySelectedProtocol\(\) \{.*?(?=\n    fun connectDevice)', re.DOTALL)
content = pattern.sub(new_verify_method, content)

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'w') as f:
    f.write(content)

