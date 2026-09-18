package com.swiftshop.feature.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.Listing
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CartItem
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.core.model.Comment
import com.swiftshop.domain.commerce.GetListingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
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
    private val addToCartUseCase: com.swiftshop.domain.commerce.AddToCartUseCase,
    private val likeListingUseCase: com.swiftshop.domain.commerce.LikeListingUseCase,
    private val unlikeListingUseCase: com.swiftshop.domain.commerce.UnlikeListingUseCase,
    private val bookmarkListingUseCase: com.swiftshop.domain.commerce.BookmarkListingUseCase,
    private val unbookmarkListingUseCase: com.swiftshop.domain.commerce.UnbookmarkListingUseCase,
    private val observeListingCommentsUseCase: com.swiftshop.domain.feed.ObserveListingCommentsUseCase,
    private val postListingCommentUseCase: com.swiftshop.domain.feed.PostListingCommentUseCase,
    private val deleteListingCommentUseCase: com.swiftshop.domain.feed.DeleteListingCommentUseCase,
    private val getShopUseCase: com.swiftshop.domain.commerce.GetShopUseCase,
    private val getShopListingsUseCase: com.swiftshop.domain.commerce.GetShopListingsUseCase,
    private val getSimilarListingsUseCase: com.swiftshop.domain.commerce.GetSimilarListingsUseCase,
    private val commerceRepository: CommerceRepository
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

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private var commentsJob: kotlinx.coroutines.Job? = null

    private val _shop = MutableStateFlow<com.swiftshop.core.model.Shop?>(null)
    val shop: StateFlow<com.swiftshop.core.model.Shop?> = _shop.asStateFlow()

    private val _moreFromShop = MutableStateFlow<List<Listing>>(emptyList())
    val moreFromShop: StateFlow<List<Listing>> = _moreFromShop.asStateFlow()

    private val _similarListings = MutableStateFlow<List<Listing>>(emptyList())
    val similarListings: StateFlow<List<Listing>> = _similarListings.asStateFlow()

    init {
        // Derive isOwner reactively — no race between user and listing resolution
        viewModelScope.launch {
            combine(uiState, observeCurrentUser()) { state, user ->
                user?.uid != null &&
                    state is ListingDetailState.Loaded &&
                    user.uid == state.listing.sellerId
            }.collect { _isOwner.value = it }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            val user = observeCurrentUser().first()
            _currentUserId.value = user?.uid
        }
        viewModelScope.launch {
            _uiState.value = ListingDetailState.Loading
            getListing(listingId).fold(
                onSuccess = { listing ->
                    _uiState.value = ListingDetailState.Loaded(listing)
                    loadShop(listing.shopId)
                    loadSuggestions(listing)
                },
                onFailure = { _uiState.value = ListingDetailState.Error(it.message ?: "Failed to load") }
            )
        }
    }

    private fun loadSuggestions(listing: Listing) {
        viewModelScope.launch {
            getShopListingsUseCase(listing.shopId, page = 0, pageSize = 11).collect { shopListings ->
                _moreFromShop.value = shopListings.filter { it.id != listing.id }.take(10)
            }
        }
        viewModelScope.launch {
            getSimilarListingsUseCase(listing.category, listing.id, limit = 10).onSuccess {
                _similarListings.value = it
            }
        }
    }

    private fun loadShop(shopId: String) {
        viewModelScope.launch {
            getShopUseCase(shopId).onSuccess { _shop.value = it }
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
    private val _isOwner = MutableStateFlow(false)
    val isOwner = _isOwner.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting = _isDeleting.asStateFlow()



    fun toggleLike() {
        val state = _uiState.value as? ListingDetailState.Loaded ?: return
        val listing = state.listing
        val wasLiked = listing.isLikedByMe

        // Optimistic update
        _uiState.value = ListingDetailState.Loaded(
            listing.copy(
                isLikedByMe = !wasLiked,
                likeCount = if (wasLiked) (listing.likeCount - 1).coerceAtLeast(0) else listing.likeCount + 1
            )
        )

        viewModelScope.launch {
            val result = if (wasLiked) unlikeListingUseCase(listing.id) else likeListingUseCase(listing.id)
            result.onFailure {
                // Revert on failure
                _uiState.value = ListingDetailState.Loaded(listing)
                _actionState.value = ActionState.Error("Couldn't update like — try again")
            }
        }
    }

    fun toggleBookmark() {
        val state = _uiState.value as? ListingDetailState.Loaded ?: return
        val listing = state.listing
        val wasBookmarked = listing.isBookmarkedByMe

        // Optimistic update
        _uiState.value = ListingDetailState.Loaded(
            listing.copy(
                isBookmarkedByMe = !wasBookmarked,
                bookmarkCount = if (wasBookmarked) (listing.bookmarkCount - 1).coerceAtLeast(0) else listing.bookmarkCount + 1
            )
        )

        viewModelScope.launch {
            val result = if (wasBookmarked) unbookmarkListingUseCase(listing.id) else bookmarkListingUseCase(listing.id)
            result.onFailure {
                // Revert on failure
                _uiState.value = ListingDetailState.Loaded(listing)
                _actionState.value = ActionState.Error("Couldn't update bookmark — try again")
            }
        }
    }

    fun deleteListing(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _isDeleting.value = true
            commerceRepository.deleteListing(listingId).fold(
                onSuccess = { onDeleted() },
                onFailure = { _actionState.value = ActionState.Error(it.message ?: "Failed to delete") }
            )
            _isDeleting.value = false
        }
    }

    fun loadComments() {
        if (commentsJob != null) return
        commentsJob = viewModelScope.launch {
            observeListingCommentsUseCase(listingId).collect { _comments.value = it }
        }
    }

    fun postComment(text: String, parentCommentId: String?) {
        viewModelScope.launch {
            val user = observeCurrentUser().first() ?: run {
                _actionState.value = ActionState.Error("Sign in to comment")
                return@launch
            }
            val comment = Comment(
                listingId = listingId,
                authorId = user.uid,
                authorName = user.displayName,
                authorAvatarUrl = user.photoUrl,
                text = text,
                parentCommentId = parentCommentId ?: ""
            )
            postListingCommentUseCase(comment).onFailure {
                _actionState.value = ActionState.Error("Couldn't post comment")
            }
        }
    }

    fun deleteComment(comment: Comment) {
        viewModelScope.launch {
            deleteListingCommentUseCase(comment.id).onFailure {
                _actionState.value = ActionState.Error("Couldn't delete comment")
            }
        }
    }

}
