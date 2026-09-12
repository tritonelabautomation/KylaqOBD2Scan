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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DocumentCodec
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.theme.WarningRed
import com.example.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Vehicle paperwork (VehIQ Documents): insurance, RC, licence, PUC, warranty with expiry alerts.
 * Expiring items also surface in the Reminders hub and in notifications.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var refresh by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val docs = remember(refresh) { viewModel.documentRepository.documents() }

    LaunchedEffect(Unit) { if (viewModel.takeQuickAdd("document")) showAdd = true }

    val day = 24L * 60 * 60 * 1000L
    val now = System.currentTimeMillis()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = CyberCyan) } },
                actions = { IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add", tint = NeonEmerald) } },
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
                Text(
                    "Expiry alerts: anything within 30 days appears here, in the Reminders hub and " +
                        "as a notification when the app starts.",
                    color = TextSecondaryDark, fontSize = 11.sp
                )
            }
            if (docs.isEmpty()) {
                item { Text("No documents yet. Add insurance, RC, licence, PUC…", color = TextSecondaryDark, fontSize = 12.sp) }
            }
            items(docs, key = { it.idMs }) { doc ->
                val remaining = doc.expiryMs?.let { (it - now) / day }
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = CyberCyan, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${doc.type} · ${doc.title}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    if (doc.number.isNotBlank()) append(doc.number).append(" · ")
                                    if (doc.issuer.isNotBlank()) append(doc.issuer).append(" · ")
                                    doc.expiryUtc?.let { append("exp ${it.take(10)}") }
                                },
                                color = TextSecondaryDark, fontSize = 10.sp
                            )
                            remaining?.let {
                                Text(
                                    when {
                                        it < 0 -> "EXPIRED ${-it} d ago"
                                        it <= 30 -> "expires in $it d"
                                        else -> "valid · $it d left"
                                    },
                                    color = when {
                                        it < 0 -> WarningRed
                                        it <= 30 -> WarningRed
                                        else -> NeonEmerald
                                    },
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.documentRepository.delete(doc.idMs); refresh++ }) {
                            Icon(Icons.Default.Delete, "Delete", tint = WarningRed, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        DocumentDialog(
            onDismiss = { showAdd = false },
            onSave = { type, title, number, issuer, expiry ->
                val expiryMs = expiry?.let {
                    runCatching {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                            .parse(it)?.time
                    }.getOrNull()
                }
                viewModel.documentRepository.add(
                    DocumentCodec.VehicleDocument(System.currentTimeMillis(), type, title, number, issuer, expiry, expiryMs)
                )
                refresh++
                showAdd = false
            }
        )
    }
}

@Composable
private fun DocumentDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String?) -> Unit
) {
    var type by remember { mutableStateOf(DocumentCodec.TYPES.first()) }
    var title by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var issuer by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentCodec.TYPES.take(3).forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t, fontSize = 10.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DocumentCodec.TYPES.drop(3).forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t, fontSize = 10.sp) })
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title (e.g. HDFC Ergo policy)") }, singleLine = true)
                OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Number (optional)") }, singleLine = true)
                OutlinedTextField(value = issuer, onValueChange = { issuer = it }, label = { Text("Issuer (optional)") }, singleLine = true)
                OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text("Expiry yyyy-mm-dd (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) onSave(type, title.trim(), number.trim(), issuer.trim(), expiry.trim().takeIf { it.isNotBlank() })
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
