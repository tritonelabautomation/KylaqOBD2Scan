package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (selectedManufacturer == null) {
                ManufacturerSelection(viewModel)
            } else if (selectedModel == null) {
                ModelSelection(viewModel)
            } else if (selectedGeneration == null) {
                GenerationSelection(viewModel)
            } else if (selectedYear == null) {
                YearSelection(viewModel)
            } else if (selectedVariant == null) {
                VariantSelection(viewModel)
            } else {
                ConfirmVehicleSelection(
                    viewModel = viewModel,
                    onConfirm = {
                        val make = selectedManufacturer?.name ?: ""
                        val mod = selectedModel?.name ?: ""
                        val yr = selectedYear?.toString() ?: ""
                        val variantId = selectedVariant?.id ?: ""
                        onVehicleConfirmed(make, mod, yr, variantId)
                    },
                    onBackToVariants = {
                        viewModel.selectYear(selectedYear!!) // Resets variant
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManufacturerSelection(viewModel: CatalogViewModel) {
    val manufacturers by viewModel.manufacturers.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select Manufacturer", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
        
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            placeholder = { Text("Search brands...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = DarkBorder,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark
            )
        )
        
        val filtered = manufacturers.filter { it.name.contains(searchQuery, ignoreCase = true) }
        
        LazyColumn {
            items(filtered) { m ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.selectManufacturer(m) },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Text(text = m.name, modifier = Modifier.padding(16.dp), color = TextPrimaryDark)
                }
            }
        }
    }
}

@Composable
fun ModelSelection(viewModel: CatalogViewModel) {
    val models by viewModel.models.collectAsState()
    val manufacturer by viewModel.selectedManufacturer.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("${manufacturer?.name} Models", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
        
        LazyColumn {
            items(models) { m ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.selectModel(m) },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = m.name, color = TextPrimaryDark)
                        if (!m.isCurrent) {
                            Text("Discontinued", color = TextSecondaryDark, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GenerationSelection(viewModel: CatalogViewModel) {
    val generations by viewModel.generations.collectAsState()
    val model by viewModel.selectedModel.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("${model?.name} Generation", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
        
        LazyColumn {
            items(generations) { g ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.selectGeneration(g) },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    val years = if (g.startYear != null) "${g.startYear} - ${g.endYear ?: "Present"}" else "Unknown Year"
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = g.name, color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                        Text(text = years, color = TextSecondaryDark, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun YearSelection(viewModel: CatalogViewModel) {
    val generation by viewModel.selectedGeneration.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select Year", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
        
        val start = generation?.startYear ?: 2000
        val end = generation?.endYear ?: 2026 // Fallback current year
        
        val years = (end downTo start).toList()
        
        LazyColumn {
            items(years) { y ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.selectYear(y) },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Text(text = y.toString(), modifier = Modifier.padding(16.dp), color = TextPrimaryDark)
                }
            }
        }
    }
}

@Composable
fun VariantSelection(viewModel: CatalogViewModel) {
    val variants by viewModel.variants.collectAsState()
    val generation by viewModel.selectedGeneration.collectAsState()
    val year by viewModel.selectedYear.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Select Variant ($year)", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
        
        LazyColumn {
            items(variants) { v ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { viewModel.selectVariant(v) },
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = v.name, color = TextPrimaryDark, fontWeight = FontWeight.Bold)
                        val specs = listOfNotNull(v.bodyType, v.drivetrain).joinToString(" • ")
                        if (specs.isNotEmpty()) {
                            Text(text = specs, color = TextSecondaryDark, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmVehicleSelection(viewModel: CatalogViewModel, onConfirm: () -> Unit, onBackToVariants: () -> Unit) {
    val details by viewModel.variantDetails.collectAsState()
    val year by viewModel.selectedYear.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Confirm Vehicle", style = MaterialTheme.typography.titleLarge, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
        
        if (details != null) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${details!!.manufacturer.name} ${details!!.model.name}", style = MaterialTheme.typography.headlineSmall, color = CyberCyan, fontWeight = FontWeight.Bold)
                    Text("${details!!.variant.name} ($year)", color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
                    
                    val engine = details!!.engine
                    if (engine != null) {
                        Text("Engine", color = TextSecondaryDark, style = MaterialTheme.typography.labelMedium)
                        val engineText = "${engine.name} (${engine.displacementCc ?: "?"} cc ${engine.fuelType ?: ""})"
                        Text(engineText, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 8.dp))
                        
                        val power = listOfNotNull(
                            engine.powerPs?.let { "${it} PS" },
                            engine.torqueNm?.let { "${it} Nm" }
                        ).joinToString(" / ")
                        if (power.isNotEmpty()) {
                            Text(power, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
                        }
                    }
                    
                    val trans = details!!.transmission
                    if (trans != null) {
                        Text("Transmission", color = TextSecondaryDark, style = MaterialTheme.typography.labelMedium)
                        val transText = "${trans.name} (${trans.type ?: ""})"
                        Text(transText, color = TextPrimaryDark, modifier = Modifier.padding(bottom = 16.dp))
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBackToVariants, modifier = Modifier.weight(1f)) {
                Text("Change Variant")
            }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CyberCyan, contentColor = DarkCanvas)) {
                Text("Add to Garage")
            }
        }
    }
}
