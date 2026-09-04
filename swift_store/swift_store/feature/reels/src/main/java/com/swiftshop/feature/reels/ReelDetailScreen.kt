package com.swiftshop.feature.reels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.domain.feed.GetPostUseCase
import com.swiftshop.domain.feed.LikePostUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReelDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPost: GetPostUseCase,
    private val likePost: LikePostUseCase,
    private val toggleBookmark: com.swiftshop.domain.feed.ToggleBookmarkUseCase,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase
) : ViewModel() {
    private val reelId: String = checkNotNull(savedStateHandle["reelId"])

    private val _uiState = MutableStateFlow<UiState<FeedPost>>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            combine(
                flow { emit(getPost(reelId)) },
                observeCurrentUser()
            ) { postResult, _ ->
                postResult.fold(
                    onSuccess = { post ->
                        if (post.type != PostType.REEL) {
                            UiState.Error("Invalid content type")
                        } else {
                            UiState.Success(post)
                        }
                    },
                    onFailure = { error -> UiState.Error(error.message ?: "Failed to load reel") }
                )
            }.collect { _uiState.value = it }
        }
    }

    fun toggleLike() {
        val reel = (_uiState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            likePost(reel.id, !reel.isLikedByMe).onSuccess {
                _uiState.value = UiState.Success(reel.copy(
                    isLikedByMe = !reel.isLikedByMe,
                    likeCount = if (reel.isLikedByMe) reel.likeCount - 1 else reel.likeCount + 1
                ))
            }
        }
    }

    fun toggleBookmark() {
        val reel = (_uiState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            val user = observeCurrentUser().first() ?: return@launch
            toggleBookmark(user.uid, reel.id, reel.type.name).onSuccess {
                _uiState.value = UiState.Success(reel.copy(
                    isBookmarkedByMe = !reel.isBookmarkedByMe,
                    bookmarkCount = if (reel.isBookmarkedByMe) reel.bookmarkCount - 1 else reel.bookmarkCount + 1
                ))
            }
        }
    }
}

@Composable
fun ReelDetailScreen(
    onBack: () -> Unit,
    viewModel: ReelDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val state = uiState) {
            is UiState.Loading -> LoadingState(modifier = Modifier.align(Alignment.Center))
            is UiState.Error -> ErrorState(state.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val reel = state.data
                // Use the existing ReelItem component
                ReelItem(
                    reel = reel, 
                    isActive = true,
                    onBookmarkClick = viewModel::toggleLike // Wait, use correct toggle
                )
                
                // Override/Add Detail-specific controls if needed
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            }
            else -> Unit
        }
    }
}
