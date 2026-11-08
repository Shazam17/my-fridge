package com.example.myapplication.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Колбэк нативного кода: получает сырые байты токена (см. llm_jni.cpp). */
fun interface TokenSink {
    fun onBytes(bytes: ByteArray)
}

/**
 * Локальная LLM на llama.cpp (docs/LOCAL_LLM.md). Модель лежит в filesDir и загружается при первом использовании.
 * Все вызовы идут в IO-потоке; одновременно выполняется одна генерация.
 */
class LocalLlm(private val context: Context) {

    private val modelFile: File get() = File(context.filesDir, MODEL_NAME)

    /** true, если модель скачана и загружена в память. */
    @Volatile
    var isLoaded: Boolean = false
        private set

    val isModelDownloaded: Boolean get() = modelFile.isFile && modelFile.length() == MODEL_SIZE_BYTES

    /** Загружает модель из filesDir. Возвращает false, если файла нет или он не читается. */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true
        if (!isModelDownloaded) return@withContext false
        isLoaded = nativeLoad(modelFile.absolutePath)
        isLoaded
    }

    /**
     * Генерирует ответ. [onText] получает накопленные фрагменты строк по мере генерации
     * (UTF-8 собирается из байтов, неполные символы не теряются).
     * Возвращает полный текст ответа или null при ошибке.
     */
    suspend fun generate(
        system: String,
        user: String,
        maxTokens: Int = 256,
        onText: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        if (!load()) return@withContext null
        val pending = ByteArrayOutputStream()
        val full = StringBuilder()
        val sink = TokenSink { bytes ->
            pending.write(bytes)
            // Декодируем только полные UTF-8 последовательности, остаток придёт со следующим токеном.
            val complete = Utf8Boundary.completeLength(pending.toByteArray())
            if (complete > 0) {
                val text = String(pending.toByteArray(), 0, complete, Charsets.UTF_8)
                val rest = pending.toByteArray().copyOfRange(complete, pending.size())
                pending.reset()
                pending.write(rest)
                full.append(text)
                onText(text)
            }
        }
        val n = nativeGenerate(system, user, maxTokens, sink)
        if (n < 0) return@withContext null
        full.toString()
    }

    /** Останавливает текущую генерацию (не блокирует). */
    fun stop() = nativeStop()

    /** Освобождает модель из памяти. */
    suspend fun unload() = withContext(Dispatchers.IO) {
        nativeUnload()
        isLoaded = false
    }

    private external fun nativeLoad(path: String): Boolean
    private external fun nativeUnload()
    private external fun nativeStop()
    private external fun nativeGenerate(system: String, user: String, maxTokens: Int, onToken: TokenSink): Int

    companion object {
        private const val TAG = "LocalLlm"

        /** Qwen2.5-0.5B-Instruct, GGUF Q4_K_M (~491 МБ). Имя и размер фиксированы для проверки загрузки. */
        const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        const val MODEL_SIZE_BYTES = 491_400_032L
        const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"

        init {
            System.loadLibrary("localllm")
        }
    }
}

/** Границы UTF-8 символов для потока токенов. Без нативных зависимостей: покрыто юнит-тестами. */
object Utf8Boundary {
    /** Количество байтов, образующих полные символы с начала массива. */
    fun completeLength(b: ByteArray): Int {
        var i = b.size
        // Ищем начало последнего символа: до 3 байт продолжения назад.
        var back = 0
        while (i > 0 && back < 4 && (b[i - 1].toInt() and 0xC0) == 0x80) {
            i--
            back++
        }
        if (i == 0) return 0
        val lead = b[i - 1].toInt() and 0xFF
        val need = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        val have = b.size - (i - 1)
        return if (have >= need) b.size else i - 1
    }
}
