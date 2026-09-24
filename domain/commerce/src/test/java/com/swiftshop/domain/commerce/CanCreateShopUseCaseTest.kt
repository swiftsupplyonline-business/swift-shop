package com.swiftshop.domain.commerce

import com.swiftshop.core.model.UserTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanCreateShopUseCaseTest {

    private val useCase = CanCreateShopUseCase()

    @Test
    fun `basic tier can create shop up to 3`() {
        assertTrue(useCase(UserTier.BASIC, existingShopCount = 0))
        assertTrue(useCase(UserTier.BASIC, existingShopCount = 2))
        assertFalse(useCase(UserTier.BASIC, existingShopCount = 3))
    }

    @Test
    fun `premium tier can create up to 5 shops`() {
        assertTrue(useCase(UserTier.PREMIUM, existingShopCount = 4))
        assertFalse(useCase(UserTier.PREMIUM, existingShopCount = 5))
    }

    @Test
    fun `elite tier always can create shop`() {
        assertTrue(useCase(UserTier.ELITE, existingShopCount = 1000))
    }
}
