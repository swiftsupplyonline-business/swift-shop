package com.swiftshop.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.FeedPost
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.PagingState
import com.swiftshop.domain.feed.GetPostFeedUseCase
import com.swiftshop.domain.feed.GetReelFeedUseCase
import com.swiftshop.domain.feed.GetShopFeedUseCase
import com.swiftshop.domain.feed.LikePostUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getShopFeed: GetShopFeedUseCase,
    private val getPostFeed: GetPostFeedUseCase,
    private val getReelFeed: GetReelFeedUseCase,
    private val likePost: LikePostUseCase
) : ViewModel() {

    // ── Shop feed ──────────────────────────────────────────────────────────
    private val _shopState = MutableStateFlow<PagingState<Listing>>(PagingState.Loading)
    val shopState: StateFlow<PagingState<Listing>> = _shopState.asStateFlow()
    private var shopPage = 0
    private var shopItems = listOf<Listing>()

    // ── Post feed ─────────────────────────────────────────────────────────
    private val _postState = MutableStateFlow<PagingState<FeedPost>>(PagingState.Loading)
    val postState: StateFlow<PagingState<FeedPost>> = _postState.asStateFlow()
    private var postPage = 0
    private var postItems = listOf<FeedPost>()

    // ── Reel feed ─────────────────────────────────────────────────────────
    private val _reelState = MutableStateFlow<PagingState<FeedPost>>(PagingState.Loading)
    val reelState: StateFlow<PagingState<FeedPost>> = _reelState.asStateFlow()
    private var reelPage = 0
    private var reelItems = listOf<FeedPost>()

    init {
        loadShop()
        loadPosts()
        loadReels()
    }

    private fun loadShop() {
        viewModelScope.launch {
            getShopFeed(page = 0).collect { result ->
                when (result) {
                    is PagingState.Success -> {
                        shopItems = result.items
                        _shopState.value = result
                    }
                    is PagingState.Error -> _shopState.value = result
                    is PagingState.Empty -> _shopState.value = result
                    else -> _shopState.value = PagingState.Loading
                }
            }
        }
    }

    fun loadMoreShop() {
        val current = _shopState.value
        if (current is PagingState.Success && !current.hasMore) return
        if (current is PagingState.LoadingMore) return
        viewModelScope.launch {
            _shopState.value = PagingState.LoadingMore(shopItems)
            shopPage++
            getShopFeed(page = shopPage).collect { result ->
                when (result) {
                    is PagingState.Success -> {
                        shopItems = shopItems + result.items
                        _shopState.value = PagingState.Success(shopItems, result.hasMore)
                    }
                    is PagingState.Error -> {
                        shopPage--
                        _shopState.value = PagingState.Success(shopItems, true)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun loadPosts() {
        viewModelScope.launch {
            getPostFeed(page = 0).collect { result ->
                when (result) {
                    is PagingState.Success -> {
                        postItems = result.items
                        _postState.value = result
                    }
                    is PagingState.Error -> _postState.value = result
                    is PagingState.Empty -> _postState.value = result
                    else -> _postState.value = PagingState.Loading
                }
            }
        }
    }

    fun loadMorePosts() {
        val current = _postState.value
        if (current is PagingState.Success && !current.hasMore) return
        if (current is PagingState.LoadingMore) return
        viewModelScope.launch {
            _postState.value = PagingState.LoadingMore(postItems)
            postPage++
            getPostFeed(page = postPage).collect { result ->
                when (result) {
                    is PagingState.Success -> {
                        postItems = postItems + result.items
                        _postState.value = PagingState.Success(postItems, result.hasMore)
                    }
                    is PagingState.Error -> {
                        postPage--
                        _postState.value = PagingState.Success(postItems, true)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun loadReels() {
        viewModelScope.launch {
            getReelFeed(page = 0).collect { result ->
                when (result) {
                    is PagingState.Success -> {
                        reelItems = result.items
                        _reelState.value = result
                    }
                    is PagingState.Error -> _reelState.value = result
                    is PagingState.Empty -> _reelState.value = result
                    else -> _reelState.value = PagingState.Loading
                }
            }
        }
    }

    fun loadMoreReels() {
        val current = _reelState.value
        if (current is PagingState.Success && !current.hasMore) return
        if (current is PagingState.LoadingMore) return
        viewModelScope.launch {
            _reelState.value = PagingState.LoadingMore(reelItems)
            reelPage++
            getReelFeed(page = reelPage).collect { result ->
                when (result) {
                    is PagingState.Success -> {
                        reelItems = reelItems + result.items
                        _reelState.value = PagingState.Success(reelItems, result.hasMore)
                    }
                    is PagingState.Error -> {
                        reelPage--
                        _reelState.value = PagingState.Success(reelItems, true)
                    }
                    else -> Unit
                }
            }
        }
    }

    fun toggleLike(postId: String, isLiked: Boolean) {
        viewModelScope.launch {
            // Optimistic update
            postItems = postItems.map { post ->
                if (post.id == postId) post.copy(
                    isLikedByMe = isLiked,
                    likeCount = if (isLiked) post.likeCount + 1 else post.likeCount - 1
                ) else post
            }
            _postState.value = PagingState.Success(postItems,
                (_postState.value as? PagingState.Success)?.hasMore ?: false)

            likePost(postId, isLiked).onFailure { e ->
                Timber.e(e, "Like failed, reverting")
                // Revert optimistic update
                postItems = postItems.map { post ->
                    if (post.id == postId) post.copy(
                        isLikedByMe = !isLiked,
                        likeCount = if (!isLiked) post.likeCount + 1 else post.likeCount - 1
                    ) else post
                }
                _postState.value = PagingState.Success(postItems,
                    (_postState.value as? PagingState.Success)?.hasMore ?: false)
            }
        }
    }
}
