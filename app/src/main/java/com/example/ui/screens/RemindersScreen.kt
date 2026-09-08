package com.example.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MaintenanceCatalog
import com.example.data.NoticeManager
import com.example.data.ReminderCodec
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricAmber
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import java.util.Date

/**
 * Reminders hub (VehIQ): overdue services + expiring documents + custom reminders in one feed,
 * with snooze / complete / delete, repeat rules, a master notifications toggle and a test
 * notification so the pipeline can be verified on-device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val settings = viewModel.settingsRepository
    val masterEnabled by settings.remindersEnabled.collectAsState()
    val weeklyCheckIn by settings.weeklyCheckInEnabled.collectAsState()

    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000L

    val overdueServices = remember(refresh) {
        viewModel.maintenanceRepository.dueStates(now)
            .filter { it.status == MaintenanceCatalog.DueStatus.OVERDUE || it.status == MaintenanceCatalog.DueStatus.DUE_SOON }
    }
    val expiringDocs = remember(refresh) { viewModel.documentRepository.expiringWithin(30, now) }
    val customDue = remember(refresh) { viewModel.reminderRepository.due(now) }
    val upcoming = remember(refresh) {
        viewModel.reminderRepository.reminders()
            .filter { !it.completed && (it.snoozedUntilMs ?: 0) <= now && it.dueMs > now }
            .sortedBy { it.dueMs }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders Hub", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = CyberCyan) } },
                actions = {
                    IconButton(onClick = { NoticeManager.postTest(context) }) {
                        Icon(Icons.Default.NotificationsActive, "Test notification", tint = ElectricAmber)
                    }
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add reminder", tint = NeonEmerald) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Notifications", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Master toggle for due-item alerts at app start.", color = TextSecondaryDark, fontSize = 10.sp)
                        }
                        Switch(checked = masterEnabled, onCheckedChange = { settings.setRemindersEnabled(it) })
                    }
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Weekly check-in", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Nudge if nothing was logged for 7+ days.", color = TextSecondaryDark, fontSize = 10.sp)
                        }
                        Switch(checked = weeklyCheckIn, onCheckedChange = { settings.setWeeklyCheckIn(it) })
                    }
                }
            }
            item {
                Text(
                    "NEEDS ATTENTION (${overdueServices.size + expiringDocs.size + customDue.size})",
                    color = WarningRed, fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
            }
            items(overdueServices, key = { "svc-${it.item.id}" }) { state ->
                AlertCard(
                    title = state.item.label,
                    subtitle = when (state.status) {
                        MaintenanceCatalog.DueStatus.OVERDUE -> "Service overdue - ${state.headline}"
                        else -> "Service due soon - ${state.headline}"
                    },
                    color = if (state.status == MaintenanceCatalog.DueStatus.OVERDUE) WarningRed else ElectricAmber,
                    actions = null
                )
            }
            items(expiringDocs, key = { "doc-${it.first.idMs}" }) { (doc, remainingMs) ->
                AlertCard(
                    title = "${doc.type} · ${doc.title}",
                    subtitle = if (remainingMs < 0) "Expired ${-remainingMs / day} d ago" else "Expires in ${remainingMs / day} d",
                    color = WarningRed,
                    actions = null
                )
            }
            items(customDue, key = { "rem-${it.idMs}" }) { rem ->
                AlertCard(
                    title = rem.title,
                    subtitle = "Due ${Date(rem.dueMs)}" + (rem.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    color = WarningRed,
                    actions = {
                        TextButton(onClick = { viewModel.reminderRepository.snooze(rem.idMs, now + day); refresh++ }) {
                            Text("Snooze 1d", fontSize = 10.sp)
                        }
                        TextButton(onClick = { viewModel.reminderRepository.complete(rem.idMs, now); refresh++ }) {
                            Text("Done", fontSize = 10.sp)
                        }
                        IconButton(onClick = { viewModel.reminderRepository.delete(rem.idMs); refresh++ }) {
                            Icon(Icons.Default.Delete, "Delete", tint = WarningRed, modifier = Modifier.size(16.dp))
                        }
                    }
                )
            }
            item {
                Text("UPCOMING (${upcoming.size})", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            items(upcoming, key = { "up-${it.idMs}" }) { rem ->
                AlertCard(
                    title = rem.title,
                    subtitle = "Due ${Date(rem.dueMs)} · ${rem.repeat.name.lowercase()}",
                    color = NeonEmerald,
                    actions = {
                        IconButton(onClick = { viewModel.reminderRepository.delete(rem.idMs); refresh++ }) {
                            Icon(Icons.Default.Delete, "Delete", tint = WarningRed, modifier = Modifier.size(16.dp))
                        }
                    }
                )
            }
            if (overdueServices.isEmpty() && expiringDocs.isEmpty() && customDue.isEmpty() && upcoming.isEmpty()) {
                item { Text("Nothing due. Log services and add reminders to build the hub.", color = TextSecondaryDark, fontSize = 12.sp) }
            }
        }
    }

    if (showAdd) {
        ReminderDialog(
            onDismiss = { showAdd = false },
            onSave = { title, dueMs, repeat, occurrences, note ->
                viewModel.reminderRepository.add(
                    ReminderCodec.CustomReminder(
                        idMs = System.currentTimeMillis(), title = title, dueMs = dueMs,
                        repeat = repeat, occurrencesLeft = occurrences, snoozedUntilMs = null,
                        completed = false, note = note
                    )
                )
                refresh++
                showAdd = false
            }
        )
    }
}

@Composable
private fun AlertCard(
    title: String,
    subtitle: String,
    color: Color,
    actions: (@Composable () -> Unit)?
) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = color, fontSize = 11.sp)
            }
            actions?.invoke()
        }
    }
}

@Composable
private fun ReminderDialog(
    onDismiss: () -> Unit,
    onSave: (String, Long, ReminderCodec.Repeat, Int?, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("0") }
    var repeat by remember { mutableStateOf(ReminderCodec.Repeat.NONE) }
    var occurrences by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = days, onValueChange = { days = it }, label = { Text("Due in N days (0 = today)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReminderCodec.Repeat.values().forEach { r ->
                        FilterChip(selected = repeat == r, onClick = { repeat = r }, label = { Text(r.name.lowercase(), fontSize = 10.sp) })
                    }
                }
                OutlinedTextField(value = occurrences, onValueChange = { occurrences = it }, label = { Text("Stop after N occurrences (optional)") }, singleLine = true)
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    val dueMs = System.currentTimeMillis() + (days.toDoubleOrNull() ?: 0.0).toLong() * 24L * 60 * 60 * 1000
                    onSave(title.trim(), dueMs, repeat, occurrences.toIntOrNull(), note.trim())
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
