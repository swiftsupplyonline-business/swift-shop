package com.swiftshop.feature.delivery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.DeliveryRequest
import com.swiftshop.core.model.DeliveryRequestStatus
import com.swiftshop.core.model.GeoPoint
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.Shop
import com.swiftshop.domain.commerce.GetListingUseCase
import com.swiftshop.domain.commerce.GetShopUseCase
import com.swiftshop.domain.delivery.CreateDeliveryRequestUseCase
import com.swiftshop.domain.delivery.ObserveDeliveryRequestUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RequestStep { DROPOFF }

sealed interface RequestDeliveryUiState {
    data object Loading : RequestDeliveryUiState
    data class Error(val message: String) : RequestDeliveryUiState
    data class Picking(
        val step: RequestStep,
        val listing: Listing,
        val shop: Shop?,
        val dropoff: GeoPoint?,
        val isSubmitting: Boolean
    ) : RequestDeliveryUiState
    data class Waiting(val request: DeliveryRequest, val shop: Shop?) : RequestDeliveryUiState
}

@HiltViewModel
class RequestDeliveryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getListing: GetListingUseCase,
    private val getShopUseCase: GetShopUseCase,
    private val createDeliveryRequestUseCase: CreateDeliveryRequestUseCase,
    private val observeDeliveryRequestUseCase: ObserveDeliveryRequestUseCase
) : ViewModel() {

    private val listingId: String = checkNotNull(savedStateHandle["listingId"])

    private val _uiState = MutableStateFlow<RequestDeliveryUiState>(RequestDeliveryUiState.Loading)
    val uiState: StateFlow<RequestDeliveryUiState> = _uiState.asStateFlow()

    private var listingCache: Listing? = null
    private var shopCache: Shop? = null

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = RequestDeliveryUiState.Loading
            getListing(listingId).fold(
                onSuccess = { listing ->
                    if (listing.listingType != com.swiftshop.core.model.ListingType.DELIVER) {
                        _uiState.value = RequestDeliveryUiState.Error("This listing isn't a delivery service")
                        return@fold
                    }
                    listingCache = listing
                    val shop = getShopUseCase(listing.shopId).getOrNull()
                    shopCache = shop
                    _uiState.value = RequestDeliveryUiState.Picking(
                        listing = listing, shop = shop, step = RequestStep.DROPOFF,
                        dropoff = null, isSubmitting = false
                    )
                },
                onFailure = { _uiState.value = RequestDeliveryUiState.Error(it.message ?: "Failed to load listing") }
            )
        }
    }

    fun onDropoffSelected(point: GeoPoint) {
        val state = _uiState.value as? RequestDeliveryUiState.Picking ?: return
        _uiState.value = state.copy(dropoff = point)
    }

    fun submitRequest() {
        val state = _uiState.value as? RequestDeliveryUiState.Picking ?: return
        val dropoff = state.dropoff ?: return

        _uiState.value = state.copy(isSubmitting = true)
        viewModelScope.launch {
            createDeliveryRequestUseCase(listingId, dropoff).fold(
                onSuccess = { requestId -> observeRequest(requestId) },
                onFailure = {
                    _uiState.value = state.copy(isSubmitting = false)
                }
            )
        }
    }

    private fun observeRequest(requestId: String) {
        viewModelScope.launch {
            observeDeliveryRequestUseCase(requestId).collect { request ->
                _uiState.value = RequestDeliveryUiState.Waiting(request, shopCache)
            }
        }
    }

    /** Called from the Waiting state when the user wants to try a different provider. */
    fun startOver() {
        val listing = listingCache
        if (listing == null) { load(); return }
        _uiState.value = RequestDeliveryUiState.Picking(
            listing = listing, shop = shopCache, step = RequestStep.DROPOFF,
            dropoff = null, isSubmitting = false
        )
    }
}
