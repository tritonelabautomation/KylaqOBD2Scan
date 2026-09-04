import re

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "r") as f:
    text = f.read()

# Replace discoverEcus with runDiscovery
text = text.replace("ecuDiscovery.discoverEcus(transport)", "ecuDiscovery.runDiscovery(transport)")

with open("app/src/main/java/com/example/scheduler/ScanCoordinator.kt", "w") as f:
    f.write(text)
