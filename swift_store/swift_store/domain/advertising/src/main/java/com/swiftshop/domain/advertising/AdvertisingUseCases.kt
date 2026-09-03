package com.swiftshop.domain.advertising

import com.swiftshop.core.model.AdCampaign
import kotlinx.coroutines.flow.Flow

class ObserveUserCampaignsUseCase(private val repository: AdvertisingRepository) {
    operator fun invoke(userId: String): Flow<List<AdCampaign>> = repository.observeUserCampaigns(userId)
}

class CreateCampaignUseCase(private val repository: AdvertisingRepository) {
    suspend operator fun invoke(campaign: AdCampaign, includeAllFeed: Boolean): Result<String> =
        repository.createCampaign(campaign, includeAllFeed)
}
