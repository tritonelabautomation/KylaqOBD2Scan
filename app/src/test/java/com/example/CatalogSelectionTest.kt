package com.example

import com.example.data.catalog.CatalogRepository
import com.example.data.db.AppDatabase
import com.example.data.db.entities.*
import com.example.ui.viewmodel.CatalogViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CatalogSelectionTest {

    private lateinit var repository: CatalogRepository
    private lateinit var viewModel: CatalogViewModel

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val database = AppDatabase.getInstance(context)
        repository = CatalogRepository(database)
        viewModel = CatalogViewModel(repository)
    }

    @Test
    fun testDependencyReset() = runTest {
        val make = CatalogManufacturerEntity("m1", "Maruti")
        val model1 = CatalogModelEntity("mod1", "m1", "Swift", true)
        val model2 = CatalogModelEntity("mod2", "m1", "Baleno", true)
        
        viewModel.selectManufacturer(make)
        viewModel.selectModel(model1)
        
        assertEquals(model1, viewModel.selectedModel.value)
        
        // Change make should reset downstream
        viewModel.selectManufacturer(CatalogManufacturerEntity("m2", "Hyundai"))
        assertNull(viewModel.selectedModel.value)
        assertNull(viewModel.selectedGeneration.value)
        assertNull(viewModel.selectedYear.value)
        assertNull(viewModel.selectedVariant.value)
    }

    @Test
    fun testYearChangeResetsVariant() = runTest {
        val make = CatalogManufacturerEntity("m1", "Maruti")
        val model = CatalogModelEntity("mod1", "m1", "Swift", true)
        val gen = CatalogGenerationEntity("g1", "mod1", "3rd Gen", 2018, 2023)
        val variant = CatalogVariantEntity("v1", "g1", "VXi", null, null, null, null, 2018, 2023)
        
        viewModel.selectManufacturer(make)
        viewModel.selectModel(model)
        viewModel.selectGeneration(gen)
        viewModel.selectYear(2023)
        viewModel.selectVariant(variant)
        
        assertEquals(variant, viewModel.selectedVariant.value)
        
        // Change year should reset variant
        viewModel.selectYear(2022)
        assertNull(viewModel.selectedVariant.value)
    }
}
