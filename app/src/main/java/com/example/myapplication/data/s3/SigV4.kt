package com.example.myapplication.data.s3

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись запросов AWS Signature V4 для S3 (SYNC_SPEC.md, раздел 8).
 * Чистый Kotlin без зависимостей от Android, покрывается юнит-тестами.
 */
class SigV4(
    private val accessKey: String,
    private val secretKey: String,
    private val region: String,
    private val service: String = "s3",
    /** S3 требует x-amz-content-sha256; общий SigV4 (например, IAM) — нет. */
    private val includePayloadHeader: Boolean = true,
) {

    /** Заголовки, которые нужно добавить к запросу, включая Authorization. */
    data class SignedHeaders(
        val amzDate: String,
        val contentSha256: String,
        val authorization: String,
        val canonicalRequest: String,
    )

    /**
     * @param method HTTP-метод
     * @param host значение заголовка Host (с портом, если нестандартный)
     * @param canonicalUri путь, уже закодированный по RFC 3986 (с сегментами через '/')
     * @param query уже отсортированные и закодированные параметры запроса, может быть пустым
     * @param payloadSha256 hex SHA-256 тела (для пустого тела — [EMPTY_SHA256])
     * @param timestamp момент подписи в epoch ms
     */
    fun sign(
        method: String,
        host: String,
        canonicalUri: String,
        query: String,
        payloadSha256: String,
        timestamp: Long,
        extraHeaders: Map<String, String> = emptyMap(),
    ): SignedHeaders {
        val amzDate = formatAmzDate(timestamp)
        val dateStamp = amzDate.substring(0, 8)

        val headers = sortedMapOf<String, String>()
        headers["host"] = host
        if (includePayloadHeader) headers["x-amz-content-sha256"] = payloadSha256
        headers["x-amz-date"] = amzDate
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.trim() }

        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = headers.keys.joinToString(";")

        val canonicalRequest = listOf(
            method,
            canonicalUri,
            query,
            canonicalHeaders,
            signedHeaders,
            payloadSha256,
        ).joinToString("\n")

        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")

        val signature = hex(hmac(signingKey(dateStamp), stringToSign))
        val authorization =
            "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        return SignedHeaders(amzDate, payloadSha256, authorization, canonicalRequest)
    }

    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmac("AWS4$secretKey".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    companion object {
        const val ALGORITHM = "AWS4-HMAC-SHA256"
        const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        fun sha256Hex(bytes: ByteArray): String =
            hex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun hmac(key: ByteArray, data: String): ByteArray = hmac(key, data.toByteArray(Charsets.UTF_8))

        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** yyyyMMdd'T'HHmmss'Z' в UTC. */
        fun formatAmzDate(timestamp: Long): String {
            val fmt = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.format(java.util.Date(timestamp))
        }

        /** RFC 3986: кодирует всё, кроме unreserved и, при [keepSlash], разделителя '/'. */
        fun uriEncode(value: String, keepSlash: Boolean): String {
            val sb = StringBuilder()
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt().toChar()
                val unreserved = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '_' || c == '.' || c == '~'
                if (unreserved || (keepSlash && c == '/')) sb.append(c)
                else sb.append("%%%02X".format(b.toInt() and 0xFF))
            }
            return sb.toString()
        }
    }
}
