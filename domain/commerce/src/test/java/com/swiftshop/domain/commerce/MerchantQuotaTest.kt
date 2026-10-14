package com.swiftshop.domain.commerce

import com.swiftshop.core.model.*
import com.swiftshop.domain.commerce.CheckListingEligibilityUseCase.Result
import org.junit.Assert.*
import org.junit.Test

class MerchantQuotaTest {

    private val check = CheckListingEligibilityUseCase()

    private fun listings(n: Int, shopId: String, prefix: String = "l") =
        (1..n).map { Listing(id = "$prefix$it", shopId = shopId) }

    @Test
    fun `BASIC tier quota - 10 per shop`() {
        val entitlement = TierEntitlements.BASIC
        assertEquals(10, entitlement.includedListingsPerShop)

        assertEquals(Result.Included, check(entitlement, listings(9, "s1"), "s1"))
        assertEquals(Result.AdditionalFeeRequired, check(entitlement, listings(10, "s1"), "s1"))

        // Separate shops have separate quotas
        val mixed = listings(10, "shopA", "a") + listings(5, "shopB", "b")
        assertEquals(Result.AdditionalFeeRequired, check(entitlement, mixed, "shopA"))
        assertEquals(Result.Included, check(entitlement, mixed, "shopB"))
    }

    @Test
    fun `PREMIUM tier quota - 50 total across account`() {
        val entitlement = TierEntitlements.PREMIUM
        assertEquals(50, entitlement.totalIncludedListings)

        assertEquals(Result.Included, check(entitlement, listings(49, "any"), "any"))
        assertEquals(Result.AdditionalFeeRequired, check(entitlement, listings(50, "any"), "any"))
        // Premium is counted across shops, not per shop
        val spread = listings(30, "x", "x") + listings(20, "y", "y")
        assertEquals(Result.AdditionalFeeRequired, check(entitlement, spread, "y"))
    }

    @Test
    fun `ELITE tier quota - unlimited`() {
        val entitlement = TierEntitlements.ELITE
        assertEquals(-1, entitlement.totalIncludedListings)
        assertEquals(-1, entitlement.maxShops)
        assertEquals(Result.Included, check(entitlement, listings(500, "s1"), "s1"))
    }

    @Test
    fun `Shop limit enforcement - Basic(3) Premium(5) Elite(unlimited)`() {
        assertEquals(3, TierEntitlements.BASIC.maxShops)
        assertEquals(5, TierEntitlements.PREMIUM.maxShops)
        assertEquals(-1, TierEntitlements.ELITE.maxShops)
    }
}
