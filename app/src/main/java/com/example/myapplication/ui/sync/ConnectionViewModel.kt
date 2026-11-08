package com.example.myapplication.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.s3.ConnectionCheck
import com.example.myapplication.data.s3.S3Client
import com.example.myapplication.data.s3.SyncSettingsStore
import com.example.myapplication.domain.DEFAULT_REGION
import com.example.myapplication.domain.SyncSettings
import com.example.myapplication.domain.SyncSettingsInput
import com.example.myapplication.domain.SyncSettingsValidation
import com.example.myapplication.domain.validateSyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние проверки подключения (кнопка «Проверить подключение»). */
sealed class CheckState {
    object Idle : CheckState()
    object Running : CheckState()
    data class Success(val objectExists: Boolean) : CheckState()
    data class Failure(val message: String) : CheckState()
}

data class ConnectionFormState(
    val endpoint: String = "https://",
    val bucket: String = "",
    val region: String = DEFAULT_REGION,
    val pathStyle: Boolean = true,
    val accessKey: String = "",
    val secretKey: String = "",
    val allowInsecureLocal: Boolean = false,
    val advancedOpen: Boolean = false,
    val loaded: Boolean = false,
    val connected: Boolean = false,
    val check: CheckState = CheckState.Idle,
    val validation: SyncSettingsValidation? = null,
    val saved: Boolean = false,
) {
    val checkPassed: Boolean get() = check is CheckState.Success
}

class ConnectionViewModel(
    private val store: SyncSettingsStore,
    private val clientFactory: (SyncSettings) -> S3Client = { S3Client(it) },
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionFormState())
    val state: StateFlow<ConnectionFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = store.snapshot()
            _state.value = if (current == null) {
                _state.value.copy(loaded = true)
            } else {
                _state.value.copy(
                    loaded = true,
                    connected = true,
                    endpoint = current.endpoint,
                    bucket = current.bucket,
                    region = current.region,
                    pathStyle = current.pathStyle,
                    accessKey = current.accessKey,
                    secretKey = current.secretKey,
                )
            }
        }
    }

    fun update(transform: ConnectionFormState.() -> ConnectionFormState) {
        // При любом изменении прошлая проверка уже не актуальна.
        _state.value = _state.value.transform().copy(check = CheckState.Idle)
    }

    private fun input(): SyncSettingsInput = _state.value.let {
        SyncSettingsInput(
            endpoint = it.endpoint,
            bucket = it.bucket,
            region = it.region,
            pathStyle = it.pathStyle,
            accessKey = it.accessKey,
            secretKey = it.secretKey,
            allowInsecureLocal = it.allowInsecureLocal,
        )
    }

    /** Проверка подключения: HeadBucket + HEAD объекта семьи (SYNC_SPEC, раздел 2). */
    fun checkConnection() {
        val validation = validateSyncSettings(input())
        _state.value = _state.value.copy(validation = validation)
        val settings = validation.value ?: return

        _state.value = _state.value.copy(check = CheckState.Running)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { clientFactory(settings).checkConnection() }
            _state.value = _state.value.copy(
                check = when (result) {
                    is ConnectionCheck.Ok -> CheckState.Success(result.objectExists)
                    is ConnectionCheck.Failed -> CheckState.Failure(result.error.message ?: "Ошибка")
                },
            )
        }
    }

    /** Сохраняет настройки. [skipCheck] = true: «Сохранить без проверки». */
    fun save(skipCheck: Boolean) {
        val validation = validateSyncSettings(input())
        _state.value = _state.value.copy(validation = validation)
        val settings = validation.value ?: return
        if (!skipCheck && _state.value.check !is CheckState.Success) return

        viewModelScope.launch {
            store.save(settings)
            _state.value = _state.value.copy(saved = true, connected = true)
        }
    }

    /** Очистка подключения: ключи и настройки удаляются, локальные данные остаются. */
    fun disconnect() {
        viewModelScope.launch {
            store.clear()
            _state.value = ConnectionFormState(loaded = true)
        }
    }
}
