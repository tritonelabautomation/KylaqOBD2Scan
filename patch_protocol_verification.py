with open("app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt", "r") as f:
    text = f.read()

execute_sig = "private suspend fun executeProtocolVerification(transport: com.example.bluetooth.ElmTransport, proto: com.example.model.CanProtocol): com.example.model.ProtocolVerificationResult {"
execute_new = """private suspend fun executeProtocolVerification(transport: com.example.bluetooth.ElmTransport, proto: com.example.model.CanProtocol): com.example.model.ProtocolVerificationResult {
        // Set protocol
        transport.sendCommand(proto.atCommand, 1500L)
        kotlinx.coroutines.delay(200)

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
        
        // Warm up and negotiate
        transport.sendCommand("0100", 3000L)
        
        // Find resolved protocol if Auto
        var resolvedProto = proto
        if (proto.atCommand == "ATSP0") {
            val dpn = transport.sendCommand("ATDPN", 1000L)
            var dpnVal = dpn.rawText.trim().replace(">", "").trim()
            if (dpnVal.length > 0 && dpnVal.first().isLetter()) {
                dpnVal = dpnVal.substring(1) // sometimes "A6" for auto 6
            }
            if (dpnVal.isNotEmpty()) {
                val matched = com.example.model.CanProtocol.values().find { it.protocolNumber == dpnVal }
                if (matched != null) {
                    resolvedProto = matched
                }
            }
        }

        for (pid in pidsToTest) {"""

import re
text = re.sub(r'private suspend fun executeProtocolVerification.*?for \(pid in pidsToTest\) \{', execute_new, text, flags=re.DOTALL)

text = text.replace("protocol = proto,", "protocol = resolvedProto,")

with open("app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt", "w") as f:
    f.write(text)
