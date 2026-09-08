package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.ConnectionState

/**
 * Loud, actionable banner shown on every live-telemetry screen while the adapter
 * is not CONNECTED. Owner feedback (2026-09-08): a silent wall of "--" reads as a
 * dead app. This banner states the exact transport status and offers the fix path.
 */
@Composable
fun ConnectionBanner(
    connectionState: ConnectionState,
    statusMessage: String,
    onConnectAction: () -> Unit,
    actionLabel: String = "CONNECT / DIAGNOSE",
    modifier: Modifier = Modifier
) {
    if (connectionState == ConnectionState.CONNECTED) return
    val connecting = connectionState == ConnectionState.CONNECTING ||
            connectionState == ConnectionState.INITIALIZING
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF4A3800),
            contentColor = Color(0xFFFFE9B0)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (connecting) Icons.Default.BluetoothSearching else Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint = Color(0xFFFFC94D),
                modifier = Modifier.size(26.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (connecting) "CONNECTING TO ADAPTER..." else "ADAPTER NOT CONNECTED - gauges stay blank until connected",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFFFFC94D)
                )
                Text(
                    text = statusMessage,
                    fontSize = 11.sp,
                    color = Color(0xFFFFE9B0)
                )
            }
            if (!connecting) {
                TextButton(onClick = onConnectAction) {
                    Text(actionLabel, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFFFFC94D))
                }
            }
        }
    }
}
