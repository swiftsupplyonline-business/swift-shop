package com.swiftshop.feature.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.Listing
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CartItem
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.commerce.GetListingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ListingDetailNavigation {
    data object GoToCheckout : ListingDetailNavigation
}

sealed interface ActionState {
    data object Idle : ActionState
    data object Loading : ActionState
    data class Success(val message: String) : ActionState
    data class Error(val message: String) : ActionState
}

sealed interface ListingDetailState {
    data object Loading : ListingDetailState
    data class Loaded(val listing: Listing) : ListingDetailState
    data class Error(val message: String) : ListingDetailState
}

@HiltViewModel
class ListingDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getListing: GetListingUseCase,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val addToCartUseCase: com.swiftshop.domain.commerce.AddToCartUseCase
) : ViewModel() {

    private val listingId: String = checkNotNull(savedStateHandle["listingId"])

    private val _uiState = MutableStateFlow<ListingDetailState>(ListingDetailState.Loading)
    val uiState: StateFlow<ListingDetailState> = _uiState.asStateFlow()

    private val _quantity = MutableStateFlow(1)
    val quantity = _quantity.asStateFlow()

    private val _isAddingToCart = MutableStateFlow(false)
    val isAddingToCart = _isAddingToCart.asStateFlow()

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState = _actionState.asStateFlow()

    private val _navigationEvents = MutableSharedFlow<ListingDetailNavigation>()
    val navigationEvents: SharedFlow<ListingDetailNavigation> = _navigationEvents.asSharedFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = ListingDetailState.Loading
            getListing(listingId).fold(
                onSuccess = { _uiState.value = ListingDetailState.Loaded(it) },
                onFailure = { _uiState.value = ListingDetailState.Error(it.message ?: "Failed to load") }
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
                _navigationEvents.emit(ListingDetailNavigation.GoToCheckout)
            }
        }
    }
}
