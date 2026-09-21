package com.swiftshop.domain.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
