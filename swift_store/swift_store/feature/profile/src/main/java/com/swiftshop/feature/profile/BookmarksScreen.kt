package com.swiftshop.feature.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.feed.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val getBookmarks: GetBookmarksUseCase,
    private val toggleBookmark: ToggleBookmarkUseCase,
    private val observeCurrentUser: ObserveCurrentUserUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<FeedItem>>>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            val user = observeCurrentUser().first()
            if (user == null) {
                _uiState.value = UiState.Error("User not signed in")
                return@launch
            }

            getBookmarks(user.uid).collect { items ->
                if (items.isEmpty()) {
                    _uiState.value = UiState.Empty
                } else {
                    _uiState.value = UiState.Success(items)
                }
            }
        }
    }

    fun onToggleBookmark(contentId: String, type: String) {
        viewModelScope.launch {
            val user = observeCurrentUser().first() ?: return@launch
            toggleBookmark(user.uid, contentId, type)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    navController: NavController,
    viewModel: BookmarksViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is UiState.Loading -> LoadingState()
                is UiState.Empty -> EmptyState(
                    title = "No saved items",
                    subtitle = "Items you bookmark will appear here."
                )
                is UiState.Error -> ErrorState(state.message, onRetry = viewModel::load)
                is UiState.Success -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(state.data) { item ->
                            when (item) {
                                is FeedItem.PostItem -> {
                                    PostCard(
                                        post = item.post.copy(isBookmarkedByMe = true),
                                        onClick = { 
                                            if (item.post.type == PostType.REEL) {
                                                navController.navigate(Screen.ReelDetail.createRoute(item.post.id))
                                            } else {
                                                navController.navigate(Screen.PostDetail.createRoute(item.post.id))
                                            }
                                        },
                                        onUserClick = { navController.navigate(Screen.UserProfile.createRoute(item.post.authorId)) },
                                        onLikeClick = { /* handled in Home/Detail */ },
                                        onBookmarkClick = { viewModel.onToggleBookmark(item.post.id, item.post.type.name) }
                                    )
                                }
                                is FeedItem.ListingItem -> {
                                    // Using a wider card for bookmarks list or grid? 
                                    // For simplicity in detail navigation, we use the standard card in a Row or just Column
                                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                        ListingCard(
                                            listing = item.listing.copy(isBookmarkedByMe = true),
                                            onClick = { navController.navigate(Screen.ListingDetail.createRoute(item.listing.id)) },
                                            onBookmarkClick = { viewModel.onToggleBookmark(item.listing.id, "LISTING") }
                                        )
                                    }
                                }
                                else -> {}
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}
