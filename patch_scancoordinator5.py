import re

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "r") as f:
    text = f.read()

# I may not have properly replaced decodeDtcResponse
text = text.replace("DtcDecoder.decodeDtcResponse(", "DtcDecoder.extractDtcs(")

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "w") as f:
    f.write(text)
