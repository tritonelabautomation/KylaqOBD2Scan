import re

with open('app/src/main/java/com/example/ui/screens/ProtocolComparisonDialog.kt', 'r') as f:
    content = f.read()

new_dialog_content = """package com.example.ui.screens

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
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.WarningRed

@Composable
fun ProtocolComparisonDialog(
    results: List<ProtocolVerificationResult>,
    isTesting: Boolean,
    onStartTest: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (ProtocolVerificationResult?) -> Unit = {}
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
                                    val color = when (result.health) {
                                        ProtocolHealth.WORKING -> NeonEmerald
                                        ProtocolHealth.PARTIAL -> ElectricAmber
                                        else -> WarningRed
                                    }
                                    Text("ECU responses: ${result.successCount}/${result.totalRequests}", fontSize = 12.sp)
                                    Text(result.health.name, color = color, fontSize = 12.sp)
                                }
                                Text("Errors: ${result.canErrorCount} | Timeouts: ${result.timeoutCount}", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
                
                if (isTesting) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Testing in progress...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                } else if (results.isNotEmpty()) {
                    val best = results.sortedWith(
                        compareBy<com.example.model.ProtocolVerificationResult> { 
                            when (it.health) {
                                com.example.model.ProtocolHealth.WORKING -> 0
                                com.example.model.ProtocolHealth.PARTIAL -> 1
                                com.example.model.ProtocolHealth.NO_RESPONSE -> 2
                                com.example.model.ProtocolHealth.ADAPTER_ERROR -> 3
                                else -> 4
                            }
                        }
                        .thenByDescending { it.successCount }
                        .thenBy { it.canErrorCount + it.invalidCount + it.timeoutCount }
                        .thenBy { it.avgResponseTimeMs }
                    ).firstOrNull()
                    
                    if (best != null && (best.health == ProtocolHealth.WORKING || best.health == ProtocolHealth.PARTIAL)) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Recommended profile:", style = MaterialTheme.typography.labelSmall, color = NeonEmerald)
                        Text(best.protocol.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text("${best.protocol.atCommand}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("Recommended based on successful OBD-II ECU communication.", style = MaterialTheme.typography.bodySmall, color = NeonEmerald)
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No responsive protocol found.", style = MaterialTheme.typography.bodyMedium, color = WarningRed)
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
"""

with open('app/src/main/java/com/example/ui/screens/ProtocolComparisonDialog.kt', 'w') as f:
    f.write(new_dialog_content)

