package com.example.myapplication.domain

import com.example.myapplication.data.db.ProductUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProductRulesTest {

    private val today = LocalDate.of(2026, 5, 10)

    private fun input(
        name: String = "Молоко",
        quantity: String = "",
        unit: ProductUnit? = null,
        dosage: String = "",
        frequency: String = "",
    ) = ProductInput(name, quantity, unit, null, dosage, frequency)

    @Test
    fun nameIsRequired() {
        val result = validateProduct(input(name = "   "))
        assertEquals(setOf(ProductField.NAME), result.errors)
        assertNull(result.value)
    }

    @Test
    fun quantityOutOfRangeIsError() {
        assertEquals(setOf(ProductField.QUANTITY), validateProduct(input(quantity = "10000")).errors)
        assertEquals(setOf(ProductField.QUANTITY), validateProduct(input(quantity = "-1")).errors)
        assertEquals(setOf(ProductField.QUANTITY), validateProduct(input(quantity = "abc")).errors)
    }

    @Test
    fun unitDefaultsToPieceWhenQuantitySet() {
        val value = validateProduct(input(quantity = "3")).value!!
        assertEquals(3, value.quantity)
        assertEquals(ProductUnit.PIECE, value.unit)
    }

    @Test
    fun weightAndVolumeUnitsKeptWithQuantity() {
        assertEquals(ProductUnit.KILOGRAM, validateProduct(input(quantity = "2", unit = ProductUnit.KILOGRAM)).value!!.unit)
        assertEquals(ProductUnit.LITER, validateProduct(input(quantity = "1", unit = ProductUnit.LITER)).value!!.unit)
    }

    @Test
    fun unitDroppedWhenNoQuantity() {
        val value = validateProduct(input(unit = ProductUnit.PACK)).value!!
        assertNull(value.quantity)
        assertNull(value.unit)
    }

    @Test
    fun dosageAndFrequencyLimits() {
        assertEquals(setOf(ProductField.DOSAGE), validateProduct(input(dosage = "a".repeat(51))).errors)
        assertEquals(setOf(ProductField.FREQUENCY), validateProduct(input(frequency = "a".repeat(101))).errors)
        assertNull(validateProduct(input(dosage = "  ", frequency = "")).value!!.dosage)
    }

    @Test
    fun outOfStockDerivedFromQuantity() {
        assertTrue(outOfStockAfterSave(0, manualFlag = false))
        assertFalse(outOfStockAfterSave(5, manualFlag = true))
        assertTrue(outOfStockAfterSave(null, manualFlag = true))
    }

    @Test
    fun toggleSetsZeroAndRestoresOne() {
        assertEquals(StockChange(0, true), toggleOutOfStock(5, isOutOfStock = false))
        assertEquals(StockChange(1, false), toggleOutOfStock(0, isOutOfStock = true))
        assertEquals(StockChange(null, true), toggleOutOfStock(null, isOutOfStock = false))
    }

    @Test
    fun adjustQuantityClampsAndMarksOutOfStock() {
        assertEquals(StockChange(0, true), adjustQuantity(1, -1))
        assertEquals(StockChange(0, true), adjustQuantity(0, -1))
        assertEquals(StockChange(9999, false), adjustQuantity(9999, 1))
    }

    @Test
    fun expiryStates() {
        assertNull(expiryState(null, today))
        assertEquals(ExpiryState.EXPIRED, expiryState(today.minusDays(1), today))
        assertEquals(ExpiryState.SOON, expiryState(today, today))
        assertEquals(ExpiryState.SOON, expiryState(today.plusDays(7), today))
        assertNull(expiryState(today.plusDays(8), today))
    }

    @Test
    fun commentValidation() {
        assertFalse(validateComment("   "))
        assertFalse(validateComment("a".repeat(1001)))
        assertTrue(validateComment("ok"))
    }
}
