package com.example.myapplication.data.s3

/** Разбор ответа S3 ListObjectsV2 (XML). Без Android, покрыт юнит-тестами. */
object ListXmlParser {

    fun keys(xml: String): List<RemoteKey> =
        Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val key = tag(body, "Key") ?: return@mapNotNull null
            RemoteKey(key = unescape(key), size = tag(body, "Size")?.toLongOrNull() ?: -1L)
        }.toList()

    /** Токен следующей страницы, только если ответ обрезан (IsTruncated = true). */
    fun nextToken(xml: String): String? =
        tag(xml, "NextContinuationToken")?.takeIf { tag(xml, "IsTruncated") == "true" }?.let { unescape(it) }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
