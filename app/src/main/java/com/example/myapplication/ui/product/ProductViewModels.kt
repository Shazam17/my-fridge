package com.example.myapplication.ui.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.SectionRepository
import com.example.myapplication.data.db.CommentEntity
import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.data.db.SectionEntity
import com.example.myapplication.domain.ProductInput
import com.example.myapplication.domain.ProductValidation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProductDetailState(
    val product: ProductEntity? = null,
    val section: SectionEntity? = null,
    val comments: List<CommentEntity> = emptyList(),
)

/** Карточка предмета: данные, комментарии, действия (FR-P6–P8, FR-P4, FR-P7, FR-C1–C4). */
class ProductDetailViewModel(
    private val productId: Long,
    private val sections: SectionRepository,
    private val products: ProductRepository,
) : ViewModel() {

    val uiState: StateFlow<ProductDetailState> =
        products.observeProduct(productId)
            .flatMapLatest { product ->
                if (product == null) {
                    flowOf(ProductDetailState())
                } else {
                    combine(
                        sections.observeSection(product.sectionId),
                        products.observeComments(productId),
                    ) { section, comments ->
                        ProductDetailState(product, section, comments)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductDetailState())

    fun toggleOutOfStock() = launch { products.toggleOutOfStockFlag(productId) }

    fun adjust(delta: Int) = launch { products.adjustStock(productId, delta) }

    fun toggleShopping() {
        val value = uiState.value.product?.isInShoppingList ?: return
        launch { products.setInShoppingList(productId, !value) }
    }

    fun markBought() = launch { products.markBought(productId) }

    fun togglePriority() {
        val value = uiState.value.product?.isPriority ?: return
        launch { products.setPriority(productId, !value) }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        products.deleteProduct(productId)
        onDone()
    }

    fun addComment(text: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(products.addComment(productId, text))
    }

    fun editComment(id: Long, text: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(products.editComment(id, text))
    }

    fun deleteComment(id: Long) = launch { products.deleteComment(id) }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
}

/** Форма создания и редактирования предмета (FR-P2, FR-P3, FR-P4). */
class ProductFormViewModel(
    private val productId: Long?,
    private val sections: SectionRepository,
    private val products: ProductRepository,
) : ViewModel() {

    val sectionOptions: StateFlow<List<SectionEntity>> =
        sections.observeVisibleSections()
            .map { list -> list.map { it.section } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isEditing: Boolean get() = productId != null && productId != 0L

    suspend fun loadExisting(): ProductEntity? = productId?.let { products.getProduct(it) }

    fun save(input: ProductInput, sectionId: Long, onResult: (ProductValidation) -> Unit) {
        // Раздел 0 означает «не выбран»: такой предмет не должен попадать в базу.
        if (sectionId == 0L) {
            onResult(ProductValidation(errors = emptySet(), value = null))
            return
        }
        viewModelScope.launch {
            onResult(products.saveProduct(productId, sectionId, input))
        }
    }
}
