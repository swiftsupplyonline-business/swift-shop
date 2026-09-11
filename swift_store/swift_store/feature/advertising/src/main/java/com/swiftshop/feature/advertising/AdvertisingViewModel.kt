package com.swiftshop.feature.advertising

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.advertising.AdvertisingRepository
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AdState {
    data object Idle : AdState
    data object Launching : AdState
    data class Success(val campaignId: String) : AdState
    data class Error(val message: String) : AdState
}

@HiltViewModel
class AdvertisingViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val advertisingRepository: AdvertisingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdState>(AdState.Idle)
    val uiState: StateFlow<AdState> = _uiState.asStateFlow()

    fun createCampaign(
        contentId: String,
        contentType: CampaignContentType,
        budget: MoneyAmount,
        durationWeeks: Int,
        includeAllFeed: Boolean
    ) {
        viewModelScope.launch {
            _uiState.value = AdState.Launching
            val userId = observeCurrentUser().firstOrNull()?.uid ?: run {
                _uiState.value = AdState.Error("Not signed in")
                return@launch
            }

            val campaign = AdCampaign(
                ownerId = userId,
                contentId = contentId,
                contentType = contentType,
                budget = budget,
                durationWeeks = durationWeeks,
                status = CampaignStatus.DRAFT,
                createdAt = System.currentTimeMillis()
            )

            advertisingRepository.createCampaign(campaign, includeAllFeed).fold(
                onSuccess = { campaignId ->
                    // Deduct budget from wallet via Cloud Function — server-authoritative
                    advertisingRepository.activateCampaign(campaignId).fold(
                        onSuccess = { _uiState.value = AdState.Success(campaignId) },
                        onFailure = { _uiState.value = AdState.Error(it.message ?: "Activation failed") }
                    )
                },
                onFailure = { _uiState.value = AdState.Error(it.message ?: "Campaign creation failed") }
            )
        }
    }
}
