with open("app/src/main/java/com/example/ui/viewmodel/CatalogViewModel.kt", "r") as f:
    vm = f.read()

old_variants = """    val variants = _selectedGeneration.flatMapLatest { gen ->
        if (gen != null) repository.getVariants(gen.id)
        else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())"""

new_variants = """    val variants = combine(_selectedGeneration, _selectedYear) { gen, year ->
        if (gen != null && year != null) repository.getVariants(gen.id, year)
        else flowOf(emptyList())
    }.flatMapLatest { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())"""

vm = vm.replace(old_variants, new_variants)

with open("app/src/main/java/com/example/ui/viewmodel/CatalogViewModel.kt", "w") as f:
    f.write(vm)
