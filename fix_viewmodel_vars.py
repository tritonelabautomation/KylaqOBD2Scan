import re

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'r') as f:
    content = f.read()

# Add missing vars
missing_vars = """
    private val _isBatchTesting = MutableStateFlow(false)
    val isBatchTesting = _isBatchTesting.asStateFlow()

    private val _batchTestResults = MutableStateFlow<List<com.example.model.ProtocolVerificationResult>>(emptyList())
    val batchTestResults = _batchTestResults.asStateFlow()
"""

# Inject before `fun verifySelectedProtocol`
content = content.replace("    companion object {", missing_vars + "    companion object {")

with open('app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt', 'w') as f:
    f.write(content)

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'r') as f:
    content = f.read()

# Fix smart cast
content = content.replace('if (showDiagnosticsDialog && protocolResult != null) {', 'protocolResult?.let { pr ->\n        if (showDiagnosticsDialog) {\n            ProtocolDiagnosticsDialog(\n                result = pr,\n                onDismiss = { showDiagnosticsDialog = false }\n            )\n        }\n    }')
content = content.replace('            ProtocolDiagnosticsDialog(\n                result = protocolResult,\n                onDismiss = { showDiagnosticsDialog = false }\n            )\n        }', '')

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'w') as f:
    f.write(content)

