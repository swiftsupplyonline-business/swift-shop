package com.swiftshop.feature.checkout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.core.datastore.PreferenceManager
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase

import com.swiftshop.domain.commerce.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.UUID
import javax.inject.Inject

// CART → DELIVERY → ADDRESS → PAYMENT → VERIFICATION → CONFIRMATION
// Delivery is now a first-class step: the buyer selects an available
// delivery listing before proceeding to address entry and payment.
enum class CheckoutStep { CART, DELIVERY, ADDRESS, PAYMENT, VERIFICATION, CONFIRMATION }

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
    private val discoverShops: DiscoverShopsUseCase,
    private val getOrder: GetOrderUseCase,
    private val preferenceManager: PreferenceManager
) : ViewModel() {



    private val _uiState = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Loading)
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private val _currentStep = MutableStateFlow(CheckoutStep.CART)
    val currentStep: StateFlow<CheckoutStep> = _currentStep.asStateFlow()

    // ── Delivery listing state ────────────────────────────────────────────────
    // Loaded when the buyer reaches the delivery selection step.
    private val _deliveryListingsState = MutableStateFlow<DeliveryListingsState>(DeliveryListingsState.Loading)
    val deliveryListingsState: StateFlow<DeliveryListingsState> = _deliveryListingsState.asStateFlow()

    // The delivery listing the buyer has selected. Must be non-null before
    // placeOrder() is called. The ID is sent to the backend; the fee is read
    // server-side from the listing — never trusted from the client.
    private val _selectedDeliveryListing = MutableStateFlow<DeliveryListing?>(null)
    val selectedDeliveryListing: StateFlow<DeliveryListing?> = _selectedDeliveryListing.asStateFlow()

    private val _nearbyShops = MutableStateFlow<List<Shop>>(emptyList())
    val shopMarkers: StateFlow<List<MapMarker>> = _nearbyShops.map { shops ->
        shops.map { shop ->
            MapMarker(
                id = shop.id,
                position = shop.location,
                title = shop.name,
                snippet = shop.category,
                entityType = SwiftEntity.SHOP
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var currentCartItems: List<CartItem> = emptyList()

    private val _deliveryAddress = MutableStateFlow(DeliveryAddress(city = "Maseru", country = "Lesotho"))
    var deliveryAddress by mutableStateOf(_deliveryAddress.value)
        private set

    var paymentMethod by mutableStateOf(PaymentMethod.MOPAY)
    var paymentProvider by mutableStateOf<String?>(null)
    var paymentPhone by mutableStateOf("")
    var buyerNotes by mutableStateOf("")

    private var currentUserId: String = ""

    private var feeCalculationJob: Job? = null

    init {
        val injectedOrderId: String? = savedStateHandle["orderId"]
        if (injectedOrderId != null) {
            rehydrateOrder(injectedOrderId)
        } else {
            observeProductCart()
            observeNearbyShops()
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
                    val isAwaitingPayment = (order.status == OrderStatus.PENDING || 
                                            order.status == OrderStatus.RESERVED ||
                                            order.status == OrderStatus.PAYMENT_PENDING) 
                                            && order.paymentUrl != null && order.mopaySessionId != null
                    
                    if (isAwaitingPayment) {
                        _uiState.value = CheckoutUiState.AwaitingPayment(
                            orderId = order.id,
                            paymentUrl = order.paymentUrl!!,
                            sessionId = order.mopaySessionId!!
                        )
                        _currentStep.value = CheckoutStep.VERIFICATION
                    } else if (order.status == OrderStatus.CONFIRMED || order.status == OrderStatus.PROCESSING) {
                        _uiState.value = CheckoutUiState.OrderPlaced(order.id)
                        _currentStep.value = CheckoutStep.CONFIRMATION
                    } else {
                        _uiState.value = CheckoutUiState.Error("Order is in state: ${order.status}")
                    }
                },
                onFailure = {
                    _uiState.value = CheckoutUiState.Error(it.message ?: "Failed to load order")
                }
            )
        }
    }

    private fun observeNearbyShops() {
        viewModelScope.launch {
            discoverShops().collect { shops ->
                _nearbyShops.value = shops.filter { it.location.isValid() }
            }
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

    fun setStep(step: CheckoutStep) {
        _currentStep.value = step
    }

    fun updateAddress(address: DeliveryAddress) {
        deliveryAddress = address
        _deliveryAddress.value = address
    }

    fun onLocationConfirmed(geoPoint: GeoPoint) {
        val current = _deliveryAddress.value
        updateAddress(current.copy(lat = geoPoint.lat, lng = geoPoint.lng))
        _confirmedLocationSnapshot.value = LocationSnapshot(
            lat = geoPoint.lat,
            lng = geoPoint.lng,
            addressSnapshot = buildList {
                if (current.label.isNotBlank()) add(current.label)
                if (current.streetHint.isNotBlank()) add(current.streetHint)
                add("${current.city}, ${current.country}")
            }.joinToString(" · "),
            instructions = current.streetHint
        )
    }

    val confirmedLocationSnapshot: StateFlow<LocationSnapshot?> get() = _confirmedLocationSnapshot
    private val _confirmedLocationSnapshot = MutableStateFlow<LocationSnapshot?>(null)

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

            val payload = when (cartState.summary.orderType) {
                OrderType.PRODUCT_PURCHASE -> OrderPayload.ProductPurchase(
                    quantity = orderItems.sumOf { it.quantity },
                    unitPrice = orderItems.firstOrNull()?.unitPrice ?: MoneyAmount.ZERO,
                    buyerNotes = buyerNotes
                )
                OrderType.FOOD_ORDER -> OrderPayload.FoodOrder(
                    items = orderItems.map { FoodOrderItem(it.listingId, it.title, it.quantity) },
                    preparationNotes = buyerNotes
                )
                OrderType.BULK_PURCHASE -> OrderPayload.BulkPurchase(
                    quantity = orderItems.sumOf { it.quantity }.toDouble()
                )
                else -> null
            }

            placeOrder(
                items = orderItems,
                address = deliveryAddress,
                deliveryListingId = delivery.id,   // backend reads fee from listing
                paymentMethod = paymentMethod,
                provider = paymentProvider,
                phoneNumber = paymentPhone,
                idempotencyKey = idempotencyKey,
                payload = payload
            ).fold(
                onSuccess = { initiation ->

                    clearCartUseCase(currentUserId)
                    if (initiation.paymentUrl != null && initiation.mopaySessionId != null) {
                        // Persist payment context locally before launching browser
                        viewModelScope.launch {
                            preferenceManager.setActivePaymentOrderId(initiation.orderId)
                        }

                        _uiState.value = CheckoutUiState.AwaitingPayment(
                            initiation.orderId,
                            initiation.paymentUrl!!,
                            initiation.mopaySessionId!!
                        )
                        _currentStep.value = CheckoutStep.VERIFICATION
                    } else {

                        _uiState.value = CheckoutUiState.OrderPlaced(initiation.orderId)
                        _currentStep.value = CheckoutStep.CONFIRMATION
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
            is CheckoutUiState.VerifyingPayment -> currentState.orderId
            else -> return
        }

        viewModelScope.launch {
            _uiState.value = CheckoutUiState.VerifyingPayment(orderId)
            verifyMopayPayment(sessionId).fold(
                onSuccess = {
                    preferenceManager.setActivePaymentOrderId(null)
                    _uiState.value = CheckoutUiState.OrderPlaced(orderId)
                    _currentStep.value = CheckoutStep.CONFIRMATION
                },
                onFailure = {
                    // Check if it's a terminal failure or just unknown
                    val message = it.message ?: "Verification failed"
                    if (message.contains("CANCELLED") || message.contains("FAILED")) {
                        preferenceManager.setActivePaymentOrderId(null)
                    }
                    _uiState.value = CheckoutUiState.Error(message)
                }
            )
        }
    }

    private var isVerifyingOnReturn = false

    fun verifyPaymentOnReturn() {
        if (isVerifyingOnReturn) return
        
        viewModelScope.launch {
            val activeOrderId = preferenceManager.activePaymentOrderId.first() ?: return@launch
            isVerifyingOnReturn = true
            
            // Re-fetch order to get the latest sessionId from server
            getOrder(activeOrderId).onSuccess { order ->
                val sid = order.mopaySessionId
                if (sid != null && (order.status == OrderStatus.PENDING || order.status == OrderStatus.RESERVED)) {
                    verifyPayment(sid)
                } else if (order.status == OrderStatus.CONFIRMED || order.status == OrderStatus.CANCELLED) {
                    preferenceManager.setActivePaymentOrderId(null)
                    rehydrateOrder(activeOrderId)
                }
            }

            
            isVerifyingOnReturn = false
        }
    }
}

