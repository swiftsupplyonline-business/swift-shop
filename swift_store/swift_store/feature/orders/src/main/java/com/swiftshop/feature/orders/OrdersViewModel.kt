package com.swiftshop.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.Order
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.core.model.OrderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface OrdersUiState {
    data object Loading : OrdersUiState
    data object Empty : OrdersUiState
    data class Loaded(val orders: List<Order>) : OrdersUiState
    data class Error(val message: String) : OrdersUiState
}

sealed interface OrderDetailState {
    data object Loading : OrderDetailState
    data class Loaded(val order: Order) : OrderDetailState
    data class Error(val message: String) : OrderDetailState
}

@HiltViewModel
class OrdersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val commerceRepository: CommerceRepository
) : ViewModel() {

    private val orderId: String? = savedStateHandle["orderId"]

    private val _uiState = MutableStateFlow<OrdersUiState>(OrdersUiState.Loading)
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private val _detailState = MutableStateFlow<OrderDetailState>(OrderDetailState.Loading)
    val detailState: StateFlow<OrderDetailState> = _detailState.asStateFlow()

    init {
        load()
        if (orderId != null) loadDetail()
    }

    fun load() {
        viewModelScope.launch {
            observeCurrentUser().filterNotNull().collect { user ->
                commerceRepository.observeUserOrders(user.uid)
                    .catch { _uiState.value = OrdersUiState.Error(it.message ?: "Error") }
                    .collect { orders ->
                        _uiState.value = if (orders.isEmpty()) OrdersUiState.Empty
                        else OrdersUiState.Loaded(orders.sortedByDescending { it.createdAt })
                    }
            }
        }
    }

    fun loadDetail() {
        val id = orderId ?: return
        viewModelScope.launch {
            commerceRepository.getOrder(id).fold(
                onSuccess = { _detailState.value = OrderDetailState.Loaded(it) },
                onFailure = { _detailState.value = OrderDetailState.Error(it.message ?: "Error") }
            )
        }
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch {
            commerceRepository.cancelOrder(orderId, "User requested cancellation")
        }
    }
}
