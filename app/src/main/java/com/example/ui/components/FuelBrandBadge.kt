package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analysis.FuelBrandTagger
import com.example.analysis.FuelBrandTagger.FuelBrand
import com.example.analysis.FuelBrandTagger.FuelTagInfo
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

/**
 * Fuel Brand & Grade Tag Badge (e.g. Nayara X95 + mileX, IOCL XP95, Jio-bp, Shell).
 */
@Composable
fun FuelBrandBadge(
    fuelTag: FuelTagInfo,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val brand = fuelTag.brand
    val brandColor = brand.primaryColor
    val accentColor = brand.secondaryColor

    Surface(
        color = brandColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, brandColor.copy(alpha = 0.45f)),
        modifier = modifier
            .testTag("badge_fuel_${fuelTag.shortBrandName.lowercase()}")
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.LocalGasStation,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = fuelTag.displayBadge,
                color = accentColor,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            if (onClick != null) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit Fuel Tag",
                    tint = accentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(9.dp)
                )
            }
        }
    }
}

/**
 * Dialog to change or tag the fuel used on a particular trip (e.g. Nayara, IOCL, Jio-bp, Shell).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelTagSelectorDialog(
    currentTag: FuelTagInfo,
    onDismiss: () -> Unit,
    onTagSelected: (station: String, grade: String, additive: String?, dosageMl: Double?) -> Unit
) {
    var selectedBrand by remember { mutableStateOf(currentTag.brand) }
    var stationName by remember { mutableStateOf(currentTag.stationName) }
    var fuelGrade by remember { mutableStateOf(currentTag.grade) }
    var additiveName by remember { mutableStateOf(currentTag.additiveName ?: "") }
    var dosageMl by remember { mutableStateOf(currentTag.additiveDosageMl?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.LocalGasStation, contentDescription = null, tint = selectedBrand.secondaryColor)
                Text("Tag Fuel Used for Trip", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Select or specify the petrol pump bunk & grade in the tank during this drive:",
                    color = TextSecondaryDark,
                    fontSize = 12.sp
                )

                // Quick Brand Preset Chips
                Text("Popular Petrol Bunks:", color = TextSecondaryDark, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        FuelBrand.NAYARA to "Nayara X95",
                        FuelBrand.IOCL to "IOCL XP95",
                        FuelBrand.JIO_BP to "Jio-bp",
                        FuelBrand.SHELL to "Shell"
                    ).forEach { (b, label) ->
                        FilterChip(
                            selected = selectedBrand == b,
                            onClick = {
                                selectedBrand = b
                                when (b) {
                                    FuelBrand.NAYARA -> {
                                        stationName = "Nayara Energy"
                                        fuelGrade = "X95"
                                        additiveName = "Dorf Ketal mileX"
                                        dosageMl = "5.0"
                                    }
                                    FuelBrand.IOCL -> {
                                        stationName = "IOCL"
                                        fuelGrade = "XP95"
                                        additiveName = ""
                                        dosageMl = ""
                                    }
                                    FuelBrand.JIO_BP -> {
                                        stationName = "Jio-bp"
                                        fuelGrade = "Active Petrol"
                                        additiveName = ""
                                        dosageMl = ""
                                    }
                                    FuelBrand.SHELL -> {
                                        stationName = "Shell"
                                        fuelGrade = "V-Power"
                                        additiveName = ""
                                        dosageMl = ""
                                    }
                                    else -> {}
                                }
                            },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        FuelBrand.BPCL to "BPCL Speed",
                        FuelBrand.HPCL to "HPCL Power",
                        FuelBrand.GENERIC to "Other"
                    ).forEach { (b, label) ->
                        FilterChip(
                            selected = selectedBrand == b,
                            onClick = {
                                selectedBrand = b
                                when (b) {
                                    FuelBrand.BPCL -> {
                                        stationName = "BPCL"
                                        fuelGrade = "Speed 97"
                                        additiveName = ""
                                        dosageMl = ""
                                    }
                                    FuelBrand.HPCL -> {
                                        stationName = "HPCL"
                                        fuelGrade = "Power 95"
                                        additiveName = ""
                                        dosageMl = ""
                                    }
                                    FuelBrand.GENERIC -> {
                                        stationName = "Petrol Pump"
                                        fuelGrade = "Regular"
                                        additiveName = ""
                                        dosageMl = ""
                                    }
                                    else -> {}
                                }
                            },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }

                HorizontalDivider(color = DarkBorder, modifier = Modifier.padding(vertical = 2.dp))

                OutlinedTextField(
                    value = stationName,
                    onValueChange = {
                        stationName = it
                        selectedBrand = FuelBrandTagger.detectBrand(it)
                    },
                    label = { Text("Petrol Bunk / Station") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = fuelGrade,
                        onValueChange = { fuelGrade = it },
                        label = { Text("Grade (e.g. X95)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = dosageMl,
                        onValueChange = { dosageMl = it },
                        label = { Text("Additive ml") },
                        modifier = Modifier.weight(0.8f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = additiveName,
                    onValueChange = { additiveName = it },
                    label = { Text("Additive Name (optional e.g. mileX)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onTagSelected(
                        stationName.ifBlank { "Petrol Pump" },
                        fuelGrade.ifBlank { "Regular" },
                        additiveName.takeIf { it.isNotBlank() },
                        dosageMl.toDoubleOrNull()
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
            ) {
                Text("Apply Tag", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondaryDark)
            }
        }
    )
}
