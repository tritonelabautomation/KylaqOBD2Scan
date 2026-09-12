package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.bluetooth.ConnectionState
import com.example.sound.EngineAudioPlayer
import com.example.sound.EngineSoundProfiles
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/**
 * Rev Theater (RevHeadz-style parity, 2026-09-12): procedurally SYNTHESISED engine
 * sounds driven by real telemetry - PID 010C rpm and 0104 load in LIVE mode, or the
 * on-screen throttle in MANUAL mode. No licensed recordings (that is RevHeadz's paid
 * catalogue); our synth uses true firing physics (rpm/60 x cylinders/2) - see
 * sound/EngineSound.kt. Safety: the driver sets it up parked; gauges are for show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevTheaterScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val player = remember { EngineAudioPlayer() }
    var engineOn by remember { mutableStateOf(false) }
    var profileId by remember { mutableStateOf(EngineSoundProfiles.TSI_TRIPLE.id) }
    var liveMode by remember { mutableStateOf(true) }
    var manualRpm by remember { mutableStateOf(0.0f) }
    var manualThrottle by remember { mutableStateOf(0.2f) }
    var volume by remember { mutableStateOf(0.8f) }

    val live by viewModel.liveNumericMap.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val liveRpm = live["010C"] ?: 0.0
    val liveLoad = (live["0104"] ?: 0.0).coerceIn(0.0, 100.0)
    val liveSpeed = live["010D"] ?: 0.0
    val profile = EngineSoundProfiles.byId(profileId)
    val liveAvailable = connection == ConnectionState.CONNECTED && liveRpm > 0.0

    DisposableEffect(Unit) {
        onDispose { player.stop() }
    }
    // Poll-driven update loop: pushes latest telemetry into the render thread while playing.
    LaunchedEffect(engineOn) {
        while (engineOn) {
            val rpm = if (liveMode && liveAvailable) liveRpm else manualRpm.toDouble()
            val throttle = if (liveMode && liveAvailable) liveLoad / 100.0 else manualThrottle.toDouble()
            // profile has a private setter; start() applies it live when already running.
            val wanted = EngineSoundProfiles.byId(profileId)
            if (wanted.id != player.profile.id) player.start(wanted)
            player.rpm = if (rpm > 0.0) rpm else profile.idleRpm
            player.throttle = throttle
            delay(80)
        }
    }
    LaunchedEffect(volume) { player.setVolume(volume) }

    val displayRpm = if (liveMode && liveAvailable) liveRpm else manualRpm.toDouble()
    val rpmNorm = ((displayRpm - profile.idleRpm) / (profile.redlineRpm - profile.idleRpm)).coerceIn(0.0, 1.0).toFloat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("REV THEATER (engine sounds)", fontWeight = FontWeight.Black, fontSize = 15.sp) },
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
                Text(
                    "SYNTHESISED sound from real firing physics (rpm/60 x cylinders/2) - not licensed " +
                        "recordings like RevHeadz. Set it up PARKED; the driver never fiddles while moving.",
                    color = ElectricAmber, fontSize = 10.sp, modifier = Modifier.padding(10.dp)
                )
            }

            // ---- Gauge ----
            Canvas(Modifier.fillMaxWidth().height(210.dp)) {
                val start = 150f
                val sweep = 240f
                val stroke = 16.dp.toPx()
                val radius = (size.minDimension - stroke) / 2f
                val center = Offset(size.width / 2f, size.height / 2f + stroke)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
                drawArc(
                    color = Color(0xFF2A3441), startAngle = start, sweepAngle = sweep,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                val redlineNorm = 0.85f
                drawArc(
                    color = WarningRed, startAngle = start + sweep * redlineNorm,
                    sweepAngle = sweep * (1f - redlineNorm),
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                val needleAngle = Math.toRadians((start + sweep * rpmNorm).toDouble())
                val needleLen = radius - stroke
                drawLine(
                    color = CyberCyan, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round,
                    start = center,
                    end = Offset(
                        center.x + needleLen.toFloat() * kotlin.math.cos(needleAngle).toFloat(),
                        center.y + needleLen.toFloat() * kotlin.math.sin(needleAngle).toFloat()
                    )
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${displayRpm.toInt()} RPM",
                    color = if (rpmNorm > 0.85f) WarningRed else CyberCyan,
                    fontSize = 34.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace
                )
                Text(
                    profile.name + "  ·  firing " +
                        String.format(java.util.Locale.US, "%.0f Hz", EngineSoundProfiles.firingFrequencyHz(profile, displayRpm.coerceAtLeast(1.0))),
                    color = TextSecondaryDark, fontSize = 11.sp
                )
                if (liveMode && liveAvailable) {
                    Text("LIVE OBD · ${String.format(java.util.Locale.US, "%.0f km/h", liveSpeed)} · load ${String.format(java.util.Locale.US, "%.0f%%", liveLoad)}", color = NeonEmerald, fontSize = 11.sp)
                } else {
                    Text("MANUAL REV (no live engine data)", color = ElectricAmber, fontSize = 11.sp)
                }
            }

            // ---- Engine packs ----
            Text("ENGINE PACK", color = TextSecondaryDark, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EngineSoundProfiles.ALL.take(3).forEach { p ->
                    FilterChip(selected = profileId == p.id, onClick = { profileId = p.id }, label = { Text(p.name, fontSize = 9.sp, maxLines = 1) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EngineSoundProfiles.ALL.drop(3).forEach { p ->
                    FilterChip(selected = profileId == p.id, onClick = { profileId = p.id }, label = { Text(p.name, fontSize = 9.sp, maxLines = 1) })
                }
            }
            Text(profile.note, color = TextSecondaryDark, fontSize = 10.sp)

            // ---- Mode + controls ----
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = liveMode, onClick = { liveMode = true }, label = { Text("LIVE OBD", fontSize = 11.sp) })
                FilterChip(selected = !liveMode, onClick = { liveMode = false }, label = { Text("MANUAL", fontSize = 11.sp) })
            }
            if (!liveMode || !liveAvailable) {
                Text("REVS  ${manualRpm.toInt()} rpm", color = TextSecondaryDark, fontSize = 10.sp)
                Slider(value = manualRpm, onValueChange = { manualRpm = it }, valueRange = 0f..profile.redlineRpm.toFloat())
                Text("THROTTLE  ${(manualThrottle * 100).toInt()}%", color = TextSecondaryDark, fontSize = 10.sp)
                Slider(value = manualThrottle, onValueChange = { manualThrottle = it })
            }
            Text("VOLUME  ${(volume * 100).toInt()}%", color = TextSecondaryDark, fontSize = 10.sp)
            Slider(value = volume, onValueChange = { volume = it })

            Button(
                onClick = {
                    if (engineOn) {
                        engineOn = false
                        player.stop()
                    } else {
                        player.start(EngineSoundProfiles.byId(profileId))
                        player.setVolume(volume)
                        engineOn = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (engineOn) "STOP ENGINE" else "START ENGINE", fontWeight = FontWeight.Black)
            }
        }
    }
}
