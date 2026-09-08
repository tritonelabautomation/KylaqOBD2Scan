package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.protocol.CodingLabCodec
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Read-only UDS Coding Lab (added 2026-09-09, owner request: VAG hidden-feature research).
 *
 * Scouts adaptation/DID contents on any discovered ECU header with service 0x22.
 * Writes (0x2E), security access (0x27), session control (0x10) and flash services
 * are blocked by SafetyValidator - this screen can inspect, never modify, vehicle
 * coding. Rationale and the honest feasibility matrix live in
 * docs/reference/vag-coding-research.md.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodingLabScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var header by remember { mutableStateOf("7E0") }
    var did by remember { mutableStateOf("F190") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MainViewModel.CodingLabResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UDS CODING LAB (read-only)", fontWeight = FontWeight.Black, fontSize = 15.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyberCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "SAFETY: this lab READS identifiers (UDS 0x22). Write (0x2E), security " +
                            "access (0x27), session control (0x10) and flashing are blocked by the " +
                            "app's SafetyValidator. Theme / sport-menu CODING on the car requires " +
                            "ODIS/OBDeleven-class tools with licensed seed-key access - see the " +
                            "research doc in docs/reference/.",
                        color = ElectricAmber, fontSize = 10.sp
                    )
                }
            }
            Text("ECU HEADER", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("7E0" to "ENGINE", "7E1" to "GEARBOX", "7E8" to "ECU RX", "7E9" to "TCM RX").forEach { (h, label) ->
                    FilterChip(selected = header == h, onClick = { header = h }, label = { Text(label, fontSize = 10.sp) })
                }
            }
            TextField(value = header, onValueChange = { header = it }, label = { Text("Custom header (e.g. 7E0)") }, singleLine = true)
            Text("DATA IDENTIFIER (DID)", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("F190" to "VIN", "F180" to "ECU ID", "F191" to "VW ID", "0100" to "OBD SUP").forEach { (d, label) ->
                    FilterChip(selected = did == d, onClick = { did = d }, label = { Text(label, fontSize = 10.sp) })
                }
            }
            TextField(value = did, onValueChange = { did = it }, label = { Text("DID hex (2-4 chars)") }, singleLine = true)
            Button(
                onClick = {
                    val h = CodingLabCodec.normalizeHeader(header)
                    val d = CodingLabCodec.normalizeDid(did)
                    if (h == null || d == null) {
                        result = MainViewModel.CodingLabResult("Invalid header or DID (header 3/8 hex, DID 2-4 hex).", null, null, null)
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        result = viewModel.codingLabRead(h, d)
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "READING..." else "READ 0x22") }

            result?.let { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("RAW RESPONSE", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(r.raw, color = NeonEmerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        r.nrc?.let {
                            Text("NEGATIVE: $it", color = WarningRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        r.payloadHex?.let {
                            Text("PAYLOAD", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(it, color = CyberCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                        r.ascii?.let {
                            if (it.isNotBlank()) Text("ASCII: $it", color = NeonEmerald, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
