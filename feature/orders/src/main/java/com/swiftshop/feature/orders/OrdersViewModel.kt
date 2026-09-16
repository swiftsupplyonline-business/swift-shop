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

sealed interface FulfillmentState {
    data object Idle : FulfillmentState
    data object Processing : FulfillmentState
    data object Success : FulfillmentState
    data class Error(val message: String) : FulfillmentState
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

    private val _fulfillmentState = MutableStateFlow<FulfillmentState>(FulfillmentState.Idle)
    val fulfillmentState: StateFlow<FulfillmentState> = _fulfillmentState.asStateFlow()

    private val _isSellerMode = MutableStateFlow(false)
    val isSellerMode = _isSellerMode.asStateFlow()
    private var currentJob: kotlinx.coroutines.Job? = null

    init {
        load()
        if (orderId != null) loadDetail()
    }

    fun load() {
        setSellerMode(_isSellerMode.value)
    }

    fun setSellerMode(enabled: Boolean) {
        _isSellerMode.value = enabled
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            observeCurrentUser().filterNotNull().collect { user ->
                _uiState.value = OrdersUiState.Loading
                val flow = if (enabled) commerceRepository.observeSellerOrders(user.uid) else commerceRepository.observeUserOrders(user.uid)
                flow.catch { _uiState.value = OrdersUiState.Error(it.message ?: "Error") }
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

    fun fulfillOrder() {
        val currentOrder = (detailState.value as? OrderDetailState.Loaded)?.order ?: return
        if (_fulfillmentState.value is FulfillmentState.Processing) return

        val nextStatus = when (currentOrder.status) {
            OrderStatus.CONFIRMED -> OrderStatus.PROCESSING
            OrderStatus.PROCESSING -> OrderStatus.READY
            else -> return
        }

        viewModelScope.launch {
            _fulfillmentState.value = FulfillmentState.Processing
            commerceRepository.updateOrderStatus(currentOrder.id, nextStatus).fold(
                onSuccess = {
                    _fulfillmentState.value = FulfillmentState.Success
                    loadDetail() // Refresh detail to reflect new status
                },
                onFailure = {
                    _fulfillmentState.value = FulfillmentState.Error(it.message ?: "Failed to update order")
                }
            )
        }
    }

    fun resetFulfillmentState() {
        _fulfillmentState.value = FulfillmentState.Idle
    }

    fun getDeliveryListingId(): String? {
        val order = (detailState.value as? OrderDetailState.Loaded)?.order ?: return null
        if (!order.requiresDelivery) return null
        if (order.selectedDeliveryListingId.isBlank()) return null
        return order.selectedDeliveryListingId
    }
}
