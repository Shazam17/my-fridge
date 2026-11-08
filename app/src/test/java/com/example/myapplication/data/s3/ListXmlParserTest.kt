package com.example.myapplication.data.s3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListXmlParserTest {

    private val truncated = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ListBucketResult>
          <IsTruncated>true</IsTruncated>
          <NextContinuationToken>tok&amp;1</NextContinuationToken>
          <Contents><Key>fam/events/dev-a/000000000001-000000000002.jsonl</Key><Size>120</Size></Contents>
          <Contents><Key>fam/events/dev-b/a&amp;b.jsonl</Key><Size>7</Size></Contents>
        </ListBucketResult>
    """.trimIndent()

    @Test
    fun parsesKeysAndSizesAndUnescapes() {
        val keys = ListXmlParser.keys(truncated)
        assertEquals(2, keys.size)
        assertEquals("fam/events/dev-a/000000000001-000000000002.jsonl", keys[0].key)
        assertEquals(120L, keys[0].size)
        assertEquals("fam/events/dev-b/a&b.jsonl", keys[1].key)
    }

    @Test
    fun returnsNextTokenOnlyWhenTruncated() {
        assertEquals("tok&1", ListXmlParser.nextToken(truncated))
        val last = truncated.replace("<IsTruncated>true</IsTruncated>", "<IsTruncated>false</IsTruncated>")
        assertNull(ListXmlParser.nextToken(last))
    }

    @Test
    fun emptyBucketPrefixGivesNoKeys() {
        val empty = "<ListBucketResult><IsTruncated>false</IsTruncated></ListBucketResult>"
        assertEquals(emptyList<RemoteKey>(), ListXmlParser.keys(empty))
        assertNull(ListXmlParser.nextToken(empty))
    }
}
