package com.example.myapplication.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.domain.EndpointError
import com.example.myapplication.domain.SyncField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errors = state.validation?.errors.orEmpty()
    var showSecret by remember { mutableStateOf(false) }
    var showAccess by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключение") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Все члены семьи вводят одинаковый идентификатор семьи и одно и то же подключение к бакету. " +
                    "Рекомендуется создать отдельный ключ, у которого права только на префикс семьи.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = state.endpoint,
                onValueChange = { v -> viewModel.update { copy(endpoint = v) } },
                label = { Text("URL эндпоинта") },
                supportingText = { Text(errors[SyncField.ENDPOINT] ?: "Полный адрес, например https://s3.example.com") },
                isError = errors.containsKey(SyncField.ENDPOINT),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.validation?.endpointError == EndpointError.NEEDS_CONFIRMATION || state.allowInsecureLocal) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.allowInsecureLocal,
                        onCheckedChange = { v -> viewModel.update { copy(allowInsecureLocal = v) } },
                    )
                    Text("Разрешить http:// для локальной сети")
                }
            }


            OutlinedTextField(
                value = state.bucket,
                onValueChange = { v -> viewModel.update { copy(bucket = v) } },
                label = { Text("Название бакета") },
                supportingText = { Text(errors[SyncField.BUCKET] ?: "Строчные буквы, цифры, дефис") },
                isError = errors.containsKey(SyncField.BUCKET),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )


            OutlinedTextField(
                value = state.accessKey,
                onValueChange = { v -> viewModel.update { copy(accessKey = v) } },
                label = { Text("S3 Access Key") },
                supportingText = { errors[SyncField.ACCESS_KEY]?.let { Text(it) } },
                isError = errors.containsKey(SyncField.ACCESS_KEY),
                singleLine = true,
                visualTransformation = if (showAccess) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showAccess = !showAccess }) {
                        Icon(
                            if (showAccess) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showAccess) "Скрыть" else "Показать",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )


            OutlinedTextField(
                value = state.secretKey,
                onValueChange = { v -> viewModel.update { copy(secretKey = v) } },
                label = { Text("S3 Secret Key") },
                supportingText = { errors[SyncField.SECRET_KEY]?.let { Text(it) } },
                isError = errors.containsKey(SyncField.SECRET_KEY),
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showSecret = !showSecret }) {
                        Icon(
                            if (showSecret) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showSecret) "Скрыть" else "Показать",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = { viewModel.update { copy(advancedOpen = !advancedOpen) } }) {
                Icon(
                    if (state.advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
                Text("Дополнительно")
            }

            if (state.advancedOpen) {
                OutlinedTextField(
                    value = state.region,
                    onValueChange = { v -> viewModel.update { copy(region = v) } },
                    label = { Text("Регион") },
                    supportingText = { errors[SyncField.REGION]?.let { Text(it) } ?: Text("Для AWS, по умолчанию us-east-1") },
                    isError = errors.containsKey(SyncField.REGION),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Path-style адресация")
                        Text(
                            "Нужна для MinIO и большинства S3-совместимых сервисов",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = state.pathStyle,
                        onCheckedChange = { v -> viewModel.update { copy(pathStyle = v) } },
                    )
                }
            }

            state.check.let { check ->
                when (check) {
                    CheckState.Idle -> Unit
                    CheckState.Running -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Text("  Проверка подключения…")
                    }
                    is CheckState.Success -> Text(
                        if (check.objectExists) "Подключение успешно. Данные семьи найдены в бакете."
                        else "Подключение успешно. Данных семьи в бакете пока нет: первое устройство загрузит свою базу.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is CheckState.Failure -> Text(check.message, color = MaterialTheme.colorScheme.error)
                }
            }

            Button(
                onClick = { viewModel.checkConnection() },
                enabled = state.check !is CheckState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Проверить подключение") }

            OutlinedButton(
                onClick = { viewModel.save(skipCheck = false) },
                enabled = state.checkPassed,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }

            TextButton(
                onClick = { viewModel.save(skipCheck = true) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить без проверки") }

            if (state.connected) {
                TextButton(
                    onClick = { confirmDisconnect = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выйти / очистить подключение") }
            }
        }
    }

    if (confirmDisconnect) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Очистить подключение?") },
            text = {
                Text("Ключи и настройки подключения будут удалены. Локальные данные на устройстве сохранятся.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    viewModel.disconnect()
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") }
            },
        )
    }
}

