package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the owner sees when the last starts crashed before the app could open
 * (2026-09-20: "Again app is crashing when I open it crashes. No app crash logs why").
 *
 * Deliberately dependency-free: no ViewModel, no database, no Bluetooth, no Compose
 * state from anywhere else - the ONE screen that must still work when everything else
 * is what broke. It says what happened, proves nothing was deleted, and can hand the
 * crash record to Drive/Gmail/Files so the reason is never trapped on the phone.
 */
@Composable
fun CrashSafeModeScreen(
    crashedAt: String?,
    summary: String?,
    fullText: String?,
    onShare: () -> Unit,
    onTryNormalStart: () -> Unit,
    updateMessage: String? = null,
    onUpdate: (() -> Unit)? = null
) {
    var showFull by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Safe mode",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("screen_crash_safe_mode")
        )
        Text(
            "The last two starts of the app crashed before it could open, so this screen " +
                "opened instead - it touches none of the usual machinery and always works.",
            fontSize = 13.sp
        )
        Text(
            "Nothing was deleted. Your trips, raw logs, fuel ledger and car-pool records are " +
                "still on disk, and a drive that was cut off is rebuilt automatically " +
                "(\"Recovered Run\") once the app starts normally again.",
            fontSize = 13.sp
        )
        if (crashedAt != null) {
            Text("Last crash recorded at $crashedAt IST", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (summary != null) {
            Text(
                summary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
        if (fullText != null) {
            TextButton(
                onClick = { showFull = !showFull },
                modifier = Modifier.testTag("btn_toggle_crash_text")
            ) {
                Text(if (showFull) "Hide full crash log" else "View full crash log")
            }
            if (showFull) {
                Text(
                    fullText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // The way OUT of a crash loop (owner 2026-09-20: the OOM loop left safe mode as
        // the only usable screen on the old build, with no path to the fixed one). This
        // button needs no ViewModel and no database - just the standalone updater - so
        // it works exactly when everything else cannot.
        if (onUpdate != null) {
            Button(onClick = onUpdate, modifier = Modifier.testTag("btn_safe_update")) {
                Text("Update & install newest build")
            }
            if (updateMessage != null) {
                Text(updateMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Button(onClick = onShare, modifier = Modifier.testTag("btn_share_crash_log")) {
            Text("Share crash log")
        }
        OutlinedButton(onClick = onTryNormalStart, modifier = Modifier.testTag("btn_try_normal_start")) {
            Text("Try normal start")
        }
    }
}

/**
 * Shown on a normal start when the journal holds a crash from an earlier run: the reason,
 * in plain sight, instead of silence (owner 2026-09-20). The full record stays on the
 * phone and rides along in every Drive backup.
 */
@Composable
fun CrashNoticeDialog(
    crashedAt: String?,
    summary: String,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("dialog_crash_notice"),
        title = { Text("Previous run crashed") },
        text = {
            Column {
                if (crashedAt != null) {
                    Text("Recorded at $crashedAt IST", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                }
                Text(summary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The full crash log is saved on this phone (crash_logs) and rides along " +
                        "in your Drive backups - no crash is ever silent again.",
                    fontSize = 11.sp,
                    color = Color(0xFF8E8E93)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare, modifier = Modifier.testTag("btn_crash_share")) {
                Text("Share log")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    )
}
