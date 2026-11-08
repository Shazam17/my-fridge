package com.example.myapplication.data.s3

import org.junit.Assert.assertEquals
import org.junit.Test

class SigV4Test {

    /**
     * Эталон AWS «Examples of the complete Signature Version 4 signing process» (IAM ListUsers):
     * https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_aws-signing.html
     * Проверяет сам алгоритм подписи (ключ, scope, string-to-sign). Заголовок x-amz-content-sha256 здесь не используется.
     */
    @Test
    fun matchesAwsReferenceIamListUsers() {
        val signer = SigV4(
            accessKey = "AKIDEXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            region = "us-east-1",
            service = "iam",
            includePayloadHeader = false,
        )
        val signed = signer.sign(
            method = "GET",
            host = "iam.amazonaws.com",
            canonicalUri = "/",
            query = "Action=ListUsers&Version=2010-05-08",
            payloadSha256 = SigV4.EMPTY_SHA256,
            timestamp = 1440938160000L, // 20150830T123600Z
            extraHeaders = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"),
        )
        assertEquals("20150830T123600Z", signed.amzDate)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/iam/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7",
            signed.authorization,
        )
    }

    @Test
    fun uriEncodeKeepsUnreservedAndEncodesRest() {
        assertEquals("a-b_c.d~e", SigV4.uriEncode("a-b_c.d~e", keepSlash = false))
        assertEquals("a%20b%2Fc", SigV4.uriEncode("a b/c", keepSlash = false))
        assertEquals("a/b%20c", SigV4.uriEncode("a/b c", keepSlash = true))
    }

    @Test
    fun formatAmzDateIsUtc() {
        assertEquals("20150830T123600Z", SigV4.formatAmzDate(1440938160000L))
    }
}
