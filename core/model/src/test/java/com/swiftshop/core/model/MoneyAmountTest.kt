package com.swiftshop.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyAmountTest {

    @Test
    fun `fromMajorUnits handles rounding correctly`() {
        // Truncation would give 1998, rounding gives 1999
        val amount = MoneyAmount.fromMajorUnits(19.99)
        assertEquals(1999L, amount.minorUnits)
    }

    @Test
    fun `fromMajorUnits handles whole numbers`() {
        val amount = MoneyAmount.fromMajorUnits(100.0)
        assertEquals(10000L, amount.minorUnits)
    }

    @Test
    fun `fromMajorUnits handles zero`() {
        val amount = MoneyAmount.fromMajorUnits(0.0)
        assertEquals(0L, amount.minorUnits)
    }

    @Test
    fun `fromMajorUnits handles many decimals`() {
        val amount = MoneyAmount.fromMajorUnits(10.555)
        assertEquals(1056L, amount.minorUnits)
    }

    @Test
    fun `toDisplayString formats correctly`() {
        val amount = MoneyAmount("LSL", 1050L)
        assertEquals("LSL 10.50", amount.toDisplayString())

        val amount2 = MoneyAmount("LSL", 5L)
        assertEquals("LSL 0.05", amount2.toDisplayString())
    }

    @Test
    fun `arithmetic operations preserve currency`() {
        val a = MoneyAmount("LSL", 1000L)
        val b = MoneyAmount("LSL", 500L)

        assertEquals(1500L, (a + b).minorUnits)
        assertEquals(500L, (a - b).minorUnits)
    }

    @Test
    fun `fromDecimalString parses correctly`() {
        assertEquals(1050L, MoneyAmount.fromDecimalString("10.5").getOrThrow().minorUnits)
        assertEquals(1050L, MoneyAmount.fromDecimalString("10.50").getOrThrow().minorUnits)
        assertEquals(1000L, MoneyAmount.fromDecimalString("10").getOrThrow().minorUnits)
        assertEquals(99L, MoneyAmount.fromDecimalString("0.99").getOrThrow().minorUnits)
        assertEquals(5L, MoneyAmount.fromDecimalString("0.05").getOrThrow().minorUnits)
    }

    @Test
    fun `fromDecimalString rejects invalid inputs`() {
        assertTrue(MoneyAmount.fromDecimalString("abc").isFailure)
        assertTrue(MoneyAmount.fromDecimalString("10.555").isFailure)
        assertTrue(MoneyAmount.fromDecimalString("-10").isFailure)
    }
}
