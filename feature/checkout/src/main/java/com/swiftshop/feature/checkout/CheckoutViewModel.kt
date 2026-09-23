package com.swiftshop.feature.checkout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.*
import com.swiftshop.domain.delivery.CancelDeliveryRequestUseCase
import com.swiftshop.domain.delivery.CreateDeliveryRequestUseCase
import com.swiftshop.domain.delivery.ObserveDeliveryRequestUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ UI State Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

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
    // AwaitingDeliveryAcceptance is now an intermediate state inside AWAITING_DELIVERY step.
    // The checkout step itself is the source of truth for navigation; this state carries the requestId
    // so the UI can show a countdown and a cancel button.
    data class AwaitingDeliveryAcceptance(val requestId: String) : CheckoutUiState
    data class Error(val message: String) : CheckoutUiState
}

// Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Accepted delivery snapshot (immutable once accepted) Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

data class AcceptedDelivery(
    val requestId: String,
    val providerName: String,   // listing title
    val fee: MoneyAmount
)

// Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ ViewModel Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeCart: ObserveCartUseCase,
    private val calculateOrderFees: CalculateOrderFeesUseCase,
    private val getDeliveryListings: GetDeliveryListingsUseCase,
    private val getShopUseCase: GetShopUseCase,
    private val placeOrder: PlaceOrderUseCase,
    private val verifyMopayPayment: VerifyMopayPaymentUseCase,
    private val createDeliveryRequest: CreateDeliveryRequestUseCase,
    private val observeDeliveryRequest: ObserveDeliveryRequestUseCase,
    private val cancelDeliveryRequest: CancelDeliveryRequestUseCase,
    private val removeFromCartUseCase: RemoveFromCartUseCase,
    private val clearCartUseCase: ClearCartUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<CheckoutUiState>(CheckoutUiState.Loading)
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private var currentUserId: String = ""
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

    // Authoritative snapshot set only after provider acceptance.
    // Cleared if the user cancels and restarts delivery selection.
    private val _acceptedDelivery = MutableStateFlow<AcceptedDelivery?>(null)
    val acceptedDelivery: StateFlow<AcceptedDelivery?> = _acceptedDelivery.asStateFlow()

    private var currentDeliveryRequest: DeliveryRequest? = null
    private var deliveryObservationJob: Job? = null
    private var feeCalculationJob: Job? = null

    private val _paymentIntent = MutableSharedFlow<OrderSummary>()
    val paymentIntent = _paymentIntent.asSharedFlow()

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

        // Delivery listings are now global (all providers, any shop).
        // Auto-select first available when listings arrive.
        viewModelScope.launch {
            getDeliveryListings("").collect { listings ->
                _deliveryListings.value = listings
                if (selectedDeliveryListingId == null && listings.isNotEmpty()) {
                    selectedDeliveryListingId = listings.first().id
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
        if (!value) {
            // Clear any accepted delivery if user switches to self-collection.
            _acceptedDelivery.value = null
            currentDeliveryRequest = null
            deliveryObservationJob?.cancel()
        }
    }

    fun updateSelectedDeliveryListing(listingId: String) {
        // If we already have an accepted delivery for a different listing, clear it
        // so the user must re-request.
        if (listingId != selectedDeliveryListingId) {
            _acceptedDelivery.value = null
            currentDeliveryRequest = null
            deliveryObservationJob?.cancel()
        }
        selectedDeliveryListingId = listingId
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
            } else if (currentState !is CheckoutUiState.AwaitingDeliveryAcceptance) {
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

            // If delivery is accepted, use the accepted fee directly rather than
            // re-querying the listing (which may have changed price).
            val accepted = _acceptedDelivery.value
            if (requiresDelivery && accepted != null) {
                // Build the summary using the authoritative accepted fee.
                calculateOrderFees(
                    orderItems,
                    requiresDelivery = false, // calculate subtotal + platform only
                    address = null,
                    selectedDeliveryListingId = null
                ).fold(
                    onSuccess = { baseSummary ->
                        val totalWithDelivery = baseSummary.subtotal + accepted.fee + baseSummary.platformFee
                        _uiState.value = CheckoutUiState.CartLoaded(
                            currentCartItems,
                            OrderSummary(
                                subtotal = baseSummary.subtotal,
                                deliveryFee = accepted.fee,
                                platformFee = baseSummary.platformFee,
                                total = totalWithDelivery
                            ),
                            isRecalculating = false
                        )
                    },
                    onFailure = {
                        _uiState.value = CheckoutUiState.Error(it.message ?: "Failed to calculate fees")
                    }
                )
                return@launch
            }

            calculateOrderFees(
                orderItems,
                false,
                null,
                null
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
        viewModelScope.launch { removeFromCartUseCase(currentUserId, listingId) }
    }

    fun onClearCartClicked() {
        viewModelScope.launch { clearCartUseCase(currentUserId) }
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Delivery request Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    /**
     * Called when the customer confirms their provider selection and taps
     * "Request Delivery" (at the end of the CART step).
     * Fetches the merchant shop location for the pickup point, then submits
     * the request to the Cloud Function.
     */
    fun requestDelivery() {
        val listingId = selectedDeliveryListingId ?: return

        viewModelScope.launch {
            _uiState.value = CheckoutUiState.Loading

            val dropoff = GeoPoint(deliveryAddress.lat, deliveryAddress.lng)

            createDeliveryRequest(listingId, dropoff).fold(
                onSuccess = { requestId ->
                    _uiState.value = CheckoutUiState.AwaitingDeliveryAcceptance(requestId)
                    observeDeliveryRequestStatus(requestId)
                },
                onFailure = {
                    _uiState.value = CheckoutUiState.Error(it.message ?: "Delivery request failed")
                }
            )
        }
    }

    private fun observeDeliveryRequestStatus(requestId: String) {
        deliveryObservationJob?.cancel()
        deliveryObservationJob = viewModelScope.launch {
            observeDeliveryRequest(requestId).collect { request ->
                currentDeliveryRequest = request
                when (request.status) {
                    DeliveryRequestStatus.ACCEPTED -> {
                        // Snapshot the accepted delivery Ã¢â‚¬â€ fee is now authoritative.
                        val providerListing = _deliveryListings.value
                            .firstOrNull { it.id == request.listingId }
                        _acceptedDelivery.value = AcceptedDelivery(
                            requestId = requestId,
                            providerName = providerListing?.title ?: "Delivery provider",
                            fee = request.deliveryFee
                        )
                        // Recalculate order summary using the locked-in fee.
                        updateFees()
                    }
                    DeliveryRequestStatus.DECLINED -> {
                        _uiState.value = CheckoutUiState.Error("Provider declined the request. Choose another provider.")
                    }
                    DeliveryRequestStatus.EXPIRED -> {
                        _uiState.value = CheckoutUiState.Error("No response within 2 minutes. Choose another provider.")
                    }
                    DeliveryRequestStatus.CANCELLED -> {
                        // User-initiated cancel Ã¢â‚¬â€ return to cart loaded state.
                        updateFees()
                    }
                    else -> { /* PENDING Ã¢â‚¬â€ waiting */ }
                }
            }
        }
    }

    /**
     * Called when the customer taps "Cancel" on the delivery-waiting screen.
     * Cancels the pending request server-side and clears state.
     */
    fun cancelPendingDeliveryRequest() {
        val requestId = currentDeliveryRequest?.id
            ?: ((_uiState.value as? CheckoutUiState.AwaitingDeliveryAcceptance)?.requestId)
            ?: return

        deliveryObservationJob?.cancel()
        viewModelScope.launch {
            cancelDeliveryRequest(requestId)
            // Ignore result Ã¢â‚¬â€ clear local state regardless.
            currentDeliveryRequest = null
            _acceptedDelivery.value = null
            updateFees()
        }
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Order placement Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    /**
     * Called after delivery is accepted (or skipped) and the customer
     * taps "Place Order" from the PAYMENT step.
     * The backend independently validates that deliveryRequestId.status == ACCEPTED.
     */
    fun placeOrder() {
        val cartState = _uiState.value as? CheckoutUiState.CartLoaded ?: return

        // Guard: delivery must be accepted before payment can be initiated.
        if (requiresDelivery) {
            val accepted = _acceptedDelivery.value
            if (accepted == null) {
                _uiState.value = CheckoutUiState.Error("Delivery must be accepted before payment.")
                return
            }
        }

        if (paymentMethod == PaymentMethod.SWIFT_WALLET) {
            viewModelScope.launch {
                _paymentIntent.emit(cartState.summary)
            }
            return
        }

        executePlaceOrder()
    }

    fun executePlaceOrder() {
        val cartState = _uiState.value as? CheckoutUiState.CartLoaded ?: return
        if (_uiState.value is CheckoutUiState.PlacingOrder) return

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
                deliveryRequestId = _acceptedDelivery.value?.requestId
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

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Payment verification Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

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
                onSuccess = { _uiState.value = CheckoutUiState.OrderPlaced(orderId) },
                onFailure = { _uiState.value = CheckoutUiState.Error(it.message ?: "Verification failed") }
            )
        }
    }
}
