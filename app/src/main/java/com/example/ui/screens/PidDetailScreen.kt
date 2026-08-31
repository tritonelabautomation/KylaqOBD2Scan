package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.model.BytePositionStats
import com.example.model.PidDefinition
import com.example.model.TransactionRecord
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PidDetailScreen(
    pidIdParam: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val pidDefinitions by viewModel.pidDefinitions.collectAsState()
    val pidRawHistory by viewModel.pidRawHistory.collectAsState()
    val liveDecodedMap by viewModel.liveDecodedMap.collectAsState()

    var selectedPidId by remember {
        val initial = if (pidIdParam.isNotBlank()) pidIdParam else "0170"
        val normalized = if (initial.length == 2) "01$initial" else initial
        mutableStateOf(normalized)
    }

    val pidDef = remember(pidDefinitions, selectedPidId) {
        pidDefinitions.find { it.id.equals(selectedPidId, ignoreCase = true) || it.pid.equals(selectedPidId.takeLast(2), ignoreCase = true) }
            ?: pidDefinitions.firstOrNull()
    }

    val activePidId = pidDef?.id ?: selectedPidId
    val rawHistory = pidRawHistory[activePidId] ?: emptyList()
    val byteStats = remember(rawHistory) { viewModel.getByteStatisticsForPid(activePidId) }
    val wordStats = remember(rawHistory) { viewModel.get16BitWordStatisticsForPid(activePidId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag("pid_detail_screen")
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("btn_pid_detail_back")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "${pidDef?.name ?: activePidId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Reverse-Engineering & Telemetry Analysis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // PID Selector Dropdown/Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            pidDefinitions.forEach { p ->
                val isSelected = p.id == activePidId
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedPidId = p.id },
                    label = {
                        Text(
                            text = if (p.isResearch) "🔬 ${p.id}" else p.id,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (p.isResearch) ResearchPurple else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.testTag("chip_pid_${p.id}")
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Scrollable analysis content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // PID Metadata Card
            pidDef?.let { def ->
                PidMetadataCard(
                    pidDef = def,
                    observationCount = rawHistory.size,
                    lastDecoded = liveDecodedMap[def.id] ?: "--",
                    history = rawHistory
                )
            }

            // Research Notice if 016D or 0170
            if (pidDef?.isResearch == true) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ResearchPurple.copy(alpha = 0.15f)),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(ResearchPurple))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Science, contentDescription = null, tint = ResearchPurple, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "PASSIVE RESEARCH MODE: Raw hex payloads are captured and preserved without synthetic decoders. Use byte variance and 16-bit word analysis below to investigate EA211 engineering registers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Byte Analysis Section
            Text(
                text = "BYTE POSITION VARIANCE & STATISTICS",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = CyberCyan,
                letterSpacing = 1.sp
            )

            if (byteStats.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        text = "Awaiting response data to compute byte variance...\nStart polling or enable simulation mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                ByteStatisticsTable(stats = byteStats)
            }

            // 16-Bit Word Correlations Section
            if (wordStats.isNotEmpty()) {
                Text(
                    text = "16-BIT ADJACENT WORD ESTIMATION",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeonEmerald,
                    letterSpacing = 1.sp
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        wordStats.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Recent Observations History List
            Text(
                text = "RAW PAYLOAD TIMELINE (${rawHistory.size} SAMPLES)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF070B0E))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (rawHistory.isEmpty()) {
                        Text(
                            text = "No samples collected yet for ${pidDef?.id ?: activePidId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        rawHistory.takeLast(15).reversed().forEach { record ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = record.timestampUtc.takeLast(12).removeSuffix("Z"),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = record.rawPayload.chunked(2).joinToString(" "),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (pidDef?.isResearch == true) ResearchPurple else CyberCyan
                                )
                                Text(
                                    text = record.canRxId ?: "7E8",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = ElectricAmber
                                )
                            }
                            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun PidMetadataCard(
    pidDef: PidDefinition,
    observationCount: Int,
    lastDecoded: String,
    history: List<TransactionRecord>
) {
    val firstObs = history.firstOrNull()?.timestampUtc?.takeLast(12)?.removeSuffix("Z") ?: "--"
    val lastObs = history.lastOrNull()?.timestampUtc?.takeLast(12)?.removeSuffix("Z") ?: "--"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Service / PID", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${pidDef.service} ${pidDef.pid}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TX / RX Header", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${pidDef.canHeader} -> ${pidDef.expectedRxId}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Samples", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$observationCount", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = NeonEmerald)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Decoder / Formula", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (pidDef.formulaDisplay.isNotBlank()) pidDef.formulaDisplay else pidDef.decoderType.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = CyberCyan
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Last Decoded Value", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = lastDecoded,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = NeonEmerald
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("First: $firstObs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Last: $lastObs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun ByteStatisticsTable(stats: List<BytePositionStats>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("BYTE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp), fontSize = 10.sp)
                Text("LAST", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(46.dp), fontSize = 10.sp)
                Text("MIN", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), fontSize = 10.sp)
                Text("MAX", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp), fontSize = 10.sp)
                Text("UNIQUE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), fontSize = 10.sp)
                Text("CHANGES", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), fontSize = 10.sp)
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            stats.forEach { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "B${s.byteIndex}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = ResearchPurple,
                        fontSize = 11.sp,
                        modifier = Modifier.width(42.dp)
                    )
                    Text(
                        text = "0x%02X".format(s.lastValue),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = CyberCyan,
                        modifier = Modifier.width(46.dp)
                    )
                    Text(
                        text = "0x%02X".format(s.minVal),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(
                        text = "0x%02X".format(s.maxVal),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    Text(
                        text = "${s.uniqueCount}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(50.dp)
                    )
                    Text(
                        text = "${s.changeCount} / ${s.sampleCount}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (s.changeCount > 0) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
            }
        }
    }
}
