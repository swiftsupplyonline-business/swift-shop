package com.swiftshop.feature.home

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.SwiftShopColors
import com.swiftshop.core.ui.theme.swiftColors
import com.swiftshop.core.ui.navigation.Screen
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class HomeTab(val label: String) {
    SHOP("Shop"),
    POSTS("Posts"),
    REELS("Reels")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onSearch: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val shopState by viewModel.shopState.collectAsState()
    val postState by viewModel.postState.collectAsState()
    val reelState by viewModel.reelState.collectAsState()

    val pagerState = rememberPagerState(pageCount = { HomeTab.entries.size })
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ── Top Header ──────────────────────────────────────────────────────
        HomeHeader(
            onSearchClick = onSearch,
            onNotificationsClick = { /* navigate to notifications */ },
            onMessagesClick = { onNavigate(Screen.MessagingList.route) },
            onCartClick = { onNavigate(Screen.Checkout.route) }
        )

        // ── Tab Row ──────────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            indicator = { tabPositions ->
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[pagerState.currentPage])
                        .height(3.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primary)
                )
            },
            divider = {}
        ) {
            HomeTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (pagerState.currentPage == index)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        // ── Pager ────────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (HomeTab.entries[page]) {
                HomeTab.SHOP -> ShopTabContent(
                    state = shopState,
                    onLoadMore = { viewModel.loadMoreShop() },
                    onListingClick = { onNavigate(Screen.ListingDetail.createRoute(it)) },
                    onShopClick = { onNavigate(Screen.ShopDetail.createRoute(it)) },
                    onCreateListing = { onNavigate(Screen.SellGateway.route) }
                )
                HomeTab.POSTS -> PostsTabContent(
                    state = postState,
                    onLoadMore = { viewModel.loadMorePosts() },
                    onPostClick = { onNavigate(Screen.PostDetail.createRoute(it)) },
                    onUserClick = { onNavigate(Screen.UserProfile.createRoute(it)) },
                    onLikeClick = { postId, liked -> viewModel.toggleLike(postId, liked) },
                    onCreatePost = { onNavigate(Screen.CreatePost.route) }
                )
                HomeTab.REELS -> ReelsTabContent(
                    state = reelState,
                    onLoadMore = { viewModel.loadMoreReels() },
                    onCreateReel = { onNavigate(Screen.CreateReel.route) }
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onMessagesClick: () -> Unit,
    onCartClick: () -> Unit
) {
    val colors = MaterialTheme.swiftColors
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Wordmark
            Text(
                "Swift Shop",
                style = MaterialTheme.typography.titleLarge,
                color = colors.brandBlue,
                modifier = Modifier.weight(1f)
            )

            // Action icons
            val iconTint = MaterialTheme.swiftColors.brandBlue
            IconButton(onClick = onCartClick) {
                Icon(Icons.Default.ShoppingCart, "Cart",
                    modifier = Modifier.size(24.dp),
                    tint = iconTint)
            }
            IconButton(onClick = onMessagesClick) {
                Icon(Icons.Default.Message, "Messages",
                    modifier = Modifier.size(24.dp),
                    tint = iconTint)
            }
            IconButton(onClick = onNotificationsClick) {
                Icon(Icons.Default.Notifications, "Notifications",
                    modifier = Modifier.size(24.dp),
                    tint = iconTint)
            }
        }
    }

    // Search bar below header
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onSearchClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Search Swift Shop…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── Shop Tab ─────────────────────────────────────────────────────────────────

@Composable
fun ShopTabContent(
    state: PagingState<Listing>,
    onLoadMore: () -> Unit,
    onListingClick: (String) -> Unit,
    onShopClick: (String) -> Unit,
    onCreateListing: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is PagingState.Loading -> LoadingState()
            is PagingState.Empty -> EmptyState(
                title = "No listings yet",
                subtitle = "Be the first to sell something",
                action = {
                    SwiftPrimaryButton("Sell Something", onClick = onCreateListing)
                }
            )
            is PagingState.Error -> ErrorState(state.message, onRetry = onLoadMore)
            is PagingState.Success, is PagingState.LoadingMore -> {
                val items = when (state) {
                    is PagingState.Success -> state.items
                    is PagingState.LoadingMore -> state.items
                    else -> emptyList()
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { listing ->
                        ListingCard(listing = listing, onClick = { onListingClick(listing.id) })
                    }
                    if (state is PagingState.LoadingMore) {
                        item(span = { GridItemSpan(2) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    // Trigger load more near end
                    item(span = { GridItemSpan(2) }) {
                        LaunchedEffect(Unit) { onLoadMore() }
                    }
                }
            }
            else -> Unit
        }

        // FAB
        FloatingActionButton(
            onClick = onCreateListing,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, "Sell something", tint = Color.White)
        }
    }
}

@Composable
fun PostsTabContent(
    state: PagingState<FeedPost>,
    onLoadMore: () -> Unit,
    onPostClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onLikeClick: (String, Boolean) -> Unit,
    onCreatePost: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is PagingState.Loading -> LoadingState()
            is PagingState.Empty -> EmptyState("No posts yet", "Follow sellers to see their posts")
            is PagingState.Error -> ErrorState(state.message, onRetry = onLoadMore)
            is PagingState.Success, is PagingState.LoadingMore -> {
                val items = when (state) {
                    is PagingState.Success -> state.items
                    is PagingState.LoadingMore -> state.items
                    else -> emptyList()
                }
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            onClick = { onPostClick(post.id) },
                            onUserClick = { onUserClick(post.authorId) },
                            onLikeClick = { onLikeClick(post.id, !post.isLikedByMe) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    item { LaunchedEffect(Unit) { onLoadMore() } }
                }
            }
            else -> Unit
        }

        FloatingActionButton(
            onClick = onCreatePost,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, "Create post", tint = Color.White)
        }
    }
}

