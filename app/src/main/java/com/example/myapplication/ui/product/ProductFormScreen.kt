package com.example.myapplication.ui.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.db.ProductUnit
import com.example.myapplication.data.db.SectionEntity
import com.example.myapplication.domain.DOSAGE_MAX_LENGTH
import com.example.myapplication.domain.FREQUENCY_MAX_LENGTH
import com.example.myapplication.domain.PRODUCT_NAME_MAX_LENGTH
import com.example.myapplication.domain.ProductField
import com.example.myapplication.domain.ProductInput
import com.example.myapplication.domain.ProductValidation
import com.example.myapplication.ui.common.formatDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Форма предмета: создание и редактирование (FR-P2, FR-P3, FR-P4, FR-P5, FR-P13, FR-P14). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFormScreen(
    viewModel: ProductFormViewModel,
    initialSectionId: Long,
    onBack: () -> Unit,
) {
    val sections by viewModel.sectionOptions.collectAsState()
    var loaded by remember { mutableStateOf(false) }

    var name by rememberSaveable { mutableStateOf("") }
    var sectionId by rememberSaveable { mutableStateOf(initialSectionId) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf<ProductUnit?>(null) }
    var hasExpiry by rememberSaveable { mutableStateOf(false) }
    var expiryEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var dosage by rememberSaveable { mutableStateOf("") }
    var frequency by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf<Set<ProductField>>(emptySet()) }

    var sectionMenu by remember { mutableStateOf(false) }
    var unitMenu by remember { mutableStateOf(false) }
    var datePickerOpen by remember { mutableStateOf(false) }

    // Заполняем форму один раз, когда загружен существующий предмет.
    LaunchedEffect(Unit) {
        if (!loaded) {
            viewModel.loadExisting()?.let { p ->
                name = p.name
                sectionId = p.sectionId
                quantity = p.quantity?.toString().orEmpty()
                unit = p.unit
                hasExpiry = p.expiresAt != null
                expiryEpochDay = p.expiresAt?.toEpochDay()
                dosage = p.dosage.orEmpty()
                frequency = p.intakeFrequency.orEmpty()
            }
            loaded = true
        }
    }

    val currentSection: SectionEntity? = sections.firstOrNull { it.id == sectionId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Редактирование" else "Новый предмет") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= PRODUCT_NAME_MAX_LENGTH) name = it },
                label = { Text("Название") },
                singleLine = true,
                isError = ProductField.NAME in errors,
                supportingText = if (ProductField.NAME in errors) {
                    { Text("Введите название до $PRODUCT_NAME_MAX_LENGTH символов") }
                } else null,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { sectionMenu = true }) {
                    Text("Раздел: ${currentSection?.name.orEmpty()}")
                }
                DropdownMenu(expanded = sectionMenu, onDismissRequest = { sectionMenu = false }) {
                    sections.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.name) },
                            onClick = { sectionId = s.id; sectionMenu = false },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = quantity,
                    onValueChange = { v -> if (v.length <= 4 && v.all { it.isDigit() }) quantity = v },
                    label = { Text("Количество") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = ProductField.QUANTITY in errors,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { unitMenu = true }, enabled = quantity.isNotBlank()) {
                    Text(unit?.label ?: ProductUnit.PIECE.label)
                }
                DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                    ProductUnit.entries.forEach { u ->
                        DropdownMenuItem(
                            text = { Text(u.label) },
                            onClick = { unit = u; unitMenu = false },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Срок годности", modifier = Modifier.weight(1f))
                Switch(checked = hasExpiry, onCheckedChange = { hasExpiry = it })
            }
            if (hasExpiry) {
                OutlinedButton(onClick = { datePickerOpen = true }) {
                    Text(
                        expiryEpochDay?.let { formatDate(LocalDate.ofEpochDay(it)) } ?: "Выбрать дату",
                    )
                }
            }

            OutlinedTextField(
                value = dosage,
                onValueChange = { if (it.length <= DOSAGE_MAX_LENGTH) dosage = it },
                label = { Text("Дозировка (например, 500 мг)") },
                singleLine = true,
                isError = ProductField.DOSAGE in errors,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = frequency,
                onValueChange = { if (it.length <= FREQUENCY_MAX_LENGTH) frequency = it },
                label = { Text("Периодичность (например, 2 раза в день)") },
                singleLine = true,
                isError = ProductField.FREQUENCY in errors,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                // До загрузки существующего предмета sectionId может быть 0 (см. навигацию при редактировании).
                enabled = loaded,
                onClick = {
                    if (!loaded) return@Button
                    val input = ProductInput(
                        name = name,
                        quantityText = quantity,
                        unit = unit,
                        expiresAt = if (hasExpiry) expiryEpochDay?.let(LocalDate::ofEpochDay) else null,
                        dosage = dosage,
                        frequency = frequency,
                    )
                    viewModel.save(input, sectionId) { result: ProductValidation ->
                        errors = result.errors
                        if (result.value != null) onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
        }
    }

    if (datePickerOpen) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = expiryEpochDay?.let {
                LocalDate.ofEpochDay(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        expiryEpochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    datePickerOpen = false
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("Отмена") } },
        ) {
            DatePicker(state = state)
        }
    }
}
