package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.DecoderType
import com.example.model.PidDefinition
import com.example.protocol.SafetyValidator
import com.example.protocol.ValidationResult
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PidConfigScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val pidDefinitions by viewModel.pidDefinitions.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingPid by remember { mutableStateOf<PidDefinition?>(null) }
    var showResetConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("pid_config_screen")
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("btn_pid_config_back")) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PID Configuration",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Row {
                IconButton(onClick = { showResetConfirmation = true }, modifier = Modifier.testTag("btn_reset_pids")) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Reset Defaults", tint = WarningRed)
                }
                IconButton(onClick = { showAddDialog = true }, modifier = Modifier.testTag("btn_add_pid")) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Add PID", tint = CyberCyan)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Configure passive request intervals and enabled channels for Škoda Kylaq. All requests are strictly verified by Safety Validator.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pidDefinitions, key = { it.id }) { pid ->
                PidConfigCard(
                    pid = pid,
                    onToggle = { viewModel.togglePid(pid.id) },
                    onEdit = { editingPid = pid },
                    onDelete = { viewModel.deletePid(pid.id) }
                )
            }
        }
    }

    // Add / Edit Dialog
    if (showAddDialog || editingPid != null) {
        PidEditDialog(
            initialPid = editingPid,
            onDismiss = {
                showAddDialog = false
                editingPid = null
            },
            onSave = { newPid ->
                viewModel.savePid(newPid)
                showAddDialog = false
                editingPid = null
            }
        )
    }

    // Reset Defaults Dialog
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset to Kylaq Factory PIDs?") },
            text = { Text("This will restore all default Mode 01 telemetry channels, intervals, and experimental 016D/0170 research PIDs.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetPidDefaults()
                        showResetConfirmation = false
                    }
                ) {
                    Text("Reset", color = WarningRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PidConfigCard(
    pid: PidDefinition,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pid_card_${pid.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pid.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${pid.service}${pid.pid}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (pid.isResearch) ResearchPurple else CyberCyan,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = pid.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Interval: ${pid.defaultIntervalMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Header: ${pid.canHeader}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    if (pid.unit.isNotBlank()) {
                        Text(
                            text = "Unit: ${pid.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = pid.enabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.testTag("switch_pid_${pid.id}")
                )
            }
        }
    }
}

@Composable
fun PidEditDialog(
    initialPid: PidDefinition?,
    onDismiss: () -> Unit,
    onSave: (PidDefinition) -> Unit
) {
    var service by remember { mutableStateOf(initialPid?.service ?: "01") }
    var pid by remember { mutableStateOf(initialPid?.pid ?: "") }
    var name by remember { mutableStateOf(initialPid?.name ?: "") }
    var shortName by remember { mutableStateOf(initialPid?.shortName ?: "") }
    var unit by remember { mutableStateOf(initialPid?.unit ?: "") }
    var canHeader by remember { mutableStateOf(initialPid?.canHeader ?: "7DF") }
    var intervalStr by remember { mutableStateOf(initialPid?.defaultIntervalMs?.toString() ?: "500") }
    var isResearch by remember { mutableStateOf(initialPid?.isResearch ?: false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (initialPid != null) "Edit PID ${initialPid.id}" else "Add OBD-II PID",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = service,
                        onValueChange = { service = it.uppercase() },
                        label = { Text("Service (e.g. 01)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pid,
                        onValueChange = { pid = it.uppercase() },
                        label = { Text("PID (e.g. 0C)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Parameter Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = intervalStr,
                        onValueChange = { intervalStr = it },
                        label = { Text("Interval (ms)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = canHeader,
                    onValueChange = { canHeader = it.uppercase() },
                    label = { Text("CAN Header (e.g. 7DF / 7E0)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Research / Raw Channel", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = isResearch, onCheckedChange = { isResearch = it })
                }

                errorMessage?.let { err ->
                    Text(text = err, color = WarningRed, fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cleanCmd = "$service$pid".trim().uppercase()
                            val validation = SafetyValidator.validateCommand(cleanCmd)
                            if (validation is ValidationResult.Rejected) {
                                errorMessage = "Safety Violation: ${validation.reason}"
                                return@Button
                            }

                            val intervalMs = intervalStr.toLongOrNull() ?: 500L
                            val id = if (initialPid != null) initialPid.id else cleanCmd
                            val newDef = PidDefinition(
                                id = id,
                                service = service,
                                pid = pid,
                                name = name.ifBlank { "PID $cleanCmd" },
                                shortName = shortName.ifBlank { name.take(8) },
                                unit = unit,
                                canHeader = canHeader.ifBlank { "7DF" },
                                expectedRxId = "7E8",
                                defaultIntervalMs = intervalMs,
                                enabled = true,
                                decoderType = if (isResearch) DecoderType.RESEARCH_RAW else (initialPid?.decoderType ?: DecoderType.RESEARCH_RAW),
                                formulaDisplay = if (isResearch) "RAW HEX" else (initialPid?.formulaDisplay ?: "RAW"),
                                isResearch = isResearch
                            )
                            onSave(newDef)
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
