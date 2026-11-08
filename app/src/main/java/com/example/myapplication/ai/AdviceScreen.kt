package com.example.myapplication.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Экран справки по продуктам на локальной модели (docs/LOCAL_LLM.md). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdviceScreen(viewModel: AdviceViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var question by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Справка") },
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
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.modelReady) {
                Text(
                    "Для справки нужна локальная модель (~490 МБ). Она скачивается один раз и работает на телефоне без интернета.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Загрузка… ${(state.progress * 100).toInt()}%")
                } else {
                    Button(onClick = viewModel::download) { Text("Скачать модель") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                return@Column
            }

            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("Вопрос про продукты") },
                placeholder = { Text("Например: что приготовить из того, что на исходе?") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.answering,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.answering) {
                    OutlinedButton(onClick = viewModel::stop) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("Остановить")
                    }
                    CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
                } else {
                    Button(
                        onClick = { viewModel.ask(question) },
                        enabled = question.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                        Text("Спросить")
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (state.answer.isNotEmpty()) {
                    Text(state.answer, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Подсказка сгенерирована моделью и может быть неточной. Сроки годности и лекарства проверяйте по упаковке и у врача.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
