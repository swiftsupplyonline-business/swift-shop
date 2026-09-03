package com.swiftshop

import com.swiftshop.core.model.MoneyAmount
import com.swiftshop.core.model.UserTier
import com.swiftshop.domain.auth.SignInWithEmailUseCase
import com.swiftshop.domain.auth.SignUpUseCase
import com.swiftshop.domain.commerce.CanCreateShopUseCase
import com.swiftshop.domain.commerce.TierEntitlements
import com.swiftshop.domain.feed.RankingFactors
import com.swiftshop.domain.feed.RankingWeights
import com.swiftshop.domain.wallet.WalletRules
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

// ─── Money ────────────────────────────────────────────────────────────────────

class MoneyAmountTest {

    @Test
    fun `fromMajorUnits converts correctly`() {
        val amount = MoneyAmount.fromMajorUnits(99.0)
        assertEquals(9900L, amount.minorUnits)
        assertEquals("LSL", amount.currency)
    }

    @Test
    fun `addition works correctly`() {
        val a = MoneyAmount("LSL", 5000L)
        val b = MoneyAmount("LSL", 3000L)
        assertEquals(MoneyAmount("LSL", 8000L), a + b)
    }

    @Test
    fun `subtraction works correctly`() {
        val a = MoneyAmount("LSL", 10000L)
        val b = MoneyAmount("LSL", 3000L)
        assertEquals(MoneyAmount("LSL", 7000L), a - b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `currency mismatch throws on addition`() {
        MoneyAmount("LSL", 1000L) + MoneyAmount("USD", 1000L)
    }

    @Test
    fun `toDisplayString formats correctly`() {
        val amount = MoneyAmount("LSL", 9900L)
        assertEquals("LSL 99.00", amount.toDisplayString())
    }

    @Test
    fun `toDisplayString pads minor units correctly`() {
        val amount = MoneyAmount("LSL", 105L)
        assertEquals("LSL 1.05", amount.toDisplayString())
    }

    @Test
    fun `zero amount displays correctly`() {
        assertEquals("LSL 0.00", MoneyAmount.ZERO.toDisplayString())
    }
}

// ─── P2P Fee Calculation ──────────────────────────────────────────────────────

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

// ─── Tier Entitlements ────────────────────────────────────────────────────────

class TierEntitlementTest {

    @Test
    fun `basic tier has 1 shop limit`() {
        assertEquals(1, TierEntitlements.BASIC.maxShops)
    }

    @Test
    fun `basic tier has 3 free listings`() {
        assertEquals(3, TierEntitlements.BASIC.freeListings)
    }

    @Test
    fun `premium tier has 3 shops`() {
        assertEquals(3, TierEntitlements.PREMIUM.maxShops)
    }

    @Test
    fun `elite tier has unlimited shops`() {
        assertEquals(Int.MAX_VALUE, TierEntitlements.ELITE.maxShops)
    }

    @Test
    fun `basic tier monthly fee is zero`() {
        assertEquals(0L, TierEntitlements.BASIC.monthlyFeeMinorUnits)
    }

    @Test
    fun `premium tier monthly fee is M99`() {
        assertEquals(9900L, TierEntitlements.PREMIUM.monthlyFeeMinorUnits)
    }

    @Test
    fun `elite tier monthly fee is M499`() {
        assertEquals(49900L, TierEntitlements.ELITE.monthlyFeeMinorUnits)
    }
}

// ─── Can Create Shop Use Case ─────────────────────────────────────────────────

class CanCreateShopUseCaseTest {

    private val useCase = CanCreateShopUseCase()

    @Test
    fun `basic tier can create shop when none exist`() {
        assertTrue(useCase(UserTier.BASIC, existingShopCount = 0))
    }

    @Test
    fun `basic tier cannot create second shop`() {
        assertFalse(useCase(UserTier.BASIC, existingShopCount = 1))
    }

    @Test
    fun `premium tier can create up to 3 shops`() {
        assertTrue(useCase(UserTier.PREMIUM, existingShopCount = 2))
        assertFalse(useCase(UserTier.PREMIUM, existingShopCount = 3))
    }

    @Test
    fun `elite tier always can create shop`() {
        assertTrue(useCase(UserTier.ELITE, existingShopCount = 1000))
    }
}

// ─── Auth Validation ──────────────────────────────────────────────────────────

class AuthValidationTest {

    private val mockRepo = mockk<com.swiftshop.domain.auth.AuthRepository>(relaxed = true)

    @Test
    fun `sign up rejects blank email`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("", "password123", "password123", "Name")
        assertTrue(result.isFailure)
        assertEquals("Email is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sign up rejects short password`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("test@test.com", "short", "short", "Name")
        assertTrue(result.isFailure)
    }

    @Test
    fun `sign up rejects password mismatch`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("test@test.com", "password123", "different123", "Name")
        assertTrue(result.isFailure)
        assertEquals("Passwords do not match", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sign up rejects blank name`() = runTest {
        val useCase = SignUpUseCase(mockRepo)
        val result = useCase("test@test.com", "password123", "password123", "")
        assertTrue(result.isFailure)
        assertEquals("Name is required", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sign in rejects blank email`() = runTest {
        val useCase = SignInWithEmailUseCase(mockRepo)
        val result = useCase("", "password123")
        assertTrue(result.isFailure)
    }

    @Test
    fun `sign in rejects short password`() = runTest {
        val useCase = SignInWithEmailUseCase(mockRepo)
        val result = useCase("test@test.com", "short")
        assertTrue(result.isFailure)
    }
}

// ─── Ranking Engine ───────────────────────────────────────────────────────────

class RankingEngineTest {

    @Test
    fun `composite score sums correctly`() {
        val factors = RankingFactors(
            relevanceScore = 0.8,
            socialScore = 0.6,
            commerceScore = 0.4,
            freshnessScore = 0.5,
            personalScore = 0.7,
            qualityScore = 0.9,
            sponsoredScore = 1.0
        )
        val weights = RankingWeights()
        val score = factors.compositeScore(weights)
        // Verify it's in valid range
        assertTrue(score > 0.0)
        assertTrue(score <= 1.0)
    }

    @Test
    fun `sponsored content gets boost from sponsored score`() {
        val organic = RankingFactors(sponsoredScore = 0.0).compositeScore()
        val sponsored = RankingFactors(sponsoredScore = 1.0).compositeScore()
        assertTrue(sponsored > organic)
    }

    @Test
    fun `zero factors produce zero score`() {
        val score = RankingFactors().compositeScore()
        assertEquals(0.0, score, 0.001)
    }

    @Test
    fun `weights sum to 1_0`() {
        val w = RankingWeights()
        val sum = w.relevance + w.social + w.commerce + w.freshness + w.personal + w.quality + w.sponsored
        assertEquals(1.0, sum, 0.001)
    }
}
