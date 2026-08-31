import re

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'r') as f:
    content = f.read()

new_state = """    private val _batchTestResults = MutableStateFlow<List<com.example.model.ProtocolVerificationResult>>(emptyList())
    val batchTestResults: StateFlow<List<com.example.model.ProtocolVerificationResult>> = _batchTestResults.asStateFlow()
    
    private val _isBatchTesting = MutableStateFlow(false)
    val isBatchTesting: StateFlow<Boolean> = _isBatchTesting.asStateFlow()

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
                transport.sendCommand(proto.atCommand, 1500)
                
                val pidsToTest = listOf("0100", "010C", "010D", "0105", "010B", "0111", "010F", "0142")
                var success = 0
                var timeout = 0
                var invalid = 0
                var unsupported = 0
                var totalTime = 0L
                var minTime = Long.MAX_VALUE
                var maxTime = Long.MIN_VALUE
                
                for (pid in pidsToTest) {
                    val resp = transport.sendCommand(pid, 2000L)
                    val duration = resp.durationMs
                    if (duration > 0) {
                        totalTime += duration
                        if (duration < minTime) minTime = duration
                        if (duration > maxTime) maxTime = duration
                    }
                    if (resp.status == com.example.model.ResponseStatus.OK && resp.lines.isNotEmpty()) {
                        success++
                    } else if (resp.status == com.example.model.ResponseStatus.TIMEOUT) {
                        timeout++
                    } else if (resp.status == com.example.model.ResponseStatus.NO_DATA) {
                        unsupported++
                    } else {
                        invalid++
                    }
                    kotlinx.coroutines.delay(100)
                }
                
                val avgTime = if (success + timeout + invalid + unsupported > 0) totalTime / pidsToTest.size else 0L
                val health = when {
                    success > 0 && success == pidsToTest.size -> com.example.model.ProtocolHealth.WORKING
                    success > 0 -> com.example.model.ProtocolHealth.PARTIAL
                    else -> com.example.model.ProtocolHealth.NO_RESPONSE
                }
                
                results.add(com.example.model.ProtocolVerificationResult(
                    protocol = proto,
                    successCount = success,
                    timeoutCount = timeout,
                    unsupportedCount = unsupported,
                    invalidCount = invalid,
                    totalRequests = pidsToTest.size,
                    avgResponseTimeMs = avgTime,
                    minResponseTimeMs = if (minTime == Long.MAX_VALUE) 0L else minTime,
                    maxResponseTimeMs = if (maxTime == Long.MIN_VALUE) 0L else maxTime,
                    health = health
                ))
                _batchTestResults.value = results.toList()
                
                // Allow user to cancel? Just sequential.
            }
            
            _isBatchTesting.value = false
            
            // If best found, auto-select it? Or leave it to the user.
        }
    }
"""

# Insert right after `fun verifySelectedProtocol()` block
pattern = re.compile(r'    fun verifySelectedProtocol\(\) \{.*?\n    \}\n', re.DOTALL)

match = pattern.search(content)
if match:
    content = content[:match.end()] + "\n" + new_state + content[match.end():]

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'w') as f:
    f.write(content)

