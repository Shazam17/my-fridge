package com.example.myapplication.data.s3

import com.example.myapplication.domain.HOUSEHOLD_ID
import com.example.myapplication.domain.SyncSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Ошибки, которые показываются пользователю (SYNC_SPEC.md, раздел 7). */
sealed class S3Error(message: String) : Exception(message) {
    object NoNetwork : S3Error("Нет подключения к интернету.")
    object NotConfigured : S3Error("Подключение к хранилищу не настроено. Откройте «Подключение».")
    object AccessDenied : S3Error("Нет доступа к бакету. Проверьте ключи и права.")
    object InvalidKey : S3Error("Неверный ключ доступа или подпись.")
    object BucketNotFound : S3Error("Бакет не найден. Проверьте название и адрес.")
    object Tls : S3Error("Не удалось установить защищённое соединение (TLS).")
    /** 412: объект изменён другим устройством между шагами 3 и 10 (SYNC_SPEC.md, раздел 5). */
    object PreconditionFailed : S3Error("Файл в хранилище меняется другим устройством. Повторите синхронизацию.")
    object IntegrityMismatch : S3Error("Сегмент с таким именем в хранилище отличается от локального. Обратитесь к разработчику.")
    class Http(val code: Int) : S3Error("Сервер ответил ошибкой HTTP $code.")
    class Other(cause: String) : S3Error("Ошибка: $cause")
}

/** Метаданные объекта; etag нужен для If-Match и определения гонки (412). */
data class ObjectInfo(val etag: String?, val lastModified: Long?, val size: Long?)

/** Ключ объекта из LIST с размером. */
data class RemoteKey(val key: String, val size: Long)

/** Результат проверки подключения (SYNC_SPEC.md, раздел 2). */
sealed class ConnectionCheck {
    /** Бакет доступен. [objectExists] = false означает первый запуск семьи (404). */
    data class Ok(val objectExists: Boolean) : ConnectionCheck()
    data class Failed(val error: S3Error) : ConnectionCheck()
}

/**
 * Минимальный S3-клиент поверх HttpURLConnection.
 * Адресация: path-style (`{endpoint}/{bucket}/{key}`) или virtual-hosted (`{bucket}.{host}`).
 */
