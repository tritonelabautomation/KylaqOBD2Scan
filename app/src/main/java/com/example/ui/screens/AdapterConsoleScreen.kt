package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdapterConsoleScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val rawLogs by viewModel.rawLogs.collectAsState()
    val manualOutput by viewModel.manualCommandOutput.collectAsState()
    val manualError by viewModel.manualCommandError.collectAsState()
    val listState = rememberLazyListState()

    var inputCommand by remember { mutableStateOf("") }

    LaunchedEffect(rawLogs.size) {
        if (rawLogs.isNotEmpty()) {
            listState.animateScrollToItem(rawLogs.size - 1)
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
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Adapter Console",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
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

        // Terminal Output Screen
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF06090D))
        ) {
            if (rawLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ELM327 Terminal Idle.\nConnect adapter or send AT commands below (e.g. ATRV, ATDP, 0100).",
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
                        .padding(10.dp)
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

        Spacer(modifier = Modifier.height(10.dp))

        // Adapter Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Adapter Information", style = MaterialTheme.typography.titleMedium, color = CyberCyan)
                Spacer(modifier = Modifier.height(8.dp))
                val isConnected = viewModel.connectionState.collectAsState().value == ConnectionState.CONNECTED
                Text("Bluetooth: ${if (isConnected) "Connected" else "Disconnected"}", style = MaterialTheme.typography.bodySmall)
                Text("Protocol: ${viewModel.selectedCanProtocol.collectAsState().value.displayName}", style = MaterialTheme.typography.bodySmall)
                Text("Voltage: ${viewModel.adapterVoltage.collectAsState().value ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
                Text("Firmware: ${viewModel.adapterFirmware.collectAsState().value ?: "Unavailable"}", style = MaterialTheme.typography.bodySmall)
            }
        }
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

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
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

