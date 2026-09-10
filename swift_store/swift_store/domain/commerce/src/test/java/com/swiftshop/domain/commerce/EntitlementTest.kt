package com.swiftshop.domain.commerce

import com.swiftshop.core.model.*
import org.junit.Assert.*
import org.junit.Test

class EntitlementTest {

    @Test
    fun `BASIC tier entitlements match contract`() {
        val entitlement = TierEntitlements.BASIC
        assertEquals(UserTier.BASIC, entitlement.tier)
        assertEquals(3, entitlement.maxShops)
        assertEquals(10, entitlement.includedListingsPerShop)
        assertEquals(500L, entitlement.additionalListingFeeMinorUnits)
        assertEquals(0L, entitlement.monthlyFeeMinorUnits)
        assertEquals(1, entitlement.internalPromotionAllowance)
        assertFalse(entitlement.internalPromotionUnlimited)
        assertEquals(0, entitlement.externalPromotionAllowance)
        assertFalse(entitlement.externalPromotionUnlimited)
        assertEquals(ExposureLevel.STANDARD, entitlement.exposureLevel)
    }

    @Test
    fun `PREMIUM tier entitlements match contract`() {
        val entitlement = TierEntitlements.PREMIUM
        assertEquals(UserTier.PREMIUM, entitlement.tier)
        assertEquals(5, entitlement.maxShops)
        assertEquals(50, entitlement.totalIncludedListings)
        assertEquals(500L, entitlement.additionalListingFeeMinorUnits)
        assertEquals(9900L, entitlement.monthlyFeeMinorUnits)
        assertTrue(entitlement.internalPromotionUnlimited)
        assertEquals(10, entitlement.externalPromotionAllowance)
        assertEquals(ExposureLevel.ENHANCED, entitlement.exposureLevel)
    }

    @Test
    fun `ELITE tier entitlements match contract`() {
        val entitlement = TierEntitlements.ELITE
        assertEquals(UserTier.ELITE, entitlement.tier)
        assertEquals(-1, entitlement.maxShops)
        assertEquals(-1, entitlement.totalIncludedListings)
        assertEquals(0L, entitlement.additionalListingFeeMinorUnits)
        assertEquals(49900L, entitlement.monthlyFeeMinorUnits)
        assertTrue(entitlement.internalPromotionUnlimited)
        assertEquals(50, entitlement.externalPromotionAllowance)
        assertEquals(ExposureLevel.MAXIMUM, entitlement.exposureLevel)
    }

    @Test
    fun `forTier returns correct entitlement`() {
        assertEquals(TierEntitlements.BASIC, TierEntitlements.forTier(UserTier.BASIC))
        assertEquals(TierEntitlements.PREMIUM, TierEntitlements.forTier(UserTier.PREMIUM))
        assertEquals(TierEntitlements.ELITE, TierEntitlements.forTier(UserTier.ELITE))
    }
}
