package com.swiftshop.feature.checkout

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.*
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
    data class Error(val message: String) : CheckoutUiState
}

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeCart: ObserveCartUseCase,
    private val calculatePurchaseTotal: CalculatePurchaseTotalUseCase,
    private val createPurchaseOrder: CreatePurchaseOrderUseCase,
    private val verifyMopayPayment: VerifyMopayPaymentUseCase,
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
    
    private val _deliveryAddress = MutableStateFlow(DeliveryAddress(city = "Maseru", country = "Lesotho"))
    var deliveryAddress by mutableStateOf(_deliveryAddress.value)
        private set

    var paymentMethod by mutableStateOf(PaymentMethod.MOPAY)
    var paymentProvider by mutableStateOf<String?>(null)
    var paymentPhone by mutableStateOf("")

    private var currentUserId: String = ""
    private var feeCalculationJob: Job? = null

    init {
        viewModelScope.launch {
            observeCurrentUser().filterNotNull().collect { user ->
                currentUserId = user.uid
                observeCart(user.uid).collect { items ->
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

    }

    fun updateAddress(address: DeliveryAddress) {
        deliveryAddress = address
        _deliveryAddress.value = address
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
            calculatePurchaseTotal(orderItems).fold(
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

            createPurchaseOrder(
                items = orderItems,
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
                            initiation.paymentUrl,
                            initiation.mopaySessionId
                        )
                    } else {
                        _uiState.value = CheckoutUiState.OrderPlaced(initiation.orderId)
                    }
                },
                onFailure = { _uiState.value = CheckoutUiState.Error(it.message ?: "Purchase failed") }
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

