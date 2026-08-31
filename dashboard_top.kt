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
            protocol = "ISO 15765-4 (CAN 11/500)",
            onConnectClick = onOpenConnectDialog,
            onDisconnectClick = { viewModel.disconnect() }
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
        TelemetryCardsGrid(
            liveMap = liveDecodedMap,
            onPidClick = { pidId -> onNavigateToPidDetail(pidId) }
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Section Title: Experimental Research Channels (016D & 0170)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(ResearchPurple)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RESEARCH CHANNELS (PASSIVE CAPTURE)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ResearchPurple,
                    letterSpacing = 1.sp
                )
            }
            TextButton(onClick = onNavigateToRawMonitor) {
                Text("Raw Monitor", color = CyberCyan)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 016D Research Card
        ResearchPidCard(
            pidId = "016D",
            name = "016D Fuel Pressure Control",
            description = "High-Pressure Direct Injection Rail Channel",
            history = pidRawHistory["016D"] ?: emptyList(),
            onClick = { onNavigateToPidDetail("016D") }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 0170 Research Card
        ResearchPidCard(
            pidId = "0170",
            name = "0170 Boost Pressure Control",
            description = "EA211 Turbo Wastegate & Charge Control Channel",
            history = pidRawHistory["0170"] ?: emptyList(),
            onClick = { onNavigateToPidDetail("0170") }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun VehicleStatusHeader(
