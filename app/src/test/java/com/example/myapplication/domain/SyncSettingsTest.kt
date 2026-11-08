package com.example.myapplication.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSettingsTest {

    private val valid = SyncSettingsInput(
        endpoint = "https://s3.example.com/",
        bucket = "home-inventory",
        region = "",
        pathStyle = true,
        accessKey = "AK",
        secretKey = "SK",
        allowInsecureLocal = false,
    )

    @Test
    fun validInputNormalizesValues() {
        val result = validateSyncSettings(valid.copy(bucket = " Home-Inventory ", region = ""))
        assertTrue(result.errors.toString(), result.isValid)
        assertNotNull(result.value)
        val settings = result.value!!
        assertEquals("home-inventory", settings.bucket)
        assertEquals("https://s3.example.com", settings.endpoint)
        assertEquals(DEFAULT_REGION, settings.region)
    }

    @Test
    fun emptyCredentialsAreRejected() {
        val result = validateSyncSettings(valid.copy(accessKey = " ", secretKey = ""))
        assertTrue(result.errors.containsKey(SyncField.ACCESS_KEY))
        assertTrue(result.errors.containsKey(SyncField.SECRET_KEY))
        assertNull(result.value)
    }

    @Test
    fun httpsIsRequiredExceptLocalWithConfirmation() {
        assertNull(checkEndpoint("https://s3.example.com", allowInsecureLocal = false))
        assertEquals(EndpointError.INSECURE_NOT_LOCAL, checkEndpoint("http://s3.example.com", false))
        assertEquals(EndpointError.NEEDS_CONFIRMATION, checkEndpoint("http://192.168.1.10:9000", false))
        assertNull(checkEndpoint("http://192.168.1.10:9000", true))
        assertNull(checkEndpoint("http://localhost:9000", true))
        assertEquals(EndpointError.NOT_HTTPS, checkEndpoint("ftp://s3.example.com", false))
        assertEquals(EndpointError.INVALID, checkEndpoint("s3.example.com", false))
    }

    @Test
    fun localHostDetection() {
        assertTrue(isLocalHost("localhost"))
        assertTrue(isLocalHost("10.0.0.5"))
        assertTrue(isLocalHost("172.16.4.1"))
        assertTrue(isLocalHost("192.168.0.1"))
        assertFalse(isLocalHost("172.32.0.1"))
        assertFalse(isLocalHost("8.8.8.8"))
        assertFalse(isLocalHost("example.com"))
    }
}
