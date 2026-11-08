package com.example.myapplication.domain

import java.net.URI

/** Правила формы подключения (SYNC_SPEC.md, раздел 2). */
const val DEFAULT_REGION = "us-east-1"

/** Идентификатор семьи: общий для всех устройств, задаётся сборкой (QR его не содержит). */
const val HOUSEHOLD_ID = "family"

private val BUCKET_LIKE = Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$")
private val REGION_LIKE = Regex("^[a-z0-9-]{2,30}$")

/** Введённые пользователем настройки подключения. Секреты здесь же, но не сохраняются открыто. */
data class SyncSettingsInput(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val pathStyle: Boolean,
    val accessKey: String,
    val secretKey: String,
    /** Явное подтверждение http:// для localhost и LAN. */
    val allowInsecureLocal: Boolean,
)

enum class SyncField { ENDPOINT, BUCKET, REGION, ACCESS_KEY, SECRET_KEY }

enum class EndpointError { INVALID, NOT_HTTPS, INSECURE_NOT_LOCAL, NEEDS_CONFIRMATION }

data class SyncSettingsValidation(
    val errors: Map<SyncField, String>,
    val endpointError: EndpointError?,
    /** Нормализованные значения, если ошибок нет. */
    val value: SyncSettings?,
) {
    val isValid: Boolean get() = value != null
}

/** Настройки подключения, готовые к сохранению и использованию клиентом. */
data class SyncSettings(
    val endpoint: String,
    val bucket: String,
    val region: String,
    val pathStyle: Boolean,
    val accessKey: String,
    val secretKey: String,
)

fun validateSyncSettings(input: SyncSettingsInput): SyncSettingsValidation {
    val errors = mutableMapOf<SyncField, String>()



    val bucket = input.bucket.trim().lowercase()
    if (!BUCKET_LIKE.matches(bucket)) {
        errors[SyncField.BUCKET] = "3–63 символа: строчные латинские буквы, цифры, дефис"
    }

    val region = input.region.trim().ifEmpty { DEFAULT_REGION }
    if (!REGION_LIKE.matches(region)) {
        errors[SyncField.REGION] = "Недопустимый регион"
    }

    val endpoint = input.endpoint.trim()
    val endpointError = checkEndpoint(endpoint, input.allowInsecureLocal)
    if (endpointError != null) {
        errors[SyncField.ENDPOINT] = endpointMessage(endpointError)
    }

    if (input.accessKey.isBlank()) errors[SyncField.ACCESS_KEY] = "Введите ключ доступа"
    if (input.secretKey.isBlank()) errors[SyncField.SECRET_KEY] = "Введите секретный ключ"

    val value = if (errors.isEmpty() && endpointError == null) {
        SyncSettings(
            endpoint = endpoint.trimEnd('/'),
            bucket = bucket,
            region = region,
            pathStyle = input.pathStyle,
            accessKey = input.accessKey.trim(),
            secretKey = input.secretKey.trim(),
        )
    } else {
        null
    }
    return SyncSettingsValidation(errors, endpointError, value)
}

/** Возвращает ошибку адреса или null. NEEDS_CONFIRMATION — http для локальной сети без подтверждения. */
fun checkEndpoint(endpoint: String, allowInsecureLocal: Boolean): EndpointError? {
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return EndpointError.INVALID
    val host = uri.host ?: return EndpointError.INVALID
    if (uri.scheme == null) return EndpointError.INVALID
    return when (uri.scheme.lowercase()) {
        "https" -> null
        "http" -> when {
            !isLocalHost(host) -> EndpointError.INSECURE_NOT_LOCAL
            !allowInsecureLocal -> EndpointError.NEEDS_CONFIRMATION
            else -> null
        }
        else -> EndpointError.NOT_HTTPS
    }
}

/** localhost, loopback и частные IPv4-диапазоны. */
fun isLocalHost(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true)) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val (a, b) = octets[0] to octets[1]
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

private fun endpointMessage(error: EndpointError): String = when (error) {
    EndpointError.INVALID -> "Введите полный адрес, например https://s3.example.com"
    EndpointError.NOT_HTTPS -> "Адрес должен начинаться с https://"
    EndpointError.INSECURE_NOT_LOCAL -> "http:// разрешён только для localhost и локальной сети"
    EndpointError.NEEDS_CONFIRMATION -> "Подтвердите использование незащищённого http:// для локальной сети"
}
