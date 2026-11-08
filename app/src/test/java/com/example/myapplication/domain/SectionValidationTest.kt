package com.example.myapplication.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SectionValidationTest {

    @Test
    fun emptyAfterTrimIsError() {
        assertEquals(SectionNameError.EMPTY, validateSectionName("   ", emptyList()))
    }

    @Test
    fun tooLongIsError() {
        val name = "a".repeat(SECTION_NAME_MAX_LENGTH + 1)
        assertEquals(SectionNameError.TOO_LONG, validateSectionName(name, emptyList()))
    }

    @Test
    fun duplicateIgnoresCaseAndSpaces() {
        assertEquals(
            SectionNameError.DUPLICATE,
            validateSectionName("  косметика ", listOf("Косметика")),
        )
    }

    @Test
    fun validNameIsAccepted() {
        assertNull(validateSectionName("Косметика", listOf("Продукты")))
    }
}
