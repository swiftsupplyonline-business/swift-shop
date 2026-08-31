package com.swiftshop.domain.advertising

import com.swiftshop.core.model.AdCampaign
import com.swiftshop.core.model.CampaignStatus
import kotlinx.coroutines.flow.Flow

interface AdvertisingRepository {
    suspend fun createCampaign(campaign: AdCampaign, includeAllFeed: Boolean): Result<String>
    suspend fun activateCampaign(campaignId: String): Result<Unit>
    suspend fun pauseCampaign(campaignId: String): Result<Unit>
    suspend fun cancelCampaign(campaignId: String): Result<Unit>
    fun observeUserCampaigns(userId: String): Flow<List<AdCampaign>>
    suspend fun getCampaignById(campaignId: String): Result<AdCampaign>
    suspend fun recordImpression(campaignId: String): Result<Unit>
    suspend fun recordClick(campaignId: String): Result<Unit>
    suspend fun recordConversion(campaignId: String): Result<Unit>
}