class S3Client(
    private val settings: SyncSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val signer = SigV4(settings.accessKey, settings.secretKey, settings.region)

    /** Префикс событий семьи: `{HOUSEHOLD_ID}/events/` (EVENT_SOURCING_SPEC.md, раздел 4.1). */
    fun eventsPrefix(): String = "$HOUSEHOLD_ID/events/"

    /** HeadBucket + HEAD объекта семьи. */
    fun checkConnection(): ConnectionCheck {
        return try {
            try {
                head(bucketPath(), query = "")
            } catch (e: S3Error.Http) {
                throw if (e.code == 404) S3Error.BucketNotFound else e
            }
            val hasEvents = hasAnyKey(eventsPrefix())
            ConnectionCheck.Ok(objectExists = hasEvents)
        } catch (e: S3Error) {
            ConnectionCheck.Failed(e)
        } catch (e: IOException) {
            ConnectionCheck.Failed(mapIo(e))
        } catch (e: SecurityException) {
            ConnectionCheck.Failed(S3Error.NoNetwork)
        }
    }

    /** Метаданные объекта или null при 404. */
    fun headObject(key: String): ObjectInfo? {
        return try {
            val conn = request("HEAD", objectPath(key), query = "")
            ObjectInfo(
                etag = conn.getHeaderField("ETag"),
                lastModified = conn.getHeaderFieldDate("Last-Modified", 0L).takeIf { it > 0L },
                size = conn.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0L },
            )
        } catch (e: S3Error.Http) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * Скачивает объект семьи во временный файл. Возвращает ETag или null, если объекта нет (404).
     */
    fun getObjectTo(key: String, target: java.io.File): String? {
        return try {
            val conn = request("GET", objectPath(key), query = "")
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            conn.getHeaderField("ETag")
        } catch (e: S3Error.Http) {
            if (e.code == 404) null else throw e
        }
    }

    /**
     * Загружает файл как объект семьи.
     * [ifMatch] — ETag ожидаемой удалённой версии; [ifNoneMatch] = true — только если объекта нет.
     * Возвращает новый ETag. При 412 бросает [S3Error.PreconditionFailed].
     */
    fun putObjectFrom(
        key: String,
        source: java.io.File,
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false,
    ): String? {
        val payloadHash = SigV4.sha256Hex(source.readBytes())
        val extra = buildMap {
            if (ifMatch != null) put("if-match", ifMatch)
            if (ifNoneMatch) put("if-none-match", "*")
        }
        return try {
            val conn = request(
                method = "PUT",
                path = objectPath(key),
                query = "",
                payloadSha256 = payloadHash,
                body = source,
                extraHeaders = extra,
            )
            conn.getHeaderField("ETag")
        } catch (e: S3Error.Http) {
            if (e.code == 412) throw S3Error.PreconditionFailed else throw e
        }
    }

    /**
     * Загрузка сегмента событий (EVENT_SOURCING_SPEC.md, раздел 4.2): PUT с `If-None-Match: *`.
     * Возвращает true, если объект создан. При 412 объект уже существует: сверяем содержимое
     * через HEAD (ETag одиночного PUT — MD5 тела, поэтому сравниваем размер и байты скачиванием),
     * совпадение — успех, иначе [S3Error.IntegrityMismatch].
     */
    fun putSegmentIfAbsent(key: String, source: java.io.File): Boolean {
        return try {
            putObjectFrom(key, source, ifNoneMatch = true)
            true
        } catch (e: S3Error.PreconditionFailed) {
            val remote = java.io.File.createTempFile("segment-check", ".jsonl")
            try {
                getObjectTo(key, remote)
                val same = SigV4.sha256Hex(remote.readBytes()) == SigV4.sha256Hex(source.readBytes())
                if (!same) throw S3Error.IntegrityMismatch
                false
            } finally {
                remote.delete()
            }
        }
    }

    /**
     * LIST v2 по префиксу с пагинацией. Возвращает ключи всех объектов.
     * Ключи берутся из XML `<Key>`; выдача бакета без пагинации обрывается на 1000 ключах.
     */
    fun listKeys(prefix: String): List<RemoteKey> {
        val result = mutableListOf<RemoteKey>()
        var token: String? = null
        do {
            val page = listPage(prefix, maxKeys = null, continuationToken = token)
            result += page.keys
            token = page.nextToken
        } while (token != null)
        return result
    }

    /** Есть ли хотя бы один объект под префиксом: LIST с max-keys=1. */
    fun hasAnyKey(prefix: String): Boolean =
        listPage(prefix, maxKeys = 1, continuationToken = null).keys.isNotEmpty()

    private data class ListPage(val keys: List<RemoteKey>, val nextToken: String?)

    /**
     * Одна страница LIST v2. Параметры query собираются и сортируются по имени:
     * канонический query подписи SigV4 должен совпадать с отправляемым.
     */
    private fun listPage(prefix: String, maxKeys: Int?, continuationToken: String?): ListPage {
        val params = sortedMapOf<String, String>()
        params["list-type"] = "2"
        params["prefix"] = SigV4.uriEncode(prefix, keepSlash = false)
        if (maxKeys != null) params["max-keys"] = maxKeys.toString()
        if (continuationToken != null) {
            params["continuation-token"] = SigV4.uriEncode(continuationToken, keepSlash = false)
        }
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val conn = request("GET", bucketPath(), query = query)
        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        val next = ListXmlParser.nextToken(xml)
        return ListPage(ListXmlParser.keys(xml), next)
    }

    /** Скачивает объект в память (сегменты небольшие: до 1 МБ, раздел 4.1). */
    fun getBytes(key: String): ByteArray {
        val conn = request("GET", objectPath(key), query = "")
        return conn.inputStream.use { it.readBytes() }
    }

    private fun bucketPath(): String = "/${settings.bucket}"

    private fun objectPath(key: String): String = "/${settings.bucket}/$key"

    private fun head(path: String, query: String) {
        request("HEAD", path, query)
    }

    /** Выполняет подписанный запрос и возвращает соединение с успешным ответом. */
    private fun request(
        method: String,
        path: String,
        query: String,
        payloadSha256: String = SigV4.EMPTY_SHA256,
        body: java.io.File? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val base = URL(settings.endpoint)
        val host = if (base.port == -1 || base.port == base.defaultPort) base.host else "${base.host}:${base.port}"
        val encodedPath = path.split('/').joinToString("/") { SigV4.uriEncode(it, keepSlash = false) }

        val fullUrl = "${settings.endpoint}$encodedPath"
        val signed = signer.sign(
            method = method,
            host = host,
            canonicalUri = encodedPath,
            query = query,
            payloadSha256 = payloadSha256,
            timestamp = clock(),
            extraHeaders = extraHeaders,
        )

        val conn = (URL(if (query.isEmpty()) fullUrl else "$fullUrl?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Host", host)
            setRequestProperty("x-amz-date", signed.amzDate)
            setRequestProperty("x-amz-content-sha256", signed.contentSha256)
            setRequestProperty("Authorization", signed.authorization)
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.length())
                setRequestProperty("Content-Type", "application/x-sqlite3")
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { out -> body.inputStream().use { it.copyTo(out) } }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw mapHttp(code)
            }
            conn
        } catch (e: IOException) {
            throw mapIo(e)
        }
    }

    private fun mapHttp(code: Int): S3Error = when (code) {
        403 -> S3Error.AccessDenied
        401 -> S3Error.InvalidKey
        404 -> S3Error.Http(404)
        412 -> S3Error.PreconditionFailed
        else -> S3Error.Http(code)
    }

    private fun mapIo(e: IOException): S3Error = when (e) {
        is UnknownHostException -> S3Error.NoNetwork
        is SSLException -> S3Error.Tls
        else -> S3Error.Other(e.javaClass.simpleName)
    }

    companion object {
        private const val TIMEOUT_MS = 15_000
    }
}
