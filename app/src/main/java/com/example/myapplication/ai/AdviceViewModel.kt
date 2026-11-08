package com.example.myapplication.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AdviceUiState(
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val answering: Boolean = false,
    val answer: String = "",
    val error: String? = null,
)

/** Состояние экрана справки: загрузка модели, вопрос, поток ответа (docs/LOCAL_LLM.md). */
class AdviceViewModel(
    private val llm: LocalLlm,
    private val advice: AdviceService,
    private val downloader: ModelDownloader,
) : ViewModel() {

    private val _state = MutableStateFlow(AdviceUiState(modelReady = llm.isModelDownloaded))
    val state: StateFlow<AdviceUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun download() {
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, progress = 0f, error = null) }
        viewModelScope.launch {
            val ok = downloader.download { p -> _state.update { it.copy(progress = p) } }
            _state.update {
                it.copy(
                    downloading = false,
                    modelReady = ok,
                    error = if (ok) null else "Не удалось скачать модель. Проверьте интернет и повторите.",
                )
            }
        }
    }

    fun ask(question: String) {
        if (question.isBlank() || _state.value.answering) return
        _state.update { it.copy(answering = true, answer = "", error = null) }
        job = viewModelScope.launch {
            val result = advice.ask(question) { chunk ->
                _state.update { it.copy(answer = it.answer + chunk) }
            }
            _state.update {
                it.copy(
                    answering = false,
                    error = if (result == null) "Модель не ответила. Попробуйте ещё раз." else null,
                )
            }
        }
    }

    fun stop() {
        llm.stop()
    }

    override fun onCleared() {
        // Останавливаем генерацию. Модель остаётся в памяти (LocalLlm в AppContainer).
        llm.stop()
        job?.cancel()
        super.onCleared()
    }
}
