package com.swiftshop.feature.delivery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.core.model.DeliveryStatus
import com.swiftshop.domain.delivery.DeliveryRepository
import com.swiftshop.domain.delivery.ObserveDeliveryRoutesByOrderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DeliveryUiState {
    data object Loading : DeliveryUiState
    data class Tracking(val route: DeliveryRoute) : DeliveryUiState
    data class Error(val message: String) : DeliveryUiState
}

@HiltViewModel
class DeliveryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DeliveryRepository,
    private val observeDeliveryRoutesByOrder: ObserveDeliveryRoutesByOrderUseCase
) : ViewModel() {

    private val routeId: String? = savedStateHandle["routeId"]
    private val orderId: String? = savedStateHandle["orderId"]

    private val _uiState = MutableStateFlow<DeliveryUiState>(DeliveryUiState.Loading)
    val uiState: StateFlow<DeliveryUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        if (routeId != null) {
            observeRoute(routeId)
        } else if (orderId != null) {
            lookupAndObserveRoutes(orderId)
        } else {
            _uiState.value = DeliveryUiState.Error("Missing route or order ID")
        }
    }

    private fun observeRoute(id: String) {
        viewModelScope.launch {
            repository.observeDeliveryRoute(id)
                .catch { _uiState.value = DeliveryUiState.Error(it.message ?: "Tracking error") }
                .collect { route ->
                    _uiState.value = DeliveryUiState.Tracking(route)
                }
        }
    }

    private fun lookupAndObserveRoutes(orderId: String) {
        viewModelScope.launch {
            observeDeliveryRoutesByOrder(orderId)
                .catch { _uiState.value = DeliveryUiState.Error(it.message ?: "Lookup error") }
                .collect { routes ->
                    val active = selectActiveRoute(routes)
                    if (active != null) {
                        _uiState.value = DeliveryUiState.Tracking(active)
                    } else if (routes.isEmpty()) {
                        _uiState.value = DeliveryUiState.Error("No delivery route found for this order")
                    } else {
                        // All routes likely cancelled or failed, but list isn't empty
                        _uiState.value = DeliveryUiState.Tracking(routes.sortedByDescending { it.createdAt }.first())
                    }
                }
        }
    }

    private fun selectActiveRoute(routes: List<DeliveryRoute>): DeliveryRoute? {
        val inProgress = listOf(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKUP, DeliveryStatus.IN_TRANSIT)
        return routes.find { it.status in inProgress }
            ?: routes.find { it.status == DeliveryStatus.REQUESTED }
            ?: routes.find { it.status == DeliveryStatus.DELIVERED }
    }
}
