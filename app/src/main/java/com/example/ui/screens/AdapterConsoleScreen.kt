package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.bluetooth.ConnectionState
import com.example.discovery.AdapterBenchmarkReport
import com.example.discovery.Module44ProbeStatus
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdapterConsoleScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rawLogs by viewModel.rawLogs.collectAsState()
    val manualOutput by viewModel.manualCommandOutput.collectAsState()
    val manualError by viewModel.manualCommandError.collectAsState()
    val isBenchmarking by viewModel.isBenchmarkingAdapter.collectAsState()
    val benchmarkReport by viewModel.adapterBenchmarkReport.collectAsState()
    val listState = rememberLazyListState()

    var inputCommand by remember { mutableStateOf("") }
    var showBenchmarkModal by remember { mutableStateOf(false) }

    val quickCommands = listOf("ATZ", "ATI", "AT@1", "AT@2", "STI", "ATRV", "ATDP", "22 F1 90", "09 02", "ATSH 714", "ATCRA 77E", "22 02 00")

    LaunchedEffect(rawLogs.size) {
        if (rawLogs.isNotEmpty()) {
            listState.animateScrollToItem(rawLogs.size - 1)
        }
    }

    LaunchedEffect(benchmarkReport) {
        if (benchmarkReport != null) {
            showBenchmarkModal = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("adapter_console_screen")
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("btn_console_back")) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Adapter Console",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text("Direct ELM327 / UDS Terminal", fontSize = 11.sp, color = TextSecondaryDark)
                }
            }

            Row {
                IconButton(
                    onClick = {
                        shareConsoleLog(context, viewModel.getAllRawLogText())
                    },
                    modifier = Modifier.testTag("btn_share_log")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share Log", tint = NeonEmerald)
                }

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("OBD Raw Log", viewModel.getAllRawLogText())
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.testTag("btn_copy_log")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Log", tint = CyberCyan)
                }

                IconButton(
                    onClick = { viewModel.clearRawLog() },
                    modifier = Modifier.testTag("btn_clear_log")
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Log", tint = WarningRed)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Hardware Capability Benchmark Action Button
        Button(
            onClick = { viewModel.runAdapterBenchmark() },
            enabled = !isBenchmarking,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.25f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (isBenchmarking) "Running 4-Phase Benchmark..." else "Run Hardware & UDS Benchmark (4-Phase Test)",
                color = CyberCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Command Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickCommands.forEach { cmd ->
                SuggestionChip(
                    onClick = {
                        inputCommand = cmd
                        viewModel.sendManualCommand(cmd)
                    },
                    label = { Text(cmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Terminal Output Screen
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF06090D)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2633))
        ) {
            if (rawLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ELM327 Terminal Idle.\nTap quick commands above or enter AT / UDS commands below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(rawLogs, key = { it.id }) { log ->
                        val textColor = when {
                            log.isTx -> ElectricAmber
                            log.status == "ERROR" || log.status == "BLOCKED" -> WarningRed
                            log.status == "OK" -> NeonEmerald
                            else -> TextPrimaryDark
                        }

                        Text(
                            text = log.toFormattedLine(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = textColor,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        manualError?.let { err ->
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = WarningRed.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = err,
                    color = WarningRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        manualOutput?.let { out ->
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = CyberCyan.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Response: $out",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Manual Command Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                label = { Text("Command (e.g. 22 F1 90)") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )

            Button(
                onClick = {
                    if (inputCommand.isNotBlank()) {
                        viewModel.sendManualCommand(inputCommand)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.Black)
            }
        }
    }

    // Benchmark Report Dialog
    if (showBenchmarkModal && benchmarkReport != null) {
        val rep = benchmarkReport!!
        AlertDialog(
            onDismissRequest = { showBenchmarkModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Assessment, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Adapter & UDS Hardware Benchmark", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("MICROCONTROLLER & CHIP VERDICT", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(rep.chipVerdict, color = if (rep.isCloneOrCounterfeit) WarningRed else NeonEmerald, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Identifier: ${rep.chipIdentifier}", color = TextSecondaryDark, fontSize = 11.sp)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ISO-TP & PROTOCOL CAPABILITY", color = ElectricAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            BenchmarkCheckRow("Custom CAN Headers (ATSH/ATCRA)", rep.customHeadersSupported)
                            BenchmarkCheckRow("CAN Flow Control (ATFC...)", rep.flowControlSupported)
                            BenchmarkCheckRow("Multi-Frame ISO-TP Reassembly", rep.multiFrameIsoTpVerified)
                            rep.vinMultiFrameReassembled?.let {
                                Text("Reassembled VIN: $it", color = NeonEmerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceElevated,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("MODULE 44 (STEERING ASSIST J500) PROBE", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            val (statusText, statusColor) = when (rep.module44Status) {
                                Module44ProbeStatus.RESPONDED_POSITIVE -> "Positive Response (62 02 00)" to NeonEmerald
                                Module44ProbeStatus.NRC_OUT_OF_RANGE_31 -> "NRC 0x31 (DID 0200 Not Supported by this EPS FW)" to ElectricAmber
                                Module44ProbeStatus.NRC_SECURITY_DENIED_33 -> "NRC 0x33 (Security / SFD Protected)" to WarningRed
                                Module44ProbeStatus.NRC_SESSION_REQUIRED_7E -> "NRC 0x7E (Requires 10 03 Extended Session)" to ElectricAmber
                                Module44ProbeStatus.GATEWAY_NO_DATA_OR_TIMEOUT -> "No Response / Gateway Filter Blocked" to TextSecondaryDark
                                else -> "NRC Error: ${rep.module44RawResponse}" to WarningRed
                            }
                            Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            rep.module44RawResponse?.let {
                                Text("Raw: $it", color = TextSecondaryDark, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBenchmarkModal = false }) {
                    Text("Close", color = CyberCyan)
                }
            }
        )
    }
}

@Composable
private fun BenchmarkCheckRow(label: String, passed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextPrimaryDark, fontSize = 11.sp)
        Text(
            if (passed) "PASSED" else "FAILED / UNKNOWN",
            color = if (passed) NeonEmerald else WarningRed,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun shareConsoleLog(context: Context, logText: String) {
    if (logText.isBlank()) {
        Toast.makeText(context, "Log is empty, nothing to export", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val rawLogsDir = File(context.filesDir, "raw_logs")
        if (!rawLogsDir.exists()) {
            rawLogsDir.mkdirs()
        }

        val timeStamp = com.example.data.RecordTime.format("yyyyMMdd_HHmmss", System.currentTimeMillis())
        val logFile = File(rawLogsDir, "ELM327_Console_$timeStamp.txt")
        logFile.writeText(logText)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Console Log")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "Share error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
