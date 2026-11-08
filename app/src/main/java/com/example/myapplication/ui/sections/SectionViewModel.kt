package com.example.myapplication.ui.sections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.SectionRepository
import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.data.db.SectionEntity
import com.example.myapplication.domain.SectionNameError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SectionUiState(
    val section: SectionEntity? = null,
    val products: List<ProductEntity> = emptyList(),
)

class SectionViewModel(
    private val sectionId: Long,
    private val sections: SectionRepository,
    private val products: ProductRepository,
) : ViewModel() {

    val uiState: StateFlow<SectionUiState> =
        combine(sections.observeSection(sectionId), products.observeBySection(sectionId)) { section, list ->
            SectionUiState(section = section, products = list)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionUiState())

    /** Выбранные предметы для массовых действий (FR-P16). Пусто = режим выбора выключен. */
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    fun toggleOutOfStock(productId: Long) {
        viewModelScope.launch { products.toggleOutOfStockFlag(productId) }
    }

    fun toggleSelected(productId: Long) {
        _selected.update { if (productId in it) it - productId else it + productId }
    }

    fun startSelection(productId: Long) {
        _selected.update { it + productId }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun selectAll() {
        _selected.value = uiState.value.products.map { it.id }.toSet()
    }

    /** Применяет действие к выбранным предметам и выходит из режима выбора. */
    fun bulkAction(action: suspend (ProductRepository, Collection<Long>) -> Unit) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        _selected.value = emptySet()
        viewModelScope.launch { action(products, ids) }
    }

    fun rename(name: String, onResult: (SectionNameError?) -> Unit) {
        viewModelScope.launch { onResult(sections.renameSection(sectionId, name)) }
    }

    fun toggleHidden() {
        val hidden = uiState.value.section?.isHidden ?: return
        viewModelScope.launch { sections.setHidden(sectionId, !hidden) }
    }

    fun deleteSection(onDone: () -> Unit) {
        viewModelScope.launch {
            sections.deleteSection(sectionId)
            onDone()
        }
    }
}
