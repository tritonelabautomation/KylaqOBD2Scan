import re

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'r') as f:
    content = f.read()

new_top = """@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onNavigateToRawMonitor: () -> Unit,
    onNavigateToPidDetail: (String) -> Unit,
    onOpenConnectDialog: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()
    val isPolling by viewModel.isPolling.collectAsState()
    val transactionCount by viewModel.transactionCount.collectAsState()
    val canResponseCount by viewModel.canResponseCount.collectAsState()
    val errorCount by viewModel.errorCount.collectAsState()
    val liveDecodedMap by viewModel.liveDecodedMap.collectAsState()
    val pidRawHistory by viewModel.pidRawHistory.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingDurationSeconds by viewModel.recordingDurationSeconds.collectAsState()
    val pollingMode by viewModel.pollingMode.collectAsState()
    val vehicleName by viewModel.vehicleName.collectAsState()
    
    val selectedCanProtocol by viewModel.selectedCanProtocol.collectAsState()
    val protocolHealth by viewModel.protocolHealth.collectAsState()
    val protocolResult by viewModel.protocolVerificationResult.collectAsState()

    val isConnected = connectionState == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("dashboard_screen")
    ) {
        // Vehicle & Connection Status Banner
        VehicleStatusHeader(
            vehicleName = vehicleName,
            adapterName = connectedDeviceName ?: "Not Connected",
            connectionState = connectionState,
            protocol = selectedCanProtocol.displayName,
            onConnectClick = onOpenConnectDialog,
            onDisconnectClick = { viewModel.disconnect() }
        )
        
        Spacer(modifier = Modifier.height(14.dp))
        
        // Protocol Verification Selector
        ProtocolVerificationControl(
            selectedProtocol = selectedCanProtocol,
            health = protocolHealth,
            result = protocolResult,
            isConnected = isConnected,
            onProtocolSelected = { viewModel.selectCanProtocol(it) },
            onVerify = { viewModel.verifySelectedProtocol() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Recording & Polling Control Bar
        RecordingControlBar(
            isRecording = isRecording,
            recordingDurationSeconds = recordingDurationSeconds,
            isPolling = isPolling,
            isConnected = isConnected,
            transactionCount = transactionCount,
            canResponseCount = canResponseCount,
            errorCount = errorCount,
            onStartRecording = { viewModel.startRecording() },
            onStopRecording = { viewModel.stopRecording() },
            onTogglePolling = { viewModel.togglePolling() }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Polling Mode Selector (Safe / Normal / Fast)
        PollingModeSelector(
            currentMode = pollingMode,
            onModeSelected = { viewModel.setPollingSpeedMode(it) }
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Section Title: Live Telemetry
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "LIVE TELEMETRY (EA211)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CyberCyan,
                letterSpacing = 1.sp
            )
            Text(
                text = "${liveDecodedMap.size} PIDs Active",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        // Primary Live Telemetry Cards Grid
        if (protocolHealth == ProtocolHealth.UNKNOWN || protocolHealth == ProtocolHealth.NO_RESPONSE || protocolHealth == ProtocolHealth.TESTING) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (protocolHealth == ProtocolHealth.TESTING) {
                        CircularProgressIndicator(color = CyberCyan)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Waiting for ECU response...", color = CyberCyan)
                    } else {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningRed, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (protocolHealth == ProtocolHealth.NO_RESPONSE) "NO VALID ECU RESPONSE" else "CAN Protocol not verified.",
                            color = WarningRed,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Please verify protocol to view telemetry.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        } else {
            TelemetryCardsGrid(
                liveMap = liveDecodedMap,
                onPidClick = { pidId -> onNavigateToPidDetail(pidId) }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
"""

# Replace the old DashboardScreen block with the new one
pattern = re.compile(r'@Composable\nfun DashboardScreen\(.*?\n    }\n}', re.DOTALL)
content = pattern.sub(new_top, content)

with open('app/src/main/java/com/example/ui/screens/DashboardScreen.kt', 'w') as f:
    f.write(content)

