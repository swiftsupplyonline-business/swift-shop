package com.swiftshop.feature.checkout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.UUID
import javax.inject.Inject

sealed interface CheckoutUiState {
    data object Loading : CheckoutUiState
    data class CartLoaded(val items: List<CartItem>, val summary: OrderSummary) : CheckoutUiState
    data object PlacingOrder : CheckoutUiState
    data class OrderPlaced(val orderId: String) : CheckoutUiState
    data class AwaitingPayment(val orderId: String, val paymentUrl: String, val sessionId: String) : CheckoutUiState
    data class VerifyingPayment(val orderId: String) : CheckoutUiState
    data class Error(val message: String) : CheckoutUiState
}

sealed interface DeliveryListingsState {
    data object Loading : DeliveryListingsState
    data class Loaded(val listings: List<DeliveryListing>) : DeliveryListingsState
    data class Error(val message: String) : DeliveryListingsState
}

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeCart: ObserveCartUseCase,
    private val calculateOrderFees: CalculateOrderFeesUseCase,
    private val placeOrder: PlaceOrderUseCase,
    private val verifyMopayPayment: VerifyMopayPaymentUseCase,
    private val removeFromCartUseCase: RemoveFromCartUseCase,
    private val clearCartUseCase: ClearCartUseCase,
    private val getDeliveryListings: GetDeliveryListingsUseCase,
    private val getOrder: GetOrderUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Loading)
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    // ── Delivery listing state ────────────────────────────────────────────────
    // Loaded when the buyer reaches the delivery selection step.
    private val _deliveryListingsState = MutableStateFlow<DeliveryListingsState>(DeliveryListingsState.Loading)
    val deliveryListingsState: StateFlow<DeliveryListingsState> = _deliveryListingsState.asStateFlow()

    // The delivery listing the buyer has selected. Must be non-null before
    // placeOrder() is called. The ID is sent to the backend; the fee is read
    // server-side from the listing — never trusted from the client.
    private val _selectedDeliveryListing = MutableStateFlow<DeliveryListing?>(null)
    val selectedDeliveryListing: StateFlow<DeliveryListing?> = _selectedDeliveryListing.asStateFlow()

    private var currentCartItems: List<CartItem> = emptyList()

    private val _deliveryAddress = MutableStateFlow(DeliveryAddress(city = "Maseru", country = "Lesotho"))
    var deliveryAddress by mutableStateOf(_deliveryAddress.value)
        private set

    var paymentMethod by mutableStateOf(PaymentMethod.MOPAY)
    var paymentProvider by mutableStateOf<String?>(null)
    var paymentPhone by mutableStateOf("")

    private var currentUserId: String = ""
    private var feeCalculationJob: Job? = null

    init {
        val injectedOrderId: String? = savedStateHandle["orderId"]
        if (injectedOrderId != null) {
            rehydrateOrder(injectedOrderId)
        } else {
            observeProductCart()
        }

        // Recalculate fees whenever the selected delivery listing changes.
        viewModelScope.launch {
            _selectedDeliveryListing
                .filterNotNull()
                .distinctUntilChanged()
                .collect { updateFees() }
        }
    }

    private fun observeProductCart() {
        viewModelScope.launch {
            observeCurrentUser().filterNotNull().collect { user ->
                currentUserId = user.uid
                observeCart(user.uid).collect { items ->
                    currentCartItems = items
                    if (_selectedDeliveryListing.value != null) {
                        updateFees()
                    } else {
                        if (items.isEmpty()) {
                            _uiState.value = CheckoutUiState.Error("Cart is empty")
                        } else {
                            _uiState.value = CheckoutUiState.CartLoaded(
                                items = items,
                                summary = OrderSummary()
                            )
                        }
                    }
                }
            }
        }
    }

    private fun rehydrateOrder(orderId: String) {
        viewModelScope.launch {
            _uiState.value = CheckoutUiState.Loading
            getOrder(orderId).fold(
                onSuccess = { order ->
                    if (order.status == OrderStatus.PENDING && order.paymentUrl != null && order.mopaySessionId != null) {
                        _uiState.value = CheckoutUiState.AwaitingPayment(
                            orderId = order.id,
                            paymentUrl = order.paymentUrl!!,
                            sessionId = order.mopaySessionId!!
                        )
                    } else if (order.status != OrderStatus.PENDING) {
                        _uiState.value = CheckoutUiState.OrderPlaced(order.id)
                    } else {
                        _uiState.value = CheckoutUiState.Error("Order not ready for payment")
                    }
                },
                onFailure = {
                    _uiState.value = CheckoutUiState.Error(it.message ?: "Failed to load order")
                }
            )
        }
    }
    fun loadDeliveryListings() {
        if (_deliveryListingsState.value is DeliveryListingsState.Loaded) return
        viewModelScope.launch {
            _deliveryListingsState.value = DeliveryListingsState.Loading
            getDeliveryListings().fold(
                onSuccess = { listings ->
                    _deliveryListingsState.value = DeliveryListingsState.Loaded(listings)
                },
                onFailure = {
                    _deliveryListingsState.value = DeliveryListingsState.Error(
                        it.message ?: "Failed to load delivery options"
                    )
                }
            )
        }
    }

    fun selectDeliveryListing(listing: DeliveryListing) {
        _selectedDeliveryListing.value = listing
    }

    fun updateAddress(address: DeliveryAddress) {
        deliveryAddress = address
        _deliveryAddress.value = address
    }

    private fun updateFees() {
        val delivery = _selectedDeliveryListing.value ?: return
        if (currentCartItems.isEmpty()) {
            _uiState.value = CheckoutUiState.Error("Cart is empty")
            return
        }

        feeCalculationJob?.cancel()
        feeCalculationJob = viewModelScope.launch {
            _uiState.value = CheckoutUiState.Loading
            val orderItems = currentCartItems.map {
                OrderItem(
                    listingId = it.listingId,
                    title = it.title,
                    quantity = it.quantity,
                    unitPrice = it.unitPrice,
                    selectedOptions = it.selectedOptions
                )
            }
            calculateOrderFees(orderItems, deliveryAddress, delivery.id).fold(
                onSuccess = { summary ->
                    _uiState.value = CheckoutUiState.CartLoaded(currentCartItems, summary)
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

    fun placeOrder() {
        val cartState = _uiState.value as? CheckoutUiState.CartLoaded ?: return
        val delivery = _selectedDeliveryListing.value
        if (delivery == null) {
            _uiState.value = CheckoutUiState.Error("Please select a delivery option first")
            return
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
                address = deliveryAddress,
                deliveryListingId = delivery.id,   // backend reads fee from listing
                paymentMethod = paymentMethod,
                provider = paymentProvider,
                phoneNumber = paymentPhone,
                idempotencyKey = idempotencyKey
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
