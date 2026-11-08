package com.example.myapplication.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.sync.SyncResult
import com.example.myapplication.data.sync.SyncUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Состояние кнопки «Синхронизировать» (SYNC_SPEC.md, раздел 7). */
data class SyncUiState(
    val running: Boolean = false,
    val message: String? = null,
)

class SyncViewModel(
    private val useCase: SyncUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    fun sync() {
        if (_state.value.running) return
        _state.value = SyncUiState(running = true)
        viewModelScope.launch {
            val message = describe(useCase.run())
            _state.value = SyncUiState(running = false, message = message)
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun describe(result: SyncResult): String = when (result) {
        SyncResult.AlreadySynced -> "Уже синхронизировано"
        SyncResult.Uploaded -> "Загружено в хранилище"
        SyncResult.Merged -> "Синхронизировано"
        is SyncResult.Failed -> result.error.message ?: "Ошибка синхронизации"
        is SyncResult.Corrupted -> "Файл в хранилище повреждён. Синхронизация отменена, локальные данные не изменены."
        is SyncResult.SchemaTooNew -> "Файл создан более новой версией приложения. Обновите приложение."
    }
}
