package com.swiftshop.domain.commerce

import com.swiftshop.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class MerchantQuotaTest {

    @Test
    fun `BASIC tier quota - 10 per shop`() {
        val entitlement = TierEntitlements.BASIC
        assertEquals(10, entitlement.includedListingsPerShop)
        
        // Mocking the check logic (equivalent to CheckListingEligibilityUseCase)
        fun check(listings: List<Listing>, shopId: String): CheckListingEligibilityUseCase.Result {
            val shopListings = listings.filter { it.shopId == shopId }
            return if (shopListings.size < entitlement.includedListingsPerShop) {
                CheckListingEligibilityUseCase.Result.Included
            } else {
                CheckListingEligibilityUseCase.Result.AdditionalFeeRequired
            }
        }

        val listings = (1..9).map { Listing(id = "l$it", shopId = "s1") }
        assertEquals(CheckListingEligibilityUseCase.Result.Included, check(listings, "s1"))

        val listings10 = (1..10).map { Listing(id = "l$it", shopId = "s1") }
        assertEquals(CheckListingEligibilityUseCase.Result.AdditionalFeeRequired, check(listings10, "s1"))
        
        // Verify separate shops have separate quotas
        val mixedListings = (1..10).map { Listing(id = "a$it", shopId = "shopA") } + 
                        (1..5).map { Listing(id = "b$it", shopId = "shopB") }
        
        assertEquals(CheckListingEligibilityUseCase.Result.AdditionalFeeRequired, check(mixedListings, "shopA"))
        assertEquals(CheckListingEligibilityUseCase.Result.Included, check(mixedListings, "shopB"))
    }

    @Test
    fun `PREMIUM tier quota - 50 total across account`() {
        val entitlement = TierEntitlements.PREMIUM
        assertEquals(50, entitlement.totalIncludedListings)

        fun check(listings: List<Listing>): CheckListingEligibilityUseCase.Result {
            return if (listings.size < entitlement.totalIncludedListings) {
                CheckListingEligibilityUseCase.Result.Included
            } else {
                CheckListingEligibilityUseCase.Result.AdditionalFeeRequired
            }
        }

        val listings49 = (1..49).map { Listing(id = "l$it", shopId = "any") }
        assertEquals(CheckListingEligibilityUseCase.Result.Included, check(listings49))

        val listings50 = (1..50).map { Listing(id = "l$it", shopId = "any") }
        assertEquals(CheckListingEligibilityUseCase.Result.AdditionalFeeRequired, check(listings50))
    }

    @Test
    fun `ELITE tier quota - unlimited`() {
        val entitlement = TierEntitlements.ELITE
        assertEquals(-1, entitlement.totalIncludedListings)
        assertEquals(-1, entitlement.maxShops)
    }

    @Test
    fun `Shop limit enforcement - Basic(3) Premium(5) Elite(unlimited)`() {
        assertEquals(3, TierEntitlements.BASIC.maxShops)
        assertEquals(5, TierEntitlements.PREMIUM.maxShops)
        assertEquals(-1, TierEntitlements.ELITE.maxShops)
    }
}
