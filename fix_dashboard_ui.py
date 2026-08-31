import re

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'r') as f:
    content = f.read()

# Add App Version to the header block
version_header = """
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("dashboard_screen")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("OBD Logger", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(horizontalAlignment = Alignment.End) {
                Text("v${com.example.BuildConfig.VERSION_NAME} (Build ${com.example.BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Commit: ${com.example.BuildConfig.GIT_COMMIT}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        
        // Vehicle & Connection Status Banner
"""
content = content.replace(
"""    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("dashboard_screen")
    ) {
        // Vehicle & Connection Status Banner""", version_header)

# Update ProtocolVerificationControl definition
new_protocol_control = """@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolVerificationControl(
    selectedProtocol: CanProtocol,
    health: ProtocolHealth,
    result: ProtocolVerificationResult?,
    isConnected: Boolean,
    onProtocolSelected: (CanProtocol) -> Unit,
    onVerify: () -> Unit,
    onShowBatchTest: () -> Unit,
    onShowDiagnostics: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Selected OBD-II Profile", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
            Spacer(modifier = Modifier.height(8.dp))
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedProtocol.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    CanProtocol.values().forEach { proto ->
                        DropdownMenuItem(
                            text = { Text(proto.displayName) },
                            onClick = {
                                onProtocolSelected(proto)
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (health) {
                    ProtocolHealth.WORKING -> NeonEmerald
                    ProtocolHealth.PARTIAL -> ElectricAmber
                    ProtocolHealth.NO_RESPONSE -> WarningRed
                    ProtocolHealth.ADAPTER_ERROR -> WarningRed
                    ProtocolHealth.TESTING -> CyberCyan
                    ProtocolHealth.UNKNOWN -> Color.Gray
                }
                val statusText = when (health) {
                    ProtocolHealth.WORKING -> "🟢 WORKING"
                    ProtocolHealth.PARTIAL -> "🟡 PARTIAL"
                    ProtocolHealth.NO_RESPONSE -> "🔴 NO_RESPONSE"
                    ProtocolHealth.ADAPTER_ERROR -> "🔴 ADAPTER_ERROR"
                    ProtocolHealth.TESTING -> "🔵 TESTING..."
                    ProtocolHealth.UNKNOWN -> "⚪ UNVERIFIED"
                }
                
                Column {
                    Text("Verification", style = MaterialTheme.typography.labelSmall)
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
            }

            if (result != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color.DarkGray)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("ECU responses: ${result.successCount}/${result.totalRequests}", style = MaterialTheme.typography.bodySmall, color = NeonEmerald)
                        Text("Unsupported: ${result.unsupportedCount}", style = MaterialTheme.typography.bodySmall, color = ElectricAmber)
                        Text("Timeouts: ${result.timeoutCount}", style = MaterialTheme.typography.bodySmall, color = WarningRed)
                        Text("CAN errors: ${result.canErrorCount}", style = MaterialTheme.typography.bodySmall, color = WarningRed)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = onShowDiagnostics, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                            Text("Diagnostics", fontSize = 12.sp, color = CyberCyan)
                        }
                        Text("Avg Latency: ${result.avgResponseTimeMs} ms", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
"""

content = re.sub(r'@OptIn\(ExperimentalMaterial3Api::class\)\n@Composable\nfun ProtocolVerificationControl\(.*?(?=\n@Composable\nfun StatusBadge)', new_protocol_control, content, flags=re.DOTALL)

# Add showDiagnostics dialog state in DashboardScreen
new_dashboard_usage = """        var showBatchTestDialog by remember { mutableStateOf(false) }
        var showDiagnosticsDialog by remember { mutableStateOf(false) }

        // Protocol Verification Selector
        ProtocolVerificationControl(
            selectedProtocol = selectedCanProtocol,
            health = protocolHealth,
            result = protocolResult,
            isConnected = isConnected,
            onProtocolSelected = { viewModel.selectCanProtocol(it) },
            onVerify = { viewModel.verifySelectedProtocol() },
            onShowBatchTest = { showBatchTestDialog = true },
            onShowDiagnostics = { showDiagnosticsDialog = true }
        )

        if (showBatchTestDialog) {
            val batchResults by viewModel.batchTestResults.collectAsState()
            val isBatchTesting by viewModel.isBatchTesting.collectAsState()
            ProtocolComparisonDialog(
                results = batchResults,
                isTesting = isBatchTesting,
                onStartTest = { viewModel.testAllCanProtocols() },
                onDismiss = { showBatchTestDialog = false },
                onApply = { 
                    if(it != null) {
                        viewModel.selectCanProtocol(it.protocol)
                        showBatchTestDialog = false
                    }
                }
            )
        }
        
        if (showDiagnosticsDialog && protocolResult != null) {
            ProtocolDiagnosticsDialog(
                result = protocolResult,
                onDismiss = { showDiagnosticsDialog = false }
            )
        }"""

content = re.sub(r'        var showBatchTestDialog by remember \{ mutableStateOf\(false\) \}.*?(?=\n        Spacer\(modifier = Modifier.height\(14.dp\)\))', new_dashboard_usage, content, flags=re.DOTALL)

# Ensure "CAN Protocol not verified." box has exactly the right text
content = content.replace("NO VALID ECU RESPONSE", "NO ECU RESPONSE")

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'w') as f:
    f.write(content)

