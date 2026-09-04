import re

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "r") as f:
    text = f.read()

# 1. Fix EcuDiscoveryManager initialization and discoverEcus call
text = text.replace("val ecuDiscovery = EcuDiscoveryManager()\n            val capabilityManager = PidCapabilityManager()", "val capabilityManager = PidCapabilityManager()\n            val ecuDiscovery = EcuDiscoveryManager(capabilityManager)")
text = text.replace("val report = ecuDiscovery.discoverEcus(transport, capabilityManager)", "val report = ecuDiscovery.discoverEcus(transport)")

# 2. Fix decodeDtcResponse -> extractDtcs which I missed in some places
text = text.replace("DtcDecoder.decodeDtcResponse(", "DtcDecoder.extractDtcs(")

# 3. Fix SafetyValidator
text = text.replace("!SafetyValidator.validateCommand(cmd) is com.example.protocol.ValidationResult.Allowed", "SafetyValidator.validateCommand(cmd) !is com.example.protocol.ValidationResult.Allowed")

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "w") as f:
    f.write(text)
