import re

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'r') as f:
    content = f.read()

# Add button to ProtocolVerificationControl
new_control_top = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolVerificationControl(
    selectedProtocol: CanProtocol,
    health: ProtocolHealth,
    result: ProtocolVerificationResult?,
    isConnected: Boolean,
    onProtocolSelected: (CanProtocol) -> Unit,
    onVerify: () -> Unit,
    onShowBatchTest: () -> Unit
) {"""

content = content.replace(
"""@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolVerificationControl(
    selectedProtocol: CanProtocol,
    health: ProtocolHealth,
    result: ProtocolVerificationResult?,
    isConnected: Boolean,
    onProtocolSelected: (CanProtocol) -> Unit,
    onVerify: () -> Unit
) {""", new_control_top)

new_buttons = """                Column {
                    Text("Protocol Health", style = MaterialTheme.typography.labelSmall)
                    Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onShowBatchTest,
                        enabled = isConnected,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Test All", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onVerify,
                        enabled = isConnected && health != ProtocolHealth.TESTING,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, disabledContainerColor = Color.DarkGray),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(if (health == ProtocolHealth.TESTING) "Testing..." else "Test Protocol", fontSize = 12.sp)
                    }
                }
            }"""

content = re.sub(r'                Column \{\n.*?Protocol Health.*?\}\n\n                Button\([\s\S]*?\}', new_buttons, content)

# Now update the usage of ProtocolVerificationControl in DashboardScreen
content = content.replace(
"""        // Protocol Verification Selector
        ProtocolVerificationControl(
            selectedProtocol = selectedCanProtocol,
            health = protocolHealth,
            result = protocolResult,
            isConnected = isConnected,
            onProtocolSelected = { viewModel.selectCanProtocol(it) },
            onVerify = { viewModel.verifySelectedProtocol() }
        )""",
"""        var showBatchTestDialog by remember { mutableStateOf(false) }

        // Protocol Verification Selector
        ProtocolVerificationControl(
            selectedProtocol = selectedCanProtocol,
            health = protocolHealth,
            result = protocolResult,
            isConnected = isConnected,
            onProtocolSelected = { viewModel.selectCanProtocol(it) },
            onVerify = { viewModel.verifySelectedProtocol() },
            onShowBatchTest = { showBatchTestDialog = true }
        )

        if (showBatchTestDialog) {
            val batchResults by viewModel.batchTestResults.collectAsState()
            val isBatchTesting by viewModel.isBatchTesting.collectAsState()
            ProtocolComparisonDialog(
                results = batchResults,
                isTesting = isBatchTesting,
                onStartTest = { viewModel.testAllCanProtocols() },
                onDismiss = { showBatchTestDialog = false }
            )
        }"""
)

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'w') as f:
    f.write(content)

