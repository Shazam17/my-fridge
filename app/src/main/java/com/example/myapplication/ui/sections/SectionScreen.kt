package com.example.myapplication.ui.sections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.ui.common.ConfirmDialog
import com.example.myapplication.ui.common.ProductRow
import com.example.myapplication.ui.common.SectionNameDialog
import java.time.LocalDate

/**
 * Экран раздела: предметы, добавление, меню раздела (FR-SEC2–SEC4).
 * Долгое нажатие на предмет включает режим выбора (FR-P16): массовые действия внизу экрана.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(
    viewModel: SectionViewModel,
    onBack: () -> Unit,
    onOpenProduct: (Long) -> Unit,
    onAddProduct: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val selectionMode = selected.isNotEmpty()
    val today = remember { LocalDate.now() }
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val section = state.section

    // Назад в режиме выбора сначала сбрасывает выбор, а не выходит из раздела.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("Выбрано: ${selected.size}") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Отменить выбор")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Выбрать все")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(section?.name.orEmpty()) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Меню раздела")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Переименовать") },
                                onClick = { menuOpen = false; renameOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (section?.isHidden == true) "Показать" else "Скрыть") },
                                onClick = { menuOpen = false; viewModel.toggleHidden() },
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить раздел") },
                                onClick = { menuOpen = false; deleteOpen = true },
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (selectionMode) {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(onClick = { viewModel.bulkAction { repo, ids -> repo.bulkMarkOutOfStock(ids) } }) {
                            Text("Закончился")
                        }
                        TextButton(onClick = { viewModel.bulkAction { repo, ids -> repo.bulkSetPriority(ids) } }) {
                            Text("Первым")
                        }
                        TextButton(onClick = { viewModel.bulkAction { repo, ids -> repo.bulkAddToShoppingList(ids) } }) {
                            Text("В покупки")
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(
                    onClick = onAddProduct,
                    modifier = Modifier.semantics { contentDescription = "Добавить предмет" },
                ) { Icon(Icons.Filled.Add, contentDescription = null) }
            }
        },
    ) { padding ->
        if (state.products.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text("В разделе пока нет предметов") }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.products, key = { it.uuid }) { product: ProductEntity ->
                    ProductRow(
                        product = product,
                        today = today,
                        onClick = {
                            if (selectionMode) viewModel.toggleSelected(product.id)
                            else onOpenProduct(product.id)
                        },
                        onToggleOutOfStock = { viewModel.toggleOutOfStock(product.id) },
                        selectionMode = selectionMode,
                        selected = product.id in selected,
                        onLongClick = { viewModel.startSelection(product.id) },
                    )
                }
            }
        }
    }

    if (renameOpen && section != null) {
        SectionNameDialog(
            title = "Переименовать раздел",
            initial = section.name,
            onConfirm = { name, onResult -> viewModel.rename(name, onResult) },
            onDismiss = { renameOpen = false },
        )
    }
    if (deleteOpen) {
        ConfirmDialog(
            title = "Удалить раздел «${section?.name.orEmpty()}»?",
            text = "Все предметы и комментарии в нём тоже будут удалены.",
            confirmText = "Удалить",
            onConfirm = {
                deleteOpen = false
                viewModel.deleteSection(onBack)
            },
            onDismiss = { deleteOpen = false },
        )
    }
}
