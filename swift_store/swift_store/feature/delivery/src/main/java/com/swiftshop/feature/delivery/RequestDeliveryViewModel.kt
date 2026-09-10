package com.swiftshop.feature.delivery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.commerce.GetOrderUseCase
import com.swiftshop.domain.commerce.GetShopUseCase
import com.swiftshop.domain.commerce.GetDeliveryListingsUseCase
import com.swiftshop.domain.delivery.RequestDeliveryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RequestDeliveryUiState {
    data object Loading : RequestDeliveryUiState
    data class Success(
        val order: Order,
        val shop: Shop,
        val deliveryListings: List<DeliveryListing>
    ) : RequestDeliveryUiState
    data class Error(val message: String) : RequestDeliveryUiState
}

sealed interface DeliveryRequestActionState {
    data object Idle : DeliveryRequestActionState
    data object Loading : DeliveryRequestActionState
    data class Success(val routeId: String) : DeliveryRequestActionState
    data class Error(val message: String) : DeliveryRequestActionState
}

@HiltViewModel
class RequestDeliveryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getOrder: GetOrderUseCase,
    private val getShop: GetShopUseCase,
    private val getDeliveryListings: GetDeliveryListingsUseCase,
    private val requestDelivery: RequestDeliveryUseCase
) : ViewModel() {

    private val orderId: String = checkNotNull(savedStateHandle["orderId"])

    private val _uiState = MutableStateFlow<RequestDeliveryUiState>(RequestDeliveryUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _actionState = MutableStateFlow<DeliveryRequestActionState>(DeliveryRequestActionState.Idle)
    val actionState = _actionState.asStateFlow()

    private val _destination = MutableStateFlow<GeoPoint?>(null)
    val destination = _destination.asStateFlow()

    private val _selectedListing = MutableStateFlow<DeliveryListing?>(null)
    val selectedListing = _selectedListing.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = RequestDeliveryUiState.Loading
            
            val orderResult = getOrder(orderId)
            if (orderResult.isFailure) {
                _uiState.value = RequestDeliveryUiState.Error(orderResult.exceptionOrNull()?.message ?: "Order not found")
                return@launch
            }
            val order = orderResult.getOrThrow()

            val shopResult = getShop(order.shopId)
            if (shopResult.isFailure) {
                _uiState.value = RequestDeliveryUiState.Error(shopResult.exceptionOrNull()?.message ?: "Shop not found")
                return@launch
            }
            val shop = shopResult.getOrThrow()

            val deliveryListingsResult = getDeliveryListings()
            val deliveryListings = deliveryListingsResult.getOrDefault(emptyList())

            _uiState.value = RequestDeliveryUiState.Success(order, shop, deliveryListings)
            
            // Initialize destination from order if available
            if (order.deliveryAddress.lat != 0.0) {
                _destination.value = GeoPoint(order.deliveryAddress.lat, order.deliveryAddress.lng)
            }
        }
    }

    fun onDestinationSelected(location: GeoPoint) {
        _destination.value = location
    }

    fun onListingSelected(listing: DeliveryListing) {
        _selectedListing.value = listing
    }

    fun submitRequest() {
        val currentState = _uiState.value as? RequestDeliveryUiState.Success ?: return
        val dest = _destination.value
        if (dest == null || !dest.isValid()) {
            _actionState.value = DeliveryRequestActionState.Error("Please select a destination on the map")
            return
        }

        viewModelScope.launch {
            _actionState.value = DeliveryRequestActionState.Loading
            requestDelivery(
                orderId = orderId,
                pickup = currentState.shop.location,
                dropoff = dest
            ).fold(
                onSuccess = { _actionState.value = DeliveryRequestActionState.Success(it) },
                onFailure = { _actionState.value = DeliveryRequestActionState.Error(it.message ?: "Request failed") }
            )
        }
    }

    fun clearActionState() {
        _actionState.value = DeliveryRequestActionState.Idle
    }
}
