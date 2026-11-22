package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MaintenanceCatalog
import com.example.data.RecordTime
import com.example.ui.theme.*
import java.util.Locale

/**
 * Data payload for creating a reconstructed / manual trip to fill an unlogged gap.
 */
data class ManualTripRequest(
    val title: String,
    val startOdometerKm: Double,
    val endOdometerKm: Double,
    val startTimestampMs: Long,
    val durationSeconds: Long,
    val driveMode: String = "D",
    val acState: String = "ON",
    val estimatedFuelLiters: Double? = null,
    val carpoolRiders: String = "",
    val carpoolFare: Double = 0.0,
    val notes: String = ""
) {
    val distanceKm: Double get() = maxOf(0.1, endOdometerKm - startOdometerKm)
}

/**
 * Dialog for filling an unlogged gap between OBD recording sessions, manually logging a missed
 * drive, or recording a workshop visit.
 *
 * Two tabs, two different records:
 *
 *  - **Trip** writes a reconstructed drive exactly as before ([ManualTripRequest]); the invoice
 *    field/attachment only annotate that drive's notes, they never change its maths.
 *  - **Service Inspection** writes one maintenance ledger entry per ticked task
 *    ([ServiceInspectionRequest]). It never fabricates a drive: a workshop bill is not a trip,
 *    and a 0 km "drive" would poison economy, cost/km and the gap detector.
 *
 * The arithmetic, parsing and validation live in `ManualTrip.kt` so the JVM suite can test
 * them; this file is layout and event wiring only.
 */
