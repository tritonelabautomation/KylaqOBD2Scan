package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

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

        // Manual Command Input Field
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it.uppercase() },
                modifier = Modifier
                    .weight(1f)
                    .testTag("input_manual_command"),
                placeholder = { Text("Command (e.g. ATRV, ATDP, 0100)", fontSize = 12.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = {
                    if (inputCommand.isNotBlank()) {
                        viewModel.sendManualCommand(inputCommand)
                        inputCommand = ""
                    }
                },
                modifier = Modifier
                    .height(54.dp)
                    .testTag("btn_send_command"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = Color(0xFF00363D))
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
            }
        }
    }
}
