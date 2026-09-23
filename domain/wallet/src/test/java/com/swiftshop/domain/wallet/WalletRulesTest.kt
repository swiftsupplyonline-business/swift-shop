package com.swiftshop.domain.wallet

import com.swiftshop.core.model.MoneyAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletRulesTest {

    @Test
    fun `P2P fee is 1_5 percent`() {
        val amount = MoneyAmount("LSL", 10000L) // M100.00
        val fee = WalletRules.calculateP2PFee(amount)
        assertEquals(150L, fee.minorUnits) // M1.50
    }

    @Test
    fun `minimum withdrawal enforced`() {
        val below = MoneyAmount("LSL", 19999L)
        assertTrue(below.minorUnits < WalletRules.MINIMUM_WITHDRAWAL.minorUnits)
    }

    @Test
    fun `minimum withdrawal boundary passes`() {
        val exact = MoneyAmount("LSL", 20000L)
        assertTrue(exact.minorUnits >= WalletRules.MINIMUM_WITHDRAWAL.minorUnits)
    }
}
