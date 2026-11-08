package com.example.myapplication.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.db.ProductEntity
import com.example.myapplication.domain.ExpiryState
import com.example.myapplication.domain.SECTION_NAME_MAX_LENGTH
import com.example.myapplication.domain.SectionNameError
import com.example.myapplication.domain.expiryState
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

fun formatDate(date: LocalDate): String = date.format(DATE_FORMAT)

fun sectionIcon(key: String): ImageVector = when (key) {
    "food" -> Icons.Filled.Restaurant
    "chemistry" -> Icons.Filled.CleaningServices
    "pills" -> Icons.Filled.Medication
    else -> Icons.Filled.Category
}

fun sectionNameError(error: SectionNameError): String = when (error) {
    SectionNameError.EMPTY -> "Введите название"
    SectionNameError.TOO_LONG -> "Не больше $SECTION_NAME_MAX_LENGTH символов"
    SectionNameError.DUPLICATE -> "Раздел с таким названием уже есть"
}

fun pluralItems(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "предмет"
        mod10 in 2..4 && mod100 !in 12..14 -> "предмета"
        else -> "предметов"
    }
    return "$count $word"
}

/** Строка количества или статус «закончился». null — показывать нечего. */
fun stockText(product: ProductEntity): String? = when {
    product.isOutOfStock -> "Закончился"
    product.quantity != null -> "${product.quantity} ${product.unit?.label.orEmpty()}".trim()
    else -> null
}

@Composable
fun ExpiryText(expiresAt: LocalDate?, today: LocalDate) {
    if (expiresAt == null) return
    val color = when (expiryState(expiresAt, today)) {
        ExpiryState.EXPIRED -> MaterialTheme.colorScheme.error
        ExpiryState.SOON -> MaterialTheme.colorScheme.tertiary
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "Годен до ${formatDate(expiresAt)}",
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

/**
 * Строка предмета: название, статус/количество, срок и кнопка «Закончился» (FR-P1, FR-P6).
 * В режиме выбора (FR-P16) вместо кнопки показывается чекбокс, нажатие переключает выбор.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProductRow(
    product: ProductEntity,
    today: LocalDate,
    onClick: () -> Unit,
    onToggleOutOfStock: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceContainerLow

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = if (selectionMode) {
                { Checkbox(checked = selected, onCheckedChange = null) }
            } else null,
            headlineContent = {
                Text(
                    text = if (product.isPriority) "★ ${product.name}" else product.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (product.isPriority) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = {
                Column {
                    stockText(product)?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (product.isOutOfStock) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ExpiryText(product.expiresAt, today)
                }
            },
            trailingContent = if (!selectionMode) {
                {
                    TextButton(onClick = onToggleOutOfStock) {
                        Text(if (product.isOutOfStock) "Есть" else "Закончился")
                    }
                }
            } else null,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Диалог названия. onConfirm получает колбэк результата:
 * null — успех, диалог закрывается; иначе показывается ошибка.
 */
@Composable
fun SectionNameDialog(
    title: String,
    initial: String,
    onConfirm: (String, (SectionNameError?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var error by remember { mutableStateOf<SectionNameError?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= SECTION_NAME_MAX_LENGTH) name = it },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(sectionNameError(it)) } },
                label = { Text("Название") },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name) { result -> if (result == null) onDismiss() else error = result }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
