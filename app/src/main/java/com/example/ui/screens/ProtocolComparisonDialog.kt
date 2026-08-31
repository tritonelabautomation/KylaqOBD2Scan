package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CanProtocol
import com.example.model.ProtocolVerificationResult
import com.example.model.ProtocolHealth
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.WarningRed

@Composable
fun ProtocolComparisonDialog(
    results: List<ProtocolVerificationResult>,
    isTesting: Boolean,
    onStartTest: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CAN Configurations Test") },
        text = {
            Column {
                Text(
                    "Sequentially testing all standard CAN protocols to find responsive ECU configurations.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                if (results.isEmpty() && !isTesting) {
                    Text("No results yet. Click Start Test to begin.")
                }
                
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(results) { result ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(result.protocol.displayName, style = MaterialTheme.typography.labelMedium)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    val color = if (result.health == ProtocolHealth.WORKING) NeonEmerald else if (result.health == ProtocolHealth.PARTIAL) Color.Yellow else WarningRed
                                    Text("Valid PIDs: ${result.successCount}/${result.totalRequests}", fontSize = 12.sp)
                                    Text(result.health.name, color = color, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                
                if (isTesting) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Testing in progress...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                } else if (results.isNotEmpty()) {
                    val best = results.maxByOrNull { it.successCount }
                    if (best != null && best.successCount > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("BEST VERIFIED CONFIGURATION:", style = MaterialTheme.typography.labelSmall, color = NeonEmerald)
                        Text(best.protocol.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text("${best.successCount}/8 valid responses", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onStartTest, enabled = !isTesting) {
                Text("Start Test")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isTesting) {
                Text("Close")
            }
        }
    )
}
