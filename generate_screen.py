content = """package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.catalog.CatalogRepository
import com.example.data.db.entities.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.CatalogViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVehicleScreen(
    catalogRepository: CatalogRepository,
    onBack: () -> Unit,
    onVehicleConfirmed: (make: String, model: String, year: String, variantId: String) -> Unit
) {
    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CatalogViewModel(catalogRepository) as T
        }
    }
    val viewModel: CatalogViewModel = viewModel(factory = factory)

    val selectedManufacturer by viewModel.selectedManufacturer.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedGeneration by viewModel.selectedGeneration.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val selectedVariant by viewModel.selectedVariant.collectAsState()
    val variantDetails by viewModel.variantDetails.collectAsState()

    var showManufacturerSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showGenerationSheet by remember { mutableStateOf(false) }
    var showYearSheet by remember { mutableStateOf(false) }
    var showVariantSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Vehicle") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkCanvas,
                    titleContentColor = TextPrimaryDark,
                    navigationIconContentColor = TextPrimaryDark
                )
            )
        },
        containerColor = DarkCanvas
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "Vehicle identification",
                style = MaterialTheme.typography.titleMedium,
                color = CyberCyan,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Make
            SelectionField(
                label = "Make",
                value = selectedManufacturer?.name,
                placeholder = "Select make",
                onClick = { showManufacturerSheet = true }
            )

            // Model
            SelectionField(
                label = "Model",
                value = selectedModel?.name,
                placeholder = "Select model",
                enabled = selectedManufacturer != null,
                onClick = { showModelSheet = true }
            )

            // Generation
            SelectionField(
                label = "Generation",
                value = selectedGeneration?.name,
                placeholder = "Select generation",
                enabled = selectedModel != null,
                onClick = { showGenerationSheet = true }
            )

            // Year
            SelectionField(
                label = "Year",
                value = selectedYear?.toString(),
                placeholder = "Select year",
                enabled = selectedGeneration != null,
                onClick = { showYearSheet = true }
            )

            // Variant
            SelectionField(
                label = "Variant",
                value = selectedVariant?.name,
                placeholder = "Select variant",
                enabled = selectedYear != null,
                onClick = { showVariantSheet = true }
            )

            // Derived specs (read-only)
            if (variantDetails != null) {
                val engine = variantDetails?.engine
                val trans = variantDetails?.transmission

                SelectionField(
                    label = "Engine",
                    value = engine?.let { "${it.name} (${it.displacementCc ?: "?"} cc)" } ?: "Not specified",
                    placeholder = "",
                    enabled = false,
                    onClick = {}
                )

                SelectionField(
                    label = "Fuel",
                    value = engine?.fuelType ?: "Not specified",
                    placeholder = "",
                    enabled = false,
                    onClick = {}
                )

                SelectionField(
                    label = "Transmission",
                    value = trans?.let { "${it.name} (${it.type ?: "?"})" } ?: "Not specified",
                    placeholder = "",
                    enabled = false,
                    onClick = {}
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (variantDetails != null) {
                Divider(color = DarkBorder, modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    "Vehicle Summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = CyberCyan,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "${selectedManufacturer?.name} ${selectedModel?.name}\\n" +
                    "${selectedGeneration?.name}\\n" +
                    "${selectedYear}\\n" +
                    "${selectedVariant?.name}\\n" +
                    (variantDetails?.engine?.let { "${it.name} ${it.fuelType ?: ""}\\n" } ?: "") +
                    (variantDetails?.transmission?.name ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimaryDark,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            Button(
                onClick = {
                    val make = selectedManufacturer?.name ?: return@Button
                    val mod = selectedModel?.name ?: return@Button
                    val yr = selectedYear?.toString() ?: return@Button
                    val variantId = selectedVariant?.id ?: return@Button
                    onVehicleConfirmed(make, mod, yr, variantId)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedVariant != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan,
                    contentColor = DarkCanvas,
                    disabledContainerColor = DarkBorder,
                    disabledContentColor = TextSecondaryDark
                )
            ) {
                Text("Save Vehicle")
            }
        }
    }

    if (showManufacturerSheet) {
        val manufacturers by viewModel.manufacturers.collectAsState()
        SearchableBottomSheet(
            title = "Select Manufacturer",
            items = manufacturers,
            itemText = { it.name },
            onDismiss = { showManufacturerSheet = false },
            onSelect = { 
                viewModel.selectManufacturer(it)
                showManufacturerSheet = false 
                showModelSheet = true
            }
        )
    }

    if (showModelSheet) {
        val models by viewModel.models.collectAsState()
        SearchableBottomSheet(
            title = "Select Model",
            items = models,
            itemText = { it.name },
            onDismiss = { showModelSheet = false },
            onSelect = { 
                viewModel.selectModel(it)
                showModelSheet = false 
                showGenerationSheet = true
            }
        )
    }

    if (showGenerationSheet) {
        val generations by viewModel.generations.collectAsState()
        SearchableBottomSheet(
            title = "Select Generation",
            items = generations,
            itemText = { g -> 
                val years = if (g.startYear != null) "${g.startYear} - ${g.endYear ?: "Present"}" else ""
                "${g.name}${if (years.isNotEmpty()) " ($years)" else ""}"
            },
            onDismiss = { showGenerationSheet = false },
            onSelect = { 
                viewModel.selectGeneration(it)
                showGenerationSheet = false 
                showYearSheet = true
            },
            searchEnabled = false
        )
    }

    if (showYearSheet) {
        val start = selectedGeneration?.startYear ?: 2000
        val end = selectedGeneration?.endYear ?: 2026
        val years = (end downTo start).toList()
        SearchableBottomSheet(
            title = "Select Year",
            items = years,
            itemText = { it.toString() },
            onDismiss = { showYearSheet = false },
            onSelect = { 
                viewModel.selectYear(it)
                showYearSheet = false 
                showVariantSheet = true
            },
            searchEnabled = false
        )
    }

    if (showVariantSheet) {
        val variants by viewModel.variants.collectAsState()
        SearchableBottomSheet(
            title = "Select Variant",
            items = variants,
            itemText = { v -> 
                val specs = listOfNotNull(v.bodyType, v.drivetrain).joinToString(" • ")
                "${v.name}${if (specs.isNotEmpty()) "\\n$specs" else ""}"
            },
            onDismiss = { showVariantSheet = false },
            onSelect = { 
                viewModel.selectVariant(it)
                showVariantSheet = false 
            }
        )
    }
}

@Composable
fun SelectionField(
    label: String,
    value: String?,
    placeholder: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) TextSecondaryDark else TextMutedDark,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
            shape = RoundedCornerShape(8.dp),
            color = if (enabled) DarkSurface else DarkCanvas,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, 
                if (enabled) DarkBorder else DarkBorder.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value ?: placeholder,
                    color = if (value != null) TextPrimaryDark else TextMutedDark,
                    modifier = Modifier.weight(1f)
                )
                if (enabled) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Select",
                        tint = TextSecondaryDark
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchableBottomSheet(
    title: String,
    items: List<T>,
    itemText: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    searchEnabled: Boolean = true
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimaryDark,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            if (searchEnabled) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    placeholder = { Text("Search...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberCyan,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimaryDark,
                        unfocusedTextColor = TextPrimaryDark
                    )
                )
            }
            
            val filtered = if (searchQuery.isBlank()) items else items.filter { 
                itemText(it).contains(searchQuery, ignoreCase = true) 
            }
            
            if (filtered.isEmpty()) {
                Text(
                    "No items found.",
                    color = TextSecondaryDark,
                    modifier = Modifier.padding(vertical = 32.dp).align(Alignment.CenterHorizontally)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered) { item ->
                        val text = itemText(item)
                        val parts = text.split("\\n")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(item) }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(parts[0], color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                            if (parts.size > 1) {
                                Text(parts[1], color = TextSecondaryDark, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Divider(color = DarkBorder)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
"""
with open("app/src/main/java/com/example/ui/screens/AddVehicleScreen.kt", "w") as f:
    f.write(content)
