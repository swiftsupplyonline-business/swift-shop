package com.swiftshop.feature.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ListingDetailNavigation {
    data class GoToCheckout(val orderId: String? = null) : ListingDetailNavigation
}

sealed interface ActionState {
    data object Idle : ActionState
    data object Loading : ActionState
    data class Success(val message: String) : ActionState
    data class Error(val message: String) : ActionState
}

sealed interface ListingDetailState {
    data object Loading : ListingDetailState
    data class Loaded(val listing: Listing, val shop: Shop? = null) : ListingDetailState
    data class Error(val message: String) : ListingDetailState
}

@HiltViewModel
class ListingDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getListing: GetListingUseCase,
    private val getShop: GetShopUseCase,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val addToCartUseCase: com.swiftshop.domain.commerce.AddToCartUseCase,
    private val observeAvailableSlots: ObserveAvailableSlotsUseCase,
    private val initiateBooking: InitiateBookingUseCase,
    private val toggleBookmark: com.swiftshop.domain.feed.ToggleBookmarkUseCase,
    private val observeBookmarkedIds: com.swiftshop.domain.feed.ObserveBookmarkedIdsUseCase
) : ViewModel() {

    private val listingId: String = checkNotNull(savedStateHandle["listingId"])

    private val _uiState = MutableStateFlow<ListingDetailState>(ListingDetailState.Loading)
    val uiState: StateFlow<ListingDetailState> = _uiState.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked = _isBookmarked.asStateFlow()

    private val _quantity = MutableStateFlow(1)
    val quantity = _quantity.asStateFlow()

    private val _availableSlots = MutableStateFlow<List<AvailabilitySlot>>(emptyList())
    val availableSlots = _availableSlots.asStateFlow()

    private val _selectedSlot = MutableStateFlow<AvailabilitySlot?>(null)
    val selectedSlot = _selectedSlot.asStateFlow()

    private val _isAddingToCart = MutableStateFlow(false)
    val isAddingToCart = _isAddingToCart.asStateFlow()

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState = _actionState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<ListingDetailNavigation>()
    val navigationEvents: SharedFlow<ListingDetailNavigation> = _navigationEvents.asSharedFlow()

    private val _bookingOperationId = MutableStateFlow<String?>(null)

    init {
        load()
        observeBookmarkStatus()
    }

    private fun observeBookmarkStatus() {
        viewModelScope.launch {
            observeCurrentUser().collectLatest { user ->
                if (user != null) {
                    observeBookmarkedIds(user.uid).collect { ids ->
                        _isBookmarked.value = listingId in ids
                    }
                }
            }
        }
    }

    fun toggleBookmark() {
        viewModelScope.launch {
            val user = observeCurrentUser().first() ?: return@launch
            toggleBookmark(user.uid, listingId, "LISTING")
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = ListingDetailState.Loading
            getListing(listingId).fold(
                onSuccess = { listing ->
                    val shop = getShop(listing.shopId).getOrNull()
                    _uiState.value = ListingDetailState.Loaded(listing, shop)
                    if (listing.listingType == ListingType.SET_APPOINTMENT) {
                        observeSlots(listing.shopId)
                    }
                },
                onFailure = { _uiState.value = ListingDetailState.Error(it.message ?: "Failed to load") }
            )
        }
    }

    private fun observeSlots(shopId: String) {
        viewModelScope.launch {
            observeAvailableSlots(shopId).collect { slots ->
                _availableSlots.value = slots
                // If selected slot is no longer in available list, clear it
                if (_selectedSlot.value != null && slots.none { it.id == _selectedSlot.value?.id }) {
                    _selectedSlot.value = null
                }
            }
        }
    }

    fun onSlotSelected(slot: AvailabilitySlot?) {
        _selectedSlot.value = slot
    }

    fun onConfirmBooking(phoneNumber: String) {
        val state = _uiState.value as? ListingDetailState.Loaded ?: return
        val slot = _selectedSlot.value ?: return
        
        // Preserve stable operation ID across retries
        val opId = _bookingOperationId.value ?: java.util.UUID.randomUUID().toString().also { 
            _bookingOperationId.value = it 
        }

        viewModelScope.launch {
            _actionState.value = ActionState.Loading
            initiateBooking(
                listingId = state.listing.id,
                slotId = slot.id,
                paymentMethod = PaymentMethod.MOPAY,
                provider = null,
                phoneNumber = phoneNumber,
                idempotencyKey = opId
            ).fold(
                onSuccess = {
                    _bookingOperationId.value = null // Clear on success
                    _navigationEvents.emit(ListingDetailNavigation.GoToCheckout(it.orderId))
                },
                onFailure = {
                    _actionState.value = ActionState.Error(it.message ?: "Booking failed")
                }
            )
        }
    }

    fun updateQuantity(q: Int) {
        _quantity.value = q.coerceAtLeast(1)
    }

    fun addToCart(onComplete: (() -> Unit)? = null) {
        val state = _uiState.value as? ListingDetailState.Loaded ?: return
        viewModelScope.launch {
            _isAddingToCart.value = true
            _actionState.value = ActionState.Loading
            val user = observeCurrentUser().first()
            val uid = user?.uid
            
            if (uid != null) { 
                val item = CartItem(
                    listingId = state.listing.id,
                    shopId = state.listing.shopId,
                    title = state.listing.title,
                    quantity = _quantity.value,
                    unitPrice = state.listing.price
                )
                addToCartUseCase(uid, item).fold(
                    onSuccess = {
                        _actionState.value = ActionState.Success("Added to cart")
                        onComplete?.invoke()
                    },
                    onFailure = {
                        _actionState.value = ActionState.Error(it.message ?: "Failed to add to cart")
                    }
                )
            } else {
                _actionState.value = ActionState.Error("User not signed in")
            }
            _isAddingToCart.value = false
        }
    }

    fun clearActionState() {
        _actionState.value = ActionState.Idle
    }

    fun buyNow() {
        addToCart {
            viewModelScope.launch {
                _navigationEvents.emit(ListingDetailNavigation.GoToCheckout())
            }
        }
    }
}
