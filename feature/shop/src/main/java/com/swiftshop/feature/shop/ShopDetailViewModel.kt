package com.swiftshop.feature.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.ListingType
import com.swiftshop.core.model.Shop
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.GetShopListingsUseCase
import com.swiftshop.domain.commerce.GetShopUseCase
import com.swiftshop.domain.profile.FollowUserUseCase
import com.swiftshop.domain.profile.IsFollowingUseCase
import com.swiftshop.domain.profile.UnfollowUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ShopDetailUiState {
    data object Loading : ShopDetailUiState
    data class Success(val shop: Shop, val listings: List<Listing>) : ShopDetailUiState
    data class Error(val message: String) : ShopDetailUiState
}

@HiltViewModel
class ShopDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getShop: GetShopUseCase,
    private val getShopListings: GetShopListingsUseCase,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val followUser: FollowUserUseCase,
    private val unfollowUser: UnfollowUserUseCase,
    private val isFollowingUseCase: IsFollowingUseCase
) : ViewModel() {

    private val shopId: String = checkNotNull(savedStateHandle["shopId"])

    private val _uiState = MutableStateFlow<ShopDetailUiState>(ShopDetailUiState.Loading)
    val uiState: StateFlow<ShopDetailUiState> = _uiState.asStateFlow()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing = _isFollowing.asStateFlow()

    private val _isFollowLoading = MutableStateFlow(false)
    val isFollowLoading = _isFollowLoading.asStateFlow()

    private val _isOwner = MutableStateFlow(false)
    val isOwner = _isOwner.asStateFlow()

    // Active listing type filter; null = show all
    private val _typeFilter = MutableStateFlow<ListingType?>(null)
    val typeFilter = _typeFilter.asStateFlow()

    // Filtered listings derived from uiState + typeFilter
    val filteredListings: StateFlow<List<Listing>> = combine(
        _uiState, _typeFilter
    ) { state, filter ->
        if (state is ShopDetailUiState.Success) {
            if (filter == null) state.listings
            else state.listings.filter { it.listingType == filter }
        } else emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = ShopDetailUiState.Loading
            getShop(shopId).fold(
                onSuccess = { shop ->
                    // Check if current user is owner
                    val currentUser = observeCurrentUser().first()
                    _isOwner.value = currentUser?.uid == shop.ownerId

                    // Check follow state using shop owner's uid
                    if (currentUser != null && currentUser.uid != shop.ownerId) {
                        _isFollowing.value = isFollowingUseCase(shop.ownerId)
                    }

                    getShopListings(shopId).collect { listings ->
                        _uiState.value = ShopDetailUiState.Success(shop, listings)
                    }
                },
                onFailure = {
                    _uiState.value = ShopDetailUiState.Error(it.message ?: "Failed to load shop")
                }
            )
        }
    }

    fun toggleFollow() {
        val state = _uiState.value as? ShopDetailUiState.Success ?: return
        viewModelScope.launch {
            _isFollowLoading.value = true
            if (_isFollowing.value) {
                unfollowUser(state.shop.ownerId)
                _isFollowing.value = false
            } else {
                followUser(state.shop.ownerId)
                _isFollowing.value = true
            }
            _isFollowLoading.value = false
        }
    }

    fun setTypeFilter(type: ListingType?) {
        _typeFilter.value = type
    }
}
