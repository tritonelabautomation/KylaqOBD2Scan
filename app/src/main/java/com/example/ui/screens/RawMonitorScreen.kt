package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Direction
import com.example.model.ResponseStatus
import com.example.model.TransactionRecord
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

enum class RawFilter(val label: String) {
    ALL("All"),
    TX("TX Only"),
    RX("RX Only"),
    RESEARCH_16D("016D Only"),
    RESEARCH_170("0170 Only"),
    ERRORS("Errors")
}

@Composable
fun RawMonitorScreen(
    viewModel: MainViewModel,
    onNavigateToPidDetail: (String) -> Unit
) {
    val currentTransactions by viewModel.currentTransactions.collectAsState()
    var selectedFilter by remember { mutableStateOf(RawFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()

    // Filter transactions
    val filteredTransactions = remember(currentTransactions, selectedFilter, searchQuery) {
        currentTransactions.filter { tx ->
            val matchesFilter = when (selectedFilter) {
                RawFilter.ALL -> true
                RawFilter.TX -> tx.direction == Direction.TX
                RawFilter.RX -> tx.direction == Direction.RX && tx.responseStatus == ResponseStatus.OK
                RawFilter.RESEARCH_16D -> tx.pid.equals("6D", ignoreCase = true)
                RawFilter.RESEARCH_170 -> tx.pid.equals("70", ignoreCase = true)
                RawFilter.ERRORS -> tx.direction == Direction.ERROR || tx.responseStatus != ResponseStatus.OK
            }

            val matchesSearch = if (searchQuery.isBlank()) true else {
                tx.pid.contains(searchQuery, ignoreCase = true) ||
                (tx.canRxId?.contains(searchQuery, ignoreCase = true) == true) ||
                (tx.canTxId?.contains(searchQuery, ignoreCase = true) == true) ||
                tx.rawPayload.contains(searchQuery, ignoreCase = true) ||
                tx.decodedParameter.contains(searchQuery, ignoreCase = true)
            }

            matchesFilter && matchesSearch
        }
    }

    LaunchedEffect(filteredTransactions.size, autoScroll) {
        if (autoScroll && filteredTransactions.isNotEmpty()) {
            listState.animateScrollToItem(filteredTransactions.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("raw_monitor_screen")
    ) {
        // Search and Auto-scroll controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("input_raw_search"),
                placeholder = { Text("Filter PID, CAN ID, Payload...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Auto-scroll toggle
            FilledIconToggleButton(
                checked = autoScroll,
                onCheckedChange = { autoScroll = it },
                modifier = Modifier.size(48.dp).testTag("btn_autoscroll_toggle")
            ) {
                Icon(
                    imageVector = if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.Lock,
                    contentDescription = "Auto Scroll",
                    tint = if (autoScroll) CyberCyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Filter chips row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RawFilter.values().forEach { filter ->
                val isSelected = filter == selectedFilter
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = filter },
                    label = {
                        Text(
                            text = filter.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("chip_filter_${filter.name}")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Header Table Row
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TIME", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp), fontSize = 10.sp)
                Text("DIR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), fontSize = 10.sp)
                Text("ID", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp), fontSize = 10.sp)
                Text("PID", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp), fontSize = 10.sp)
                Text("PAYLOAD / DATA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), fontSize = 10.sp)
                Text("DECODED", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp), fontSize = 10.sp)
            }
        }

        // Live Transactions List
        if (filteredTransactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (currentTransactions.isEmpty()) "No active transactions recorded yet.\nStart recording or connect adapter to log live frames." else "No records match active filter.",
                    style = MaterialTheme.typography.bodyMedium,
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
                    .background(Color(0xFF070B0E))
                    .testTag("raw_transactions_list")
            ) {
                items(filteredTransactions, key = { it.id }) { tx ->
                    RawTransactionRow(
                        tx = tx,
                        onRowClick = {
                            val targetPid = if (tx.pid.length == 2) "01${tx.pid}" else tx.pid
                            onNavigateToPidDetail(targetPid)
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@Composable
fun RawTransactionRow(
    tx: TransactionRecord,
    onRowClick: () -> Unit
) {
    val dirColor = when (tx.direction) {
        Direction.TX -> ElectricAmber
        Direction.RX -> NeonEmerald
        Direction.ERROR -> WarningRed
        Direction.INFO -> CyberCyan
    }

    val isResearch = tx.pid.equals("6D", ignoreCase = true) || tx.pid.equals("70", ignoreCase = true)
    val timeFormatted = tx.timestampUtc.takeLast(12).removeSuffix("Z")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRowClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time
        Text(
            text = timeFormatted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )

        // Direction
        Text(
            text = tx.direction.name,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = dirColor,
            modifier = Modifier.width(36.dp)
        )

        // CAN ID
        val canId = if (tx.direction == Direction.TX) tx.canTxId ?: "7DF" else tx.canRxId ?: "7E8"
        Text(
            text = canId,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = if (canId == "7E8") CyberCyan else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(42.dp)
        )

        // PID
        Text(
            text = "${tx.service}${tx.pid}",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = if (isResearch) ResearchPurple else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(46.dp)
        )

        // Payload
        val payloadStr = if (tx.rawPayload.isNotBlank()) {
            tx.rawPayload.chunked(2).joinToString(" ")
        } else if (tx.responseHex.isNotBlank()) {
            tx.responseHex
        } else {
            tx.requestHex
        }

        Text(
            text = payloadStr,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = if (isResearch) ResearchPurple else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        // Decoded Value
        Text(
            text = tx.decodedValueDisplay.ifBlank { tx.unit },
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (tx.direction == Direction.ERROR) WarningRed else NeonEmerald,
            modifier = Modifier.width(76.dp)
        )
    }
}