@Composable
fun FillMissingTripDialog(
    initialStartOdoKm: Double? = null,
    initialEndOdoKm: Double? = null,
    initialStartMs: Long? = null,
    initialEndMs: Long? = null,
    initialTab: TripLogTab = TripLogTab.TRIP,
    initialServiceItemId: String? = null,
    onDismiss: () -> Unit,
    onSave: (ManualTripRequest) -> Unit,
    onSaveService: (ServiceInspectionRequest) -> Unit = {}
) {
    val context = LocalContext.current
    val defaultStartOdo = initialStartOdoKm ?: 0.0
    val defaultEndOdo = initialEndOdoKm ?: (defaultStartOdo + 5.0)
    val defaultStartMs = initialStartMs ?: (System.currentTimeMillis() - 3600_000L)
    val defaultDurationSec = if (initialEndMs != null && initialEndMs > defaultStartMs) {
        maxOf(60L, (initialEndMs - defaultStartMs) / 1000L)
    } else {
        val dist = maxOf(0.5, defaultEndOdo - defaultStartOdo)
        // Assume ~30 km/h city average
        (dist / 30.0 * 3600.0).toLong().coerceIn(120L, 14400L)
    }

    var tab by remember { mutableStateOf(initialTab) }

    var title by remember { mutableStateOf("Missed Drive (Gap Filled)") }
    var startOdoText by remember { mutableStateOf(String.format(Locale.US, "%.1f", defaultStartOdo)) }
    var endOdoText by remember { mutableStateOf(String.format(Locale.US, "%.1f", defaultEndOdo)) }
    var durationMinutesText by remember { mutableStateOf("${maxOf(1L, defaultDurationSec / 60L)}") }

    var driveMode by remember { mutableStateOf("D") } // "D" or "S"
    var acState by remember { mutableStateOf("ON") } // "ON", "OFF", "Auto"

    var customFuelText by remember { mutableStateOf("") }
    var showCarpoolSection by remember { mutableStateOf(false) }
    var carpoolRidersText by remember { mutableStateOf("") }
    var carpoolFareText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }

    // ── Service Inspection state ────────────────────────────────────────────────
    // The service tab's odometer is its own state: the trip tab's default (start + 5 km) would
    // otherwise be written as a real odometer reading for a workshop visit.
    var serviceOdoText by remember {
        mutableStateOf(initialEndOdoKm?.let { String.format(Locale.US, "%.1f", it) } ?: "")
    }
    var serviceItemId by remember {
        mutableStateOf(
            initialServiceItemId?.takeIf { id -> MaintenanceCatalog.KYLAQ_ITEMS.any { it.id == id } }
        )
    }
    var serviceDateMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var pollenTagged by remember { mutableStateOf(false) }
    // Hoisted: a `remember` inside the tab branch would be created/discarded on every switch.
    var serviceTaskMenuOpen by remember { mutableStateOf(false) }

    // ── Shared invoice state (both tabs can hold a bill) ────────────────────────
    var invoiceCostText by remember { mutableStateOf("") }
    var invoiceUri by remember { mutableStateOf<String?>(null) }
    var invoiceName by remember { mutableStateOf<String?>(null) }

    val invoiceCost = ServiceInspectionRules.parseInvoiceCost(invoiceCostText)
    val invoiceCostInvalid = invoiceCostText.isNotBlank() && invoiceCost == null

    // PDF-only picker. The grant is persisted so the bill can be reopened after the app
    // restarts; providers that do not offer a persistable grant are tolerated (the URI still
    // works for this session) rather than crashing the dialog.
    val invoicePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val normalized = ServiceInspectionRules.normalizePdfUri(uri.toString())
        if (normalized == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Non-persistable provider: keep the URI for this session only.
        }
        invoiceUri = normalized
        invoiceName = ServiceInspectionRules.pdfDisplayName(
            queryPdfDisplayName(context, uri), normalized
        )
    }

    val startOdoVal = startOdoText.toDoubleOrNull() ?: defaultStartOdo
    val endOdoVal = endOdoText.toDoubleOrNull() ?: defaultEndOdo
    val distanceKm = maxOf(0.0, endOdoVal - startOdoVal)
    val durationMinVal = durationMinutesText.toLongOrNull() ?: (defaultDurationSec / 60L)
    val durationSecVal = maxOf(60L, durationMinVal * 60L)

    val avgSpeedKmh = if (durationSecVal > 0) (distanceKm / (durationSecVal / 3600.0)).coerceIn(1.0, 160.0) else 30.0
    val autoEstimatedFuelL = (distanceKm / 10.5) // ~10.5 km/L default EA211 city economy
    val finalFuelL = customFuelText.toDoubleOrNull() ?: autoEstimatedFuelL

    val tripValid = distanceKm > 0.05 && durationSecVal >= 60L && startOdoVal >= 0.0 &&
        endOdoVal > startOdoVal && !invoiceCostInvalid

    val draft = FillTripDraft(
        title = title,
        startOdoKm = startOdoVal,
        endOdoKm = if (tab == TripLogTab.SERVICE) {
            serviceOdoText.trim().toDoubleOrNull() ?: 0.0
        } else {
            endOdoVal
        },
        startMs = defaultStartMs,
        durationSeconds = durationSecVal,
        driveMode = driveMode,
        acState = acState,
        fuelLitres = finalFuelL,
        carpoolRiders = carpoolRidersText,
        carpoolFareRupees = carpoolFareText.toDoubleOrNull() ?: 0.0,
        notes = notesText,
        serviceItemId = serviceItemId,
        serviceDateMs = serviceDateMs,
        invoiceCostRupees = invoiceCost,
        invoicePdfUri = invoiceUri,
        invoicePdfName = invoiceName,
        pollenTaskTagged = pollenTagged
    )
    val serviceValidation = draft.validateService()
    val serviceValid = serviceValidation.ok && !invoiceCostInvalid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (tab == TripLogTab.TRIP) Icons.Default.AddRoad else Icons.Default.Build,
                    contentDescription = null,
                    tint = if (tab == TripLogTab.TRIP) CyberCyan else NeonEmerald,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (tab == TripLogTab.TRIP) "Fill Missing / Unlogged Trip" else "Log Service Inspection",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimaryDark
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── Tabs ────────────────────────────────────────────────────────
                // ScrollableTabRow with only two wide tabs still fits a small phone; the
                // labels are short so a plain TabRow keeps both targets equal width.
                TabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = CyberCyan,
                    modifier = Modifier.fillMaxWidth().testTag("tabs_fill_trip")
                ) {
                    TripLogTab.values().forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            modifier = Modifier.testTag(
                                if (entry == TripLogTab.TRIP) "tab_trip" else "tab_service_inspection"
                            ),
                            text = {
                                Text(
                                    entry.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (tab == entry) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }

                if (tab == TripLogTab.TRIP) {
                    Text(
                        text = "Reconstruct a trip for distance driven without the OBD logger connected. " +
                            "Telemetry endpoints, odometer delta, and fuel burn will be synthesized into " +
                            "a permanent trip record.",
                        color = TextSecondaryDark,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    Text(
                        text = "Record work done at a workshop or in your own garage. This writes a " +
                            "maintenance ledger entry, not a drive - the trip history and fuel maths " +
                            "are left untouched.",
                        color = TextSecondaryDark,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }

                AnimatedVisibility(visible = tab == TripLogTab.TRIP) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Trip Title
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Trip Title / Purpose") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("input_manual_trip_title"),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberCyan)
                        )

                        // Odometer Gap Endpoints
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = startOdoText,
                                        onValueChange = { startOdoText = it },
                                        label = { Text("Start ODO (km)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("input_manual_start_odo")
                                    )
                                    OutlinedTextField(
                                        value = endOdoText,
                                        onValueChange = { endOdoText = it },
                                        label = { Text("End ODO (km)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).testTag("input_manual_end_odo")
                                    )
                                }

                                // Calculated Delta & Avg Speed summary
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Route, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = String.format(Locale.US, "Distance: +%.1f km", distanceKm),
                                            fontWeight = FontWeight.Bold,
                                            color = NeonEmerald,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Speed, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                                        Text(
                                            text = String.format(Locale.US, "Avg: %.0f km/h", avgSpeedKmh),
                                            fontWeight = FontWeight.Medium,
                                            color = CyberCyan,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        // Estimated Duration in Minutes
                        OutlinedTextField(
                            value = durationMinutesText,
                            onValueChange = { durationMinutesText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Estimated Duration (Minutes)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, tint = CyberCyan) },
                            modifier = Modifier.fillMaxWidth().testTag("input_manual_duration_mins")
                        )

                        // Drive Mode & AC State Selectors
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Drive Mode Switch
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Transmission Mode", color = TextSecondaryDark, fontSize = 10.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        // FIXED WIDTH: a FilterChip sizes to its label, so "Sport (S)"
                                        // rendered wider than "Drive (D)" and the pair visibly
                                        // jittered when the selection moved (the selected chip also
                                        // gains a border). Both chips now share one width, so the
                                        // Sport button no longer shifts or clips.
                                        FilterChip(
                                            selected = driveMode == "D",
                                            onClick = { driveMode = "D" },
                                            label = { Text("Drive (D)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                                            modifier = Modifier.width(TRANSMISSION_CHIP_WIDTH).testTag("chip_drive_mode"),
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberCyan.copy(alpha = 0.2f), selectedLabelColor = CyberCyan)
                                        )
                                        FilterChip(
                                            selected = driveMode == "S",
                                            onClick = { driveMode = "S" },
                                            label = { Text("Sport (S)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                                            modifier = Modifier.width(TRANSMISSION_CHIP_WIDTH).testTag("chip_sport_mode"),
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = WarningRed.copy(alpha = 0.2f), selectedLabelColor = WarningRed)
                                        )
                                    }
                                }
                            }

                            // AC State Switch
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Air Conditioning", color = TextSecondaryDark, fontSize = 10.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        FilterChip(
                                            selected = acState == "ON",
                                            onClick = { acState = "ON" },
                                            label = { Text("ON", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                                            modifier = Modifier.width(AC_CHIP_WIDTH).testTag("chip_ac_on"),
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CyberCyan.copy(alpha = 0.2f), selectedLabelColor = CyberCyan)
                                        )
                                        FilterChip(
                                            selected = acState == "OFF",
                                            onClick = { acState = "OFF" },
                                            label = { Text("OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                                            modifier = Modifier.width(AC_CHIP_WIDTH).testTag("chip_ac_off"),
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = ElectricAmber.copy(alpha = 0.2f), selectedLabelColor = ElectricAmber)
                                        )
                                    }
                                }
                            }
                        }

                        // Estimated Fuel Burn
                        OutlinedTextField(
                            value = customFuelText,
                            onValueChange = { customFuelText = it },
                            label = { Text(String.format(Locale.US, "Estimated Fuel (L) [Default: %.2f L]", autoEstimatedFuelL)) },
                            placeholder = { Text(String.format(Locale.US, "%.2f", autoEstimatedFuelL)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = NeonEmerald) },
                            modifier = Modifier.fillMaxWidth().testTag("input_manual_fuel_liters")
                        )

                        // Optional Carpool Section Toggle
                        Surface(
                            color = if (showCarpoolSection) NeonEmerald.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (showCarpoolSection) NeonEmerald.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                            modifier = Modifier.fillMaxWidth().clickable { showCarpoolSection = !showCarpoolSection }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Groups, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(18.dp))
                                    Text("Attach Carpool / Riders", color = TextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Icon(
                                    imageVector = if (showCarpoolSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = TextSecondaryDark,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        AnimatedVisibility(visible = showCarpoolSection) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = carpoolRidersText,
                                    onValueChange = { carpoolRidersText = it },
                                    label = { Text("Rider Names (Comma Separated)") },
                                    placeholder = { Text("e.g. Rahul, Sneha, Amit") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("input_manual_carpool_riders")
                                )
                                OutlinedTextField(
                                    value = carpoolFareText,
                                    onValueChange = { carpoolFareText = it },
                                    label = { Text("Total Fare Earned (\u20b9)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("input_manual_carpool_fare")
                                )
                            }
                        }
                    }
                }

                if (tab == TripLogTab.SERVICE) {
                    // ── Service task picker ─────────────────────────────────────
                    val selectedItem = MaintenanceCatalog.KYLAQ_ITEMS.firstOrNull { it.id == serviceItemId }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, NeonEmerald.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { serviceTaskMenuOpen = true },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Service Task", color = TextSecondaryDark, fontSize = 10.sp)
                                    Text(
                                        selectedItem?.label ?: "Choose a task from the service plan",
                                        color = if (selectedItem == null) TextSecondaryDark else TextPrimaryDark,
                                        fontSize = 13.sp,
                                        fontWeight = if (selectedItem == null) FontWeight.Normal else FontWeight.SemiBold
                                    )
                                }
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose service task", tint = NeonEmerald)
                            }
                            DropdownMenu(expanded = serviceTaskMenuOpen, onDismissRequest = { serviceTaskMenuOpen = false }) {
                                MaintenanceCatalog.KYLAQ_ITEMS.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(item.label, fontSize = 13.sp) },
                                        onClick = {
                                            serviceItemId = item.id
                                            // The pollen tag belongs to the cabin filter only.
                                            if (!PollenFilterTask.appliesTo(item.id)) pollenTagged = false
                                            serviceTaskMenuOpen = false
                                        },
                                        modifier = Modifier.testTag("service_task_${item.id}")
                                    )
                                }
                            }

                            // Pollen filter task tagging - visible whenever the cabin filter is
                            // picked, so a DIY filter swap is identifiable in the ledger later.
                            if (draft.pollenTagAvailable) {
                                Surface(
                                    color = if (pollenTagged) NeonEmerald.copy(alpha = 0.12f) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, NeonEmerald.copy(alpha = if (pollenTagged) 0.5f else 0.2f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pollenTagged = !pollenTagged }
                                        .testTag(PollenFilterTask.TEST_TAG)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (pollenTagged) Icons.Default.CheckCircle else Icons.Default.AddCircleOutline,
                                            contentDescription = null,
                                            tint = NeonEmerald,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Tag as ${PollenFilterTask.LABEL} task",
                                                color = TextPrimaryDark,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                "Writes ${PollenFilterTask.NOTE_TAG} into the ledger note so the swap is searchable and exportable.",
                                                color = TextSecondaryDark,
                                                fontSize = 10.sp,
                                                lineHeight = 13.sp
                                            )
                                        }
                                    }
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = serviceOdoText,
                                    onValueChange = { serviceOdoText = it },
                                    label = { Text("Odometer (km)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).testTag("input_service_odometer")
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).clickable {
                                            serviceDateMs = System.currentTimeMillis()
                                        }
                                    ) {
                                        Text("Service Date", color = TextSecondaryDark, fontSize = 10.sp)
                                        Text(
                                            RecordTime.format("dd MMM yyyy", serviceDateMs),
                                            color = TextPrimaryDark,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text("Tap for today", color = TextSecondaryDark, fontSize = 9.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Invoice cost + PDF attachment (both tabs) ───────────────────
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = invoiceCostText,
                        onValueChange = { invoiceCostText = it },
                        label = { Text("Invoice Cost (\u20b9)") },
                        isError = invoiceCostInvalid,
                        // Always supplied: a nullable composable-lambda slot infers poorly inside
                        // an if/else, and an empty caption keeps the field height stable anyway.
                        supportingText = {
                            Text(
                                if (invoiceCostInvalid) "Enter an amount, e.g. 1250.50" else " ",
                                fontSize = 10.sp,
                                color = if (invoiceCostInvalid) WarningRed else Color.Transparent
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null, tint = ElectricAmber) },
                        modifier = Modifier.weight(1f).testTag("input_invoice_cost")
                    )
                    OutlinedButton(
                        onClick = { invoicePicker.launch(ServiceInspectionRules.PDF_MIME_TYPES) },
                        modifier = Modifier
                            .align(Alignment.Top)
                            .padding(top = 8.dp)
                            .testTag("btn_attach_invoice_pdf")
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberCyan)
                        Spacer(Modifier.width(4.dp))
                        Text(if (invoiceUri == null) "PDF" else "Change", fontSize = 11.sp, color = CyberCyan)
                    }
                }

                if (invoiceUri != null) {
                    Surface(
                        color = CyberCyan.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(16.dp))
                            Text(
                                ServiceInspectionRules.invoiceSummary(invoiceName, invoiceCost),
                                color = TextPrimaryDark,
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f).testTag("label_invoice_pdf"),
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { invoiceUri = null; invoiceName = null },
                                modifier = Modifier.size(32.dp).testTag("btn_clear_invoice_pdf")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove invoice", tint = WarningRed, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Optional Notes
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text(if (tab == TripLogTab.TRIP) "Notes / Context" else "Work Done / Parts Used") },
                    placeholder = {
                        Text(
                            if (tab == TripLogTab.TRIP) "e.g. Drove with windows open, no OBD dongle"
                            else "e.g. Cabin filter replaced, part no. 6R0819653"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_service_notes")
                )

                if (tab == TripLogTab.SERVICE && !serviceValid) {
                    Text(
                        text = serviceValidation.message.ifBlank { "Pick a service task to continue" },
                        color = WarningRed,
                        fontSize = 10.5.sp
                    )
                }
            }
        },
        confirmButton = {
            if (tab == TripLogTab.TRIP) {
                Button(
                    onClick = {
                        if (tripValid) onSave(draft.toManualTripRequest())
                    },
                    enabled = tripValid,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald),
                    modifier = Modifier.testTag("btn_confirm_manual_trip")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save & Fill Trip", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        if (serviceValid) draft.toServiceRequest()?.let(onSaveService)
                    },
                    enabled = serviceValid,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald),
                    modifier = Modifier.testTag("btn_confirm_service_inspection")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save Inspection", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}

/**
 * Same width for "Drive (D)" and "Sport (S)". 96.dp fits both labels at 11.sp with the chip's
 * own 8.dp horizontal padding, and leaves room for the selected-state border.
 */
private val TRANSMISSION_CHIP_WIDTH = 96.dp

/** "ON"/"OFF" are equal-length labels; the fixed width stops the pair re-flowing. */
private val AC_CHIP_WIDTH = 66.dp

/**
 * Reads DISPLAY_NAME for a picked document. Providers are allowed to return null (and some do
 * for cloud-backed files), so every step is null-safe and the caller falls back to the URI.
 */
private fun queryPdfDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
} catch (e: Exception) {
    null
}
