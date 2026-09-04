import re

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "r") as f:
    text = f.read()

text = text.replace("SafetyValidator.isCommandSafe(cmd, \"01\", cmd)", "SafetyValidator.validateCommand(cmd) is com.example.protocol.ValidationResult.Allowed")

# DtcDecoder.extractDtcs requires only one argument payloadHex, but I replaced it with two.
text = text.replace("DtcDecoder.extractDtcs(mode03.lines, \"03\")", "DtcDecoder.extractDtcs(mode03.lines.joinToString(\"\"))")
text = text.replace("DtcDecoder.extractDtcs(mode07.lines, \"07\")", "DtcDecoder.extractDtcs(mode07.lines.joinToString(\"\"))")
text = text.replace("DtcDecoder.extractDtcs(mode0A.lines, \"0A\")", "DtcDecoder.extractDtcs(mode0A.lines.joinToString(\"\"))")

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "w") as f:
    f.write(text)
