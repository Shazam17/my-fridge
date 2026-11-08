package com.example.myapplication.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.db.CommentEntity
import com.example.myapplication.domain.COMMENT_MAX_LENGTH
import com.example.myapplication.ui.common.ConfirmDialog
import com.example.myapplication.ui.common.ExpiryText
import com.example.myapplication.ui.common.formatDate
import com.example.myapplication.ui.common.stockText
import java.time.LocalDate

/** Карточка предмета (TECHNICAL_SPEC.md, раздел 7.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    viewModel: ProductDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val product = state.product
    val today = remember { LocalDate.now() }
    var deleteOpen by remember { mutableStateOf(false) }
    var newComment by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<CommentEntity?>(null) }
    var deletingComment by remember { mutableStateOf<CommentEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(product?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Редактировать") }
                    IconButton(onClick = { deleteOpen = true }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
                },
            )
        },
    ) { padding ->
        if (product == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("Предмет не найден")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = state.section?.name.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        stockText(product)?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (product.quantity != null && !product.isOutOfStock) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { viewModel.adjust(-1) },
                                    enabled = product.quantity > 0,
                                ) { Icon(Icons.Filled.Remove, contentDescription = "Уменьшить") }
                                Text(
                                    text = product.quantity.toString(),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                OutlinedButton(onClick = { viewModel.adjust(1) }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Увеличить")
                                }
                            }
                        }
                        ExpiryText(product.expiresAt, today)
                        product.dosage?.let { Text("Дозировка: $it") }
                        product.intakeFrequency?.let { Text("Периодичность: $it") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.toggleOutOfStock() }) {
                                Text(if (product.isOutOfStock) "Снять «закончился»" else "Закончился")
                            }
                            OutlinedButton(onClick = { viewModel.toggleShopping() }) {
                                Text(if (product.isInShoppingList) "Убрать из покупок" else "В список покупок")
                            }
                        }
                        OutlinedButton(onClick = { viewModel.togglePriority() }) {
                            Text(
                                if (product.isPriority) "Снять «использовать первым»"
                                else "Использовать в первую очередь",
                            )
                        }
                        if (product.isInShoppingList) {
                            Button(onClick = { viewModel.markBought() }) { Text("Куплено") }
                        }
                    }
                }
            }

            item {
                Text("Комментарии", style = MaterialTheme.typography.titleMedium)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newComment,
                        onValueChange = { if (it.length <= COMMENT_MAX_LENGTH) newComment = it },
                        label = { Text("Новый комментарий") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            viewModel.addComment(newComment) { ok -> if (ok) newComment = "" }
                        },
                        enabled = newComment.isNotBlank(),
                    ) { Text("Отправить") }
                }
            }

            items(state.comments, key = { it.uuid }) { comment ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(comment.text, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = formatDate(java.time.Instant.ofEpochMilli(comment.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { editing = comment }) { Text("Изменить") }
                            TextButton(onClick = { deletingComment = comment }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }

    if (deleteOpen && product != null) {
        ConfirmDialog(
            title = "Удалить предмет «${product.name}»?",
            text = "Комментарии к нему тоже будут удалены.",
            confirmText = "Удалить",
            onConfirm = {
                deleteOpen = false
                viewModel.delete(onBack)
            },
            onDismiss = { deleteOpen = false },
        )
    }

    deletingComment?.let { comment ->
        ConfirmDialog(
            title = "Удалить комментарий?",
            text = comment.text,
            confirmText = "Удалить",
            onConfirm = {
                viewModel.deleteComment(comment.id)
                deletingComment = null
            },
            onDismiss = { deletingComment = null },
        )
    }

    editing?.let { comment ->
        EditCommentDialog(
            initial = comment.text,
            onConfirm = { text, onResult ->
                viewModel.editComment(comment.id, text, onResult)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun EditCommentDialog(
    initial: String,
    onConfirm: (String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить комментарий") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= COMMENT_MAX_LENGTH) text = it },
                isError = invalid,
                supportingText = if (invalid) {
                    { Text("Комментарий не может быть пустым") }
                } else null,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(text) { ok -> if (ok) onDismiss() else invalid = true }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
