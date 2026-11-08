package com.example.myapplication.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает GGUF-модель в filesDir (docs/LOCAL_LLM.md). Сначала пишет во временный файл,
 * после проверки размера переименовывает — прерванная загрузка не остаётся как «готовая» модель.
 */
class ModelDownloader(private val context: Context) {

    /**
     * Скачивает модель. [onProgress] получает долю 0..1. Возвращает true при успехе.
     */
    suspend fun download(onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, LocalLlm.MODEL_NAME)
        val tmp = File(context.filesDir, "${LocalLlm.MODEL_NAME}.part")
        val conn = URL(LocalLlm.MODEL_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext false
            val total = LocalLlm.MODEL_SIZE_BYTES
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            if (tmp.length() != LocalLlm.MODEL_SIZE_BYTES) {
                tmp.delete()
                return@withContext false
            }
            target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            conn.disconnect()
        }
    }
}
