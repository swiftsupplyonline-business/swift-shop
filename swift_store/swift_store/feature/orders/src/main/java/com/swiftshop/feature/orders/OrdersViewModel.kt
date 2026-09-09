package com.swiftshop.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.Order
import com.swiftshop.core.model.OrderRole
import com.swiftshop.core.model.OrderStatus
import com.swiftshop.core.model.ListingType

import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.profile.ObserveProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface OrdersUiState {
    data object Loading : OrdersUiState
    data object Empty : OrdersUiState
    data class Loaded(
        val orders: List<Order>, 
        val currentRole: OrderRole
    ) : OrdersUiState
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
    private val commerceRepository: CommerceRepository,
    private val observeProfile: ObserveProfileUseCase
) : ViewModel() {



    private val orderId: String? = savedStateHandle["orderId"]

    private val _currentRole = MutableStateFlow(OrderRole.REQUESTER)
    val currentRole: StateFlow<OrderRole> = _currentRole.asStateFlow()

    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _uiState = MutableStateFlow<OrdersUiState>(OrdersUiState.Loading)
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private val _detailState = MutableStateFlow<OrderDetailState>(OrderDetailState.Loading)
    val detailState: StateFlow<OrderDetailState> = _detailState.asStateFlow()

    val availableRoles = observeCurrentUser().filterNotNull().flatMapLatest { user ->
        combine(
            observeProfile(user.uid),
            commerceRepository.observeUserListings(user.uid),
            // Observe if user is a RECIPIENT on any order
            commerceRepository.observeOrdersByRole(user.uid, OrderRole.RECIPIENT).map { it.isNotEmpty() }
        ) { profile, listings, hasReceivedOrders ->
            val roles = mutableListOf(OrderRole.REQUESTER)
            
            // SELLER: owns at least one shop
            if (profile.shopCount > 0) roles.add(OrderRole.SELLER)
            
            // SERVICE_PROVIDER: has at least one bookable service listing
            if (listings.any { it.listingType in listOf(
                    com.swiftshop.core.model.ListingType.BOOKABLE_SERVICE,
                    com.swiftshop.core.model.ListingType.SET_APPOINTMENT,
                    com.swiftshop.core.model.ListingType.SERVICE
                ) }) {
                roles.add(OrderRole.SERVICE_PROVIDER)
            }
            
            // DELIVERY_PROVIDER: has at least one delivery service listing
            if (listings.any { it.listingType in listOf(
                    com.swiftshop.core.model.ListingType.DELIVERY_SERVICE,
                    com.swiftshop.core.model.ListingType.DELIVER
                ) }) {
                roles.add(OrderRole.DELIVERY_PROVIDER)
            }

            // RECIPIENT: has at least one order sent to them
            if (hasReceivedOrders) {
                roles.add(OrderRole.RECIPIENT)
            }

            // ADMIN: authoritative check
            if (user.isAdmin) {
                roles.add(OrderRole.ADMIN)
            }

            roles.distinct()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(OrderRole.REQUESTER))





    init {
        // Automatically reload when role changes or manual refresh triggered
        combine(_currentRole, refreshTrigger.onStart { emit(Unit) }) { role, _ -> role }
            .onEach { _uiState.value = OrdersUiState.Loading }
            .flatMapLatest { role ->
                observeCurrentUser().filterNotNull().flatMapLatest { user ->
                    commerceRepository.observeOrdersByRole(user.uid, role)
                }
            }
            .catch { e -> _uiState.value = OrdersUiState.Error(e.message ?: "Unknown error") }
            .onEach { orders ->
                _uiState.value = if (orders.isEmpty()) OrdersUiState.Empty
                else OrdersUiState.Loaded(orders.sortedByDescending { it.createdAt }, _currentRole.value)
            }
            .launchIn(viewModelScope)

        if (orderId != null) loadDetail()
    }


    fun setRole(role: OrderRole) {
        _currentRole.value = role
    }

    fun load() {
        refreshTrigger.tryEmit(Unit)
    }

    fun loadDetail() {
        val id = orderId ?: return
        viewModelScope.launch {
            _detailState.value = OrderDetailState.Loading
            commerceRepository.getOrder(id).fold(
                onSuccess = { _detailState.value = OrderDetailState.Loaded(it) },
                onFailure = { _detailState.value = OrderDetailState.Error(it.message ?: "Error loading order") }
            )
        }
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch {
            commerceRepository.cancelOrder(orderId, "User requested cancellation")
        }
    }
}
