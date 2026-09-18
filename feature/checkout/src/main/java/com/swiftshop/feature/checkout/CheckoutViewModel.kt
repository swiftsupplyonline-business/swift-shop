package com.swiftshop.feature.checkout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.*
import com.swiftshop.domain.delivery.CreateDeliveryRequestUseCase
import com.swiftshop.domain.delivery.ObserveDeliveryRequestUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.UUID
import javax.inject.Inject

sealed interface CheckoutUiState {
    data object Loading : CheckoutUiState
    data class CartLoaded(
        val items: List<CartItem>,
        val summary: OrderSummary,
        val isRecalculating: Boolean = false
    ) : CheckoutUiState
    data object PlacingOrder : CheckoutUiState
    data class OrderPlaced(val orderId: String) : CheckoutUiState
    data class AwaitingPayment(val orderId: String, val paymentUrl: String, val sessionId: String) : CheckoutUiState
    data class VerifyingPayment(val orderId: String) : CheckoutUiState
    data class AwaitingDeliveryAcceptance(val requestId: String) : CheckoutUiState
    data class Error(val message: String) : CheckoutUiState
}

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeCart: ObserveCartUseCase,
    private val calculateOrderFees: CalculateOrderFeesUseCase,
    private val getDeliveryListings: GetDeliveryListingsUseCase,
    private val placeOrder: PlaceOrderUseCase,
    private val verifyMopayPayment: VerifyMopayPaymentUseCase,
    private val createDeliveryRequest: CreateDeliveryRequestUseCase,
    private val observeDeliveryRequest: ObserveDeliveryRequestUseCase,
    private val removeFromCartUseCase: RemoveFromCartUseCase,
    private val clearCartUseCase: ClearCartUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Loading)
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private var currentCartItems: List<CartItem> = emptyList()
    private val _hasCartItems = MutableStateFlow(false)
    val hasCartItems: StateFlow<Boolean> = _hasCartItems.asStateFlow()
    private val _cartItems = MutableStateFlow<List<CartItem>>(emptyList())
    val cartItems: StateFlow<List<CartItem>> = _cartItems.asStateFlow()
    private val _cartShopId = MutableStateFlow("")
    
    private val _deliveryAddress = MutableStateFlow(DeliveryAddress(city = "Maseru", country = "Lesotho"))
    var deliveryAddress by mutableStateOf(_deliveryAddress.value)
        private set

    private val _requiresDelivery = MutableStateFlow(false)
    var requiresDelivery by mutableStateOf(_requiresDelivery.value)
        private set

    private val _deliveryListings = MutableStateFlow<List<Listing>>(emptyList())
    val deliveryListings = _deliveryListings.asStateFlow()

    var selectedDeliveryListingId by mutableStateOf<String?>(null)
        private set

    var paymentMethod by mutableStateOf(PaymentMethod.MOPAY)
    var paymentProvider by mutableStateOf<String?>(null)
    var paymentPhone by mutableStateOf("")

    private var currentDeliveryRequest: DeliveryRequest? = null
    private var deliveryObservationJob: Job? = null

    private var currentUserId: String = ""
    private var feeCalculationJob: Job? = null

    init {
        viewModelScope.launch {
            observeCurrentUser().filterNotNull().collect { user ->
                currentUserId = user.uid
                observeCart(user.uid).collect { items ->
                    _cartShopId.value = items.firstOrNull()?.shopId ?: ""
                    currentCartItems = items
                    _hasCartItems.value = items.isNotEmpty()
                    _cartItems.value = items
                    updateFees()
                }
            }
        }

        // Debounced fee calculation on address change
        viewModelScope.launch {
            _deliveryAddress
                .debounce(500)
                .distinctUntilChanged()
                .collect { updateFees() }
        }

        viewModelScope.launch {
            _requiresDelivery
                .debounce(500)
                .distinctUntilChanged()
                .collect { updateFees() }
        }

        viewModelScope.launch {
            _cartShopId
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .flatMapLatest { shopId -> getDeliveryListings(shopId) }
                .collect { listings ->
                _deliveryListings.value = listings
                if (selectedDeliveryListingId == null && listings.isNotEmpty()) {
                    selectedDeliveryListingId = listings.first().id
                    updateFees()
                }
            }
        }
    }

    fun updateAddress(address: DeliveryAddress) {
        deliveryAddress = address
        _deliveryAddress.value = address
    }

    fun updateRequiresDelivery(value: Boolean) {
        requiresDelivery = value
        _requiresDelivery.value = value
    }

    fun updateSelectedDeliveryListing(listingId: String) {
        selectedDeliveryListingId = listingId
        updateFees()
    }

    private fun updateFees() {
        if (currentCartItems.isEmpty()) {
            _uiState.value = CheckoutUiState.Error("Cart is empty")
            return
        }

        feeCalculationJob?.cancel()
        feeCalculationJob = viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState is CheckoutUiState.CartLoaded) {
                _uiState.value = currentState.copy(isRecalculating = true)
            } else {
                _uiState.value = CheckoutUiState.Loading
            }

            val orderItems = currentCartItems.map {
                OrderItem(
                    listingId = it.listingId,
                    title = it.title,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    selectedOptions = it.selectedOptions
                )
            }
            calculateOrderFees(
                orderItems,
                requiresDelivery,
                if (requiresDelivery) deliveryAddress else null,
                if (requiresDelivery) selectedDeliveryListingId else null
            ).fold(
                onSuccess = { summary ->
                    _uiState.value = CheckoutUiState.CartLoaded(currentCartItems, summary, isRecalculating = false)
                },
                onFailure = { 
                    _uiState.value = CheckoutUiState.Error(it.message ?: "Failed to calculate fees")
                }
            )
        }
    }

    fun removeItem(listingId: String) {
        viewModelScope.launch {
            removeFromCartUseCase(currentUserId, listingId)
        }
    }


    fun onClearCartClicked() {
        viewModelScope.launch {
            clearCartUseCase(currentUserId)
        }
    }
    fun placeOrder() {
        val cartState = _uiState.value as? CheckoutUiState.CartLoaded ?: return

        if (requiresDelivery) {
            val requestId = currentDeliveryRequest?.id
            val status = currentDeliveryRequest?.status

            if (requestId == null || status != DeliveryRequestStatus.ACCEPTED) {
                // Trigger delivery request if not already started
                requestDelivery()
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = CheckoutUiState.PlacingOrder
            val idempotencyKey = UUID.randomUUID().toString()
            val orderItems = cartState.items.map {
                OrderItem(
                    listingId = it.listingId,
                    title = it.title,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    selectedOptions = it.selectedOptions
                )
            }
            
            placeOrder(
                items = orderItems,
                requiresDelivery = requiresDelivery,
                address = if (requiresDelivery) deliveryAddress else null,
                paymentMethod = paymentMethod,
                provider = paymentProvider,
                phoneNumber = paymentPhone,
                idempotencyKey = idempotencyKey,
                selectedDeliveryListingId = if (requiresDelivery) selectedDeliveryListingId else null,
                deliveryRequestId = currentDeliveryRequest?.id
            ).fold(
                onSuccess = { initiation ->
                    clearCartUseCase(currentUserId)
                    if (initiation.paymentUrl != null && initiation.mopaySessionId != null) {
                        _uiState.value = CheckoutUiState.AwaitingPayment(
                            initiation.orderId,
                            initiation.paymentUrl!!,
                            initiation.mopaySessionId!!
                        )
                    } else {
                        _uiState.value = CheckoutUiState.OrderPlaced(initiation.orderId)
                    }
                },
                onFailure = { _uiState.value = CheckoutUiState.Error(it.message ?: "Order failed") }
            )
        }
    }

    private fun requestDelivery() {
        val listingId = selectedDeliveryListingId ?: return
        val pickup = GeoPoint() // In a real app, this would be the shop location from the listing's shop
        val dropoff = GeoPoint(deliveryAddress.lat, deliveryAddress.lng)

        viewModelScope.launch {
            _uiState.value = CheckoutUiState.Loading
            createDeliveryRequest(listingId, pickup, dropoff).fold(
                onSuccess = { requestId ->
                    _uiState.value = CheckoutUiState.AwaitingDeliveryAcceptance(requestId)
                    observeDeliveryRequestStatus(requestId)
                },
                onFailure = { _uiState.value = CheckoutUiState.Error(it.message ?: "Delivery request failed") }
            )
        }
    }

    private fun observeDeliveryRequestStatus(requestId: String) {
        deliveryObservationJob?.cancel()
        deliveryObservationJob = viewModelScope.launch {
            observeDeliveryRequest(requestId).collect { request ->
                currentDeliveryRequest = request
                if (request.status == DeliveryRequestStatus.ACCEPTED) {
                    // Transition back to cart loaded so user can proceed to Place Order
                    updateFees()
                } else if (request.status == DeliveryRequestStatus.DECLINED || request.status == DeliveryRequestStatus.EXPIRED) {
                    _uiState.value = CheckoutUiState.Error("Delivery provider ${request.status.name.lowercase()}")
                }
            }
        }
    }

    fun verifyPayment(sessionId: String) {
        val currentState = _uiState.value
        val orderId = when (currentState) {
            is CheckoutUiState.AwaitingPayment -> currentState.orderId
            is CheckoutUiState.OrderPlaced -> currentState.orderId
            else -> return
        }

        viewModelScope.launch {
            _uiState.value = CheckoutUiState.VerifyingPayment(orderId)
            verifyMopayPayment(sessionId).fold(
                onSuccess = {
                    _uiState.value = CheckoutUiState.OrderPlaced(orderId)
                },
                onFailure = {
                    _uiState.value = CheckoutUiState.Error(it.message ?: "Verification failed")
                }
            )
        }
    }
}

