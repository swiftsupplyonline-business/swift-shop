package com.swiftshop.feature.home

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.SwiftShopColors
import com.swiftshop.core.ui.theme.swiftColors
import com.swiftshop.core.ui.navigation.Screen
import kotlinx.coroutines.launch

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
            onMessagesClick = { onNavigate(Screen.MessagingList.route) }
        )

        // ── Tab Row ─────────────────────────────────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp
        ) {
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    Box(
                        modifier = Modifier
                            .tabIndicatorOffset(tabPositions[pagerState.currentPage])
                            .height(3.dp)
                            .padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                },
                divider = {}
            ) {
                HomeTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(index) }
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                        text = {
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (pagerState.currentPage == index)
                                    FontWeight.Bold else FontWeight.Normal,
                                color = if (pagerState.currentPage == index)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
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
                    onBookmarkClick = { viewModel.onToggleBookmark(it, "LISTING") },
                    onCreateListing = { onNavigate(Screen.CreateListing.route) }
                )
                HomeTab.POSTS -> PostsTabContent(
                    state = postState,
                    onLoadMore = { viewModel.loadMorePosts() },
                    onPostClick = { onNavigate(Screen.PostDetail.createRoute(it)) },
                    onUserClick = { onNavigate(Screen.UserProfile.createRoute(it)) },
                    onLikeClick = { postId, liked -> viewModel.toggleLike(postId, liked) },
                    onBookmarkClick = { viewModel.onToggleBookmark(it, "POST") },
                    onCreatePost = { onNavigate(Screen.CreatePost.route) }
                )
                HomeTab.REELS -> {
                    val uploadProgress by viewModel.reelUploadProgress.collectAsState(null)
                    ReelsTabContent(
                        state = reelState,
                        onLoadMore = { viewModel.loadMoreReels() },
                        onCreateReel = { onNavigate(Screen.CreateReel.route) },
                        onBookmarkClick = { viewModel.onToggleBookmark(it, "REEL") },
                        uploadProgress = uploadProgress
                    )
                }
            }
        }
    }
}

// ─── Header ──────────────────────────────────────────────────────────────────

@Composable
private fun HomeHeader(
    onSearchClick: () -> Unit,
    onNotificationsClick: () -> Unit,
    onMessagesClick: () -> Unit
) {
    val colors = MaterialTheme.swiftColors

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Wordmark with gradient text effect via box
                Text(
                    "Swift Shop",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp
                    ),
                    color = colors.brandBlue,
                    modifier = Modifier.weight(1f)
                )

                // Icon buttons
                IconButton(onClick = onMessagesClick) {
                    Icon(
                        Icons.Default.Message,
                        "Messages",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = onNotificationsClick) {
                    Icon(
                        Icons.Default.Notifications,
                        "Notifications",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Search bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onSearchClick() }
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Search Swift Shop…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ─── Shop Tab ────────────────────────────────────────────────────────────────

@Composable
fun ShopTabContent(
    state: PagingState<Listing>,
    onLoadMore: () -> Unit,
    onListingClick: (String) -> Unit,
    onShopClick: (String) -> Unit,
    onBookmarkClick: (String) -> Unit,
    onCreateListing: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is PagingState.Loading -> LoadingState()
            is PagingState.Empty -> EmptyState(
                title = "No listings yet",
                subtitle = "Be the first to add something for sale",
                action = { SwiftPrimaryButton("Add Listing", onClick = onCreateListing) }
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
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { listing ->
                        ListingCard(
                            listing = listing,
                            onClick = { onListingClick(listing.id) },
                            onBookmarkClick = { onBookmarkClick(listing.id) }
                        )
                    }
                    if (state is PagingState.LoadingMore) {
                        item(span = { GridItemSpan(2) }) {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                    item(span = { GridItemSpan(2) }) {
                        LaunchedEffect(Unit) { onLoadMore() }
                    }
                }
            }
            else -> Unit
        }

        // Gradient FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(SwiftShopColors.BrandBlue, SwiftShopColors.ElectricBlue)
                    )
                )
                .clickable { onCreateListing() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, "Add listing", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

// ─── Posts Tab ───────────────────────────────────────────────────────────────

@Composable
fun PostsTabContent(
    state: PagingState<FeedPost>,
    onLoadMore: () -> Unit,
    onPostClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onLikeClick: (String, Boolean) -> Unit,
    onBookmarkClick: (String) -> Unit,
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
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.id }) { post ->
                        PostCard(
                            post = post,
                            onClick = { onPostClick(post.id) },
                            onUserClick = { onUserClick(post.authorId) },
                            onLikeClick = { onLikeClick(post.id, !post.isLikedByMe) },
                            onBookmarkClick = { onBookmarkClick(post.id) }
                        )
                    }
                    item { LaunchedEffect(Unit) { onLoadMore() } }
                }
            }
            else -> Unit
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(SwiftShopColors.BrandBlue, SwiftShopColors.ElectricBlue)
                    )
                )
                .clickable { onCreatePost() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, "Create post", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

// ─── Reels Tab ───────────────────────────────────────────────────────────────

@Composable
fun ReelsTabContent(
    state: PagingState<FeedPost>,
    onLoadMore: () -> Unit,
    onCreateReel: () -> Unit,
    onBookmarkClick: (String) -> Unit,
    uploadProgress: com.swiftshop.core.media.MediaUploadProgress? = null
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (state) {
            is PagingState.Loading -> LoadingState()
            is PagingState.Empty -> EmptyState(
                "No reels yet",
                "Create a short video to showcase your products",
                action = {
                    SwiftGlassmorphicButton(onClick = onCreateReel) {
                        Icon(Icons.Default.VideoCall, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Upload Reel")
                    }
                }
            )
            is PagingState.Error -> ErrorState(state.message, onRetry = onLoadMore)
            is PagingState.Success, is PagingState.LoadingMore -> {
                val items = when (state) {
                    is PagingState.Success -> state.items
                    is PagingState.LoadingMore -> state.items
                    else -> emptyList()
                }
                VerticalReelsPager(
                    reels = items,
                    onLoadMore = onLoadMore,
                    onBookmarkClick = onBookmarkClick
                )
            }
            else -> Unit
        }

        uploadProgress?.let { progress ->
            val p = when (progress) {
                is com.swiftshop.core.media.MediaUploadProgress.InProgress -> progress.percent / 100f
                else -> null
            }
            SwiftUploadProgressBar(
                progress = p,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            )
        }

        SwiftGlassmorphicButton(
            onClick = onCreateReel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .navigationBarsPadding()
        ) {
            Icon(Icons.Default.VideoCall, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text("Upload Reel", style = MaterialTheme.typography.labelLarge)
        }
    }
}
