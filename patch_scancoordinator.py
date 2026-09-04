import re

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "r") as f:
    text = f.read()

text = text.replace("import com.example.bluetooth.Elm327Transport", "import com.example.bluetooth.ElmTransport")
text = text.replace("private val transport: Elm327Transport", "private val transport: ElmTransport")
text = text.replace("val ecuDiscovery = EcuDiscoveryManager()", "val ecuDiscovery = EcuDiscoveryManager()\n            val capabilityManager = PidCapabilityManager()")
text = text.replace("val supportedPidsResponse = transport.sendCommand(\"0100\", 2000)\n            val discoveredEcus = ecuDiscovery.extractEcusFrom0100(supportedPidsResponse.lines)\n            for (ecuAddress in discoveredEcus) {", "val report = ecuDiscovery.discoverEcus(transport, capabilityManager)\n            for (discovered in report.detectedEcus) {\n                val ecuAddress = discovered.rxCanId")
text = text.replace("ecuAddress == \"7EA\"", "ecuAddress == \"7E9\" || ecuAddress == \"7EA\"")
text = text.replace("rawEvidence = supportedPidsResponse.rawAscii", "rawEvidence = null")
text = text.replace("for (ecu in discoveredEcus) {\n                // Here we'd map the bits from 0100, 0120, 0140, etc.\n                // Simplified for now\n            }", """for (discovered in report.detectedEcus) {
                for (pid in discovered.supportedPids) {
                    pidCapabilities.add(PidCapabilityEntity(
                        vehicleId = vehicleId,
                        ecuAddress = discovered.rxCanId,
                        pid = pid,
                        supported = true,
                        lastVerified = System.currentTimeMillis(),
                        responseLatency = discovered.averageLatencyMs,
                        failureCount = 0,
                        confidence = "OBSERVED"
                    ))
                }
            }""")
text = text.replace("DtcDecoder()", "DtcDecoder")
text = text.replace("DtcDecoder.decodeDtcResponse(", "DtcDecoder.extractDtcs(")
text = text.replace("transport.deviceName", "\"ELM327\"")
text = text.replace("transport.deviceAddress", "\"00:00:00:00:00:00\"")
text = text.replace("SafetyValidator.isCommandSafe(cmd)", "SafetyValidator.isCommandSafe(cmd, \"01\", cmd)")
text = text.replace("transport.sendCommand(cmd, timeoutMs)", "transport.sendCommand(cmd, timeoutMs)")


with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "w") as f:
    f.write(text)
