package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.catalog.CatalogRepository
import com.example.data.catalog.CatalogVariantDetails
import com.example.data.db.entities.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CatalogViewModel(private val repository: CatalogRepository) : ViewModel() {

    val manufacturers = repository.getManufacturers().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _selectedManufacturer = MutableStateFlow<CatalogManufacturerEntity?>(null)
    val selectedManufacturer: StateFlow<CatalogManufacturerEntity?> = _selectedManufacturer.asStateFlow()

    private val _selectedModel = MutableStateFlow<CatalogModelEntity?>(null)
    val selectedModel: StateFlow<CatalogModelEntity?> = _selectedModel.asStateFlow()

    private val _selectedGeneration = MutableStateFlow<CatalogGenerationEntity?>(null)
    val selectedGeneration: StateFlow<CatalogGenerationEntity?> = _selectedGeneration.asStateFlow()
    
    private val _selectedYear = MutableStateFlow<Int?>(null)
    val selectedYear: StateFlow<Int?> = _selectedYear.asStateFlow()

    private val _selectedVariant = MutableStateFlow<CatalogVariantEntity?>(null)
    val selectedVariant: StateFlow<CatalogVariantEntity?> = _selectedVariant.asStateFlow()
    
    private val _variantDetails = MutableStateFlow<CatalogVariantDetails?>(null)
    val variantDetails: StateFlow<CatalogVariantDetails?> = _variantDetails.asStateFlow()

    val models = _selectedManufacturer.flatMapLatest { manufacturer ->
        if (manufacturer != null) repository.getModels(manufacturer.id)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val generations = _selectedModel.flatMapLatest { model ->
        if (model != null) repository.getGenerations(model.id)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val variants = _selectedGeneration.flatMapLatest { gen ->
        if (gen != null) repository.getVariants(gen.id)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectManufacturer(manufacturer: CatalogManufacturerEntity) {
        _selectedManufacturer.value = manufacturer
        _selectedModel.value = null
        _selectedGeneration.value = null
        _selectedYear.value = null
        _selectedVariant.value = null
    }

    fun selectModel(model: CatalogModelEntity) {
        _selectedModel.value = model
        _selectedGeneration.value = null
        _selectedYear.value = null
        _selectedVariant.value = null
    }

    fun selectGeneration(generation: CatalogGenerationEntity) {
        _selectedGeneration.value = generation
        _selectedYear.value = null
        _selectedVariant.value = null
    }

    fun selectYear(year: Int) {
        _selectedYear.value = year
        _selectedVariant.value = null
    }

    fun selectVariant(variant: CatalogVariantEntity) {
        _selectedVariant.value = variant
        viewModelScope.launch {
            _variantDetails.value = repository.getVariantDetails(variant.id)
        }
    }
    
    fun reset() {
        _selectedManufacturer.value = null
        _selectedModel.value = null
        _selectedGeneration.value = null
        _selectedYear.value = null
        _selectedVariant.value = null
        _variantDetails.value = null
    }
}