// ─── Reels Tab ────────────────────────────────────────────────────────────────

@Composable
fun ReelsTabContent(
    state: PagingState<FeedPost>,
    onLoadMore: () -> Unit,
    onCreateReel: () -> Unit
) {
    var activeCommentPostId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is PagingState.Loading -> LoadingState()
            is PagingState.Empty -> EmptyState(
                "No reels yet",
                "Create a short video to showcase your products",
                action = { SwiftPrimaryButton("Upload Reel", onClick = onCreateReel) }
            )
            is PagingState.Error -> ErrorState(state.message, onRetry = onLoadMore)
            is PagingState.Success, is PagingState.LoadingMore -> {
                val items = when (state) {
                    is PagingState.Success -> state.items
                    is PagingState.LoadingMore -> state.items
                    else -> emptyList()
                }
                // Full-screen vertical pager for reels
                VerticalReelsPager(reels = items, onLoadMore = onLoadMore, onCommentClick = { activeCommentPostId = it.id })
            }
            else -> Unit
        }

        FloatingActionButton(
            onClick = onCreateReel,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.VideoCall, "Upload reel", tint = Color.White)
        }

        if (activeCommentPostId != null) {
            CommentBottomSheet(postId = activeCommentPostId!!, onDismiss = { activeCommentPostId = null })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VerticalReelsPager(reels: List<FeedPost>, onLoadMore: () -> Unit, onCommentClick: (FeedPost) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { reels.size })

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { index ->
        if (index >= reels.size - 2) {
            LaunchedEffect(Unit) { onLoadMore() }
        }
        ReelItem(reel = reels[index], isActive = index == pagerState.currentPage, onCommentClick = onCommentClick)
    }
}

@Composable
fun ReelItem(reel: FeedPost, isActive: Boolean, onCommentClick: (FeedPost) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        SwiftVideoPlayer(
            url = reel.videoUrl,
            isActive = isActive,
            modifier = Modifier.fillMaxSize()
        )

        // Overlay: author + actions
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .fillMaxWidth(0.75f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SwiftAvatar(url = reel.authorAvatarUrl, tier = reel.authorTier)
                Spacer(Modifier.width(8.dp))
                Text(reel.authorName, style = MaterialTheme.typography.titleSmall,
                    color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(reel.caption, style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f), maxLines = 3)
        }

        // Right-side action column
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 88.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ReelAction(Icons.Default.Favorite, reel.likeCount.toString())
            Spacer(Modifier.height(20.dp))
            ReelAction(Icons.Default.ChatBubble, reel.commentCount.toString(), onClick = { onCommentClick(reel) })
            Spacer(Modifier.height(20.dp))
            ReelAction(Icons.Default.Share, "Share")
            Spacer(Modifier.height(20.dp))
            ReelAction(Icons.Default.BookmarkBorder, "Save")
        }
    }
}

@Composable
private fun ReelAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@HiltViewModel
class ReelCommentViewModel @Inject constructor(
    private val observePostComments: com.swiftshop.domain.feed.ObservePostCommentsUseCase,
    private val postPostComment: com.swiftshop.domain.feed.PostPostCommentUseCase,
    private val deletePostComment: com.swiftshop.domain.feed.DeletePostCommentUseCase,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase
) : ViewModel() {
    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments = _comments.asStateFlow()

    private var currentPostId: String? = null
    private var collectJob: kotlinx.coroutines.Job? = null

    fun setPostId(id: String) {
        if (currentPostId == id) return
        currentPostId = id
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            observePostComments(id).collect {
                _comments.value = it
            }
        }
    }

    fun addComment(text: String) {
        val id = currentPostId ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            val user = observeCurrentUser().first() ?: return@launch
            val comment = Comment(
                postId = id,
                authorId = user.uid,
                authorName = user.displayName,
                authorAvatarUrl = user.photoUrl,
                text = text.trim()
            )
            postPostComment(comment)
        }
    }

    fun deleteComment(commentId: String) {
        viewModelScope.launch {
            deletePostComment(commentId)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentBottomSheet(
    postId: String,
    onDismiss: () -> Unit,
    viewModel: ReelCommentViewModel = hiltViewModel()
) {
    LaunchedEffect(postId) {
        viewModel.setPostId(postId)
    }

    val comments by viewModel.comments.collectAsState()
    var commentText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Comments",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(comments, key = { it.id }) { comment ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        SwiftAvatar(url = comment.authorAvatarUrl, tier = UserTier.BASIC, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = comment.authorName, style = MaterialTheme.typography.titleSmall)
                            Text(text = comment.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = { Text("Add a comment…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (commentText.isNotBlank()) {
                            viewModel.addComment(commentText)
                            commentText = ""
                        }
                    }
                ) {
                    Icon(Icons.Default.Send, "Send")
                }
            }
        }
    }
}
