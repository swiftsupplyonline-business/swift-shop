package com.swiftshop.feature.delivery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.DeliveryRequest
import com.swiftshop.core.model.DeliveryRequestStatus
import com.swiftshop.core.model.GeoPoint
import com.swiftshop.domain.delivery.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DeliveryCheckoutUiState {
    data object Loading : DeliveryCheckoutUiState
    data class Choosing(
        val options: List<DeliveryOption>,
        val selectedListingId: String? = null,
        val dropoff: GeoPoint? = null,
        val submitting: Boolean = false
    ) : DeliveryCheckoutUiState
    data class Waiting(val request: DeliveryRequest) : DeliveryCheckoutUiState
    data class CreatingJob(val request: DeliveryRequest) : DeliveryCheckoutUiState
    data class ReadyForTracking(val routeId: String) : DeliveryCheckoutUiState
    data class Error(val message: String) : DeliveryCheckoutUiState
}

@HiltViewModel
class DeliveryCheckoutViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getDeliveryOptions: GetDeliveryOptionsUseCase,
    private val createRequest: CreatePostPurchaseDeliveryRequestUseCase,
    private val observeRequest: ObserveDeliveryRequestUseCase,
    private val createJob: CreateDeliveryJobUseCase
) : ViewModel() {
    private val orderId: String = checkNotNull(savedStateHandle["orderId"])
    private val _uiState = MutableStateFlow<DeliveryCheckoutUiState>(DeliveryCheckoutUiState.Loading)
    val uiState: StateFlow<DeliveryCheckoutUiState> = _uiState.asStateFlow()

    init { loadOptions() }

    fun loadOptions() {
        viewModelScope.launch {
            getDeliveryOptions().fold(
                onSuccess = { _uiState.value = DeliveryCheckoutUiState.Choosing(it) },
                onFailure = { _uiState.value = DeliveryCheckoutUiState.Error(it.message ?: "Failed to load delivery providers") }
            )
        }
    }

    fun selectProvider(listingId: String) {
        val state = _uiState.value as? DeliveryCheckoutUiState.Choosing ?: return
        _uiState.value = state.copy(selectedListingId = listingId)
    }

    fun selectDropoff(point: GeoPoint) {
        val state = _uiState.value as? DeliveryCheckoutUiState.Choosing ?: return
        _uiState.value = state.copy(dropoff = point)
    }

    fun submit() {
        val state = _uiState.value as? DeliveryCheckoutUiState.Choosing ?: return
        val listingId = state.selectedListingId ?: return
        val dropoff = state.dropoff ?: return
        _uiState.value = state.copy(submitting = true)

        viewModelScope.launch {
            createRequest(orderId, listingId, dropoff).fold(
                onSuccess = { requestId -> observeRequestStatus(requestId) },
                onFailure = { _uiState.value = DeliveryCheckoutUiState.Error(it.message ?: "Delivery request failed") }
            )
        }
    }

    private fun observeRequestStatus(requestId: String) {
        viewModelScope.launch {
            observeRequest(requestId).collect { request ->
                when (request.status) {
                    DeliveryRequestStatus.ACCEPTED -> {
                        _uiState.value = DeliveryCheckoutUiState.CreatingJob(request)
                        createJob(request.id).fold(
                            onSuccess = { routeId -> _uiState.value = DeliveryCheckoutUiState.ReadyForTracking(routeId) },
                            onFailure = { _uiState.value = DeliveryCheckoutUiState.Error(it.message ?: "Failed to create delivery job") }
                        )
                    }
                    DeliveryRequestStatus.DECLINED,
                    DeliveryRequestStatus.EXPIRED -> _uiState.value = DeliveryCheckoutUiState.Error("Provider ${request.status.name.lowercase()}. Choose another provider.")
                    DeliveryRequestStatus.CANCELLED -> _uiState.value = DeliveryCheckoutUiState.Error("Delivery request cancelled")
                    DeliveryRequestStatus.PENDING -> _uiState.value = DeliveryCheckoutUiState.Waiting(request)
                }
            }
        }
    }
}
