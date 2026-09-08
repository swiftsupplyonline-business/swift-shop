package com.swiftshop.feature.profile

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.SwiftShopColors
import com.swiftshop.core.ui.theme.swiftColors
import com.swiftshop.core.ui.navigation.Screen

@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val walletState by viewModel.walletState.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionState) {
        when (val state = actionState) {
            is ActionState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearActionState()
            }
            is ActionState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearActionState()
            }
            else -> {}
        }
    }

    when (val state = uiState) {
        is ProfileUiState.Loading -> LoadingState()
        is ProfileUiState.Error -> ErrorState(state.message, onRetry = { viewModel.load() })
        is ProfileUiState.Success -> ProfileContent(
            user = state.user,
            profile = state.profile,
            shops = state.shops,
            listings = state.recentListings,
            posts = state.recentPosts,
            reels = state.recentReels,
            wallet = walletState,
            isOwnProfile = state.isOwnProfile,
            onBack = { navController.popBackStack() },
            onFollow = { viewModel.toggleFollow() },
            onMessage = { navController.navigate(Screen.MessagingList.route) },
            onWalletClick = { navController.navigate(Screen.Wallet.route) },
            onShopClick = { navController.navigate(Screen.ShopDetail.createRoute(it)) },
            onListingClick = { navController.navigate(Screen.ListingDetail.createRoute(it)) },
            onPostClick = { navController.navigate(Screen.PostDetail.createRoute(it)) },
            onReelClick = { navController.navigate(Screen.ReelDetail.createRoute(it)) },
            onBookmarksClick = { navController.navigate(Screen.Bookmarks.route) },
            onSettings = { navController.navigate(Screen.Settings.route) },
            onEditProfile = { navController.navigate(Screen.EditProfile.route) },
            onToggleBookmark = { id, type -> viewModel.onToggleBookmark(id, type) },
            onOrdersClick = { navController.navigate(Screen.Orders.route) },
            onCreatePost = { navController.navigate(Screen.CreatePost.route) },
            onCreateListing = { navController.navigate(Screen.CreateListing.route) },
            onCreateShop = { navController.navigate(Screen.CreateShop.route) },
            onUpgradeTier = {
                val target = if (state.profile.tier == UserTier.BASIC) UserTier.PREMIUM else UserTier.ELITE
                viewModel.upgradeTier(target)
            },
            onSignOut = {
                viewModel.signOut()
                navController.navigate(Screen.Auth.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
            snackbarHostState = snackbarHostState
        )
    }
}

@Composable
private fun ProfileContent(
    user: User,
    profile: UserProfile,
    shops: List<Shop>,
    listings: List<Listing>,
    posts: List<FeedPost>,
    reels: List<FeedPost>,
    wallet: WalletUiState,
    isOwnProfile: Boolean,
    onBack: () -> Unit,
    onFollow: () -> Unit,
    onMessage: () -> Unit,
    onWalletClick: () -> Unit,
    onShopClick: (String) -> Unit,
    onListingClick: (String) -> Unit,
    onPostClick: (String) -> Unit,
    onReelClick: (String) -> Unit,
    onBookmarksClick: () -> Unit,
    onSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onToggleBookmark: (String, String) -> Unit,
    onOrdersClick: () -> Unit,
    onCreatePost: () -> Unit,
    onCreateListing: () -> Unit,
    onCreateShop: () -> Unit,
    onUpgradeTier: () -> Unit,
    onSignOut: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val colors = MaterialTheme.swiftColors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Listings", "Posts", "Shops")

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            // â”€â”€ Cover + Avatar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            item {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                    AsyncImage(
                        model = profile.coverUrl,
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Gradient scrim
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(0.1f), Color.Black.copy(0.5f))
                            )
                        )
                    )
                    // Controls row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.4f))
                        ) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                        if (isOwnProfile) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = onBookmarksClick,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Default.BookmarkBorder, "Saved", tint = Color.White)
                                }
                                IconButton(
                                    onClick = onSettings,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.4f))
                                ) {
                                    Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // â”€â”€ Avatar + action buttons row â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // Avatar overlapping cover
                    SwiftAvatar(
                        url = profile.avatarUrl,
                        size = 84.dp,
                        tier = profile.tier,
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .offset(y = (-42).dp)
                    )

                    // Action buttons â€” right side, vertically centered with avatar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 12.dp, top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isOwnProfile) {
                            OutlinedButton(
                                onClick = onMessage,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(Icons.Default.Message, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Message", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = onFollow,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Text(
                                    if (profile.isFollowedByMe) "Following" else "Follow",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        } else {
                            Button(
                                onClick = onEditProfile,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(38.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Edit Profile", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    // Bottom spacer to compensate avatar offset
                    Spacer(modifier = Modifier.height(48.dp).align(Alignment.BottomStart))
                }
            }

            // â”€â”€ Quick action row (own profile) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOwnProfile) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QuickActionButton(
                            icon = Icons.Default.AddPhotoAlternate,
                            label = "Post",
                            onClick = onCreatePost,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionButton(
                            icon = Icons.Default.AddShoppingCart,
                            label = "Listing",
                            onClick = onCreateListing,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionButton(
                            icon = Icons.Default.Storefront,
                            label = "Shop",
                            onClick = onCreateShop,
                            modifier = Modifier.weight(1f)
                        )
                        QuickActionButton(
                            icon = Icons.Default.ReceiptLong,
                            label = "Orders",
                            onClick = onOrdersClick,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // â”€â”€ Name + Bio + Stats â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                ) {
                    // Name + verified + tier
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            user.displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (user.isVerified) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Verified, "Verified",
                                tint = colors.brandBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TierBadge(tier = profile.tier, size = 24.dp)
                    }

                    // Location
                    if (profile.location.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.LocationOn, null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                profile.location,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Bio
                    if (profile.bio.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(profile.bio, style = MaterialTheme.typography.bodyMedium)
                    }

                    // Stats row
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ProfileStat(profile.followerCount.toString(), "Followers")
                            StatDivider()
                            ProfileStat(profile.followingCount.toString(), "Following")
                            StatDivider()
                            ProfileStat(profile.activeListingCount.toString(), "Listings")
                            StatDivider()
                            ProfileStat(profile.shopCount.toString(), "Shops")
                        }
                    }
                }
            }

            // â”€â”€ Wallet Card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOwnProfile) {
                item {
                    Spacer(Modifier.height(8.dp))
                    WalletCard(
                        tier = profile.tier,
                        walletState = wallet,
                        onWalletClick = onWalletClick,
                        onUpgrade = onUpgradeTier,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // â”€â”€ Performance Card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            item {
                Spacer(Modifier.height(8.dp))
                PerformanceCard(
                    reputationScore = profile.reputationScore,
                    deliveries = profile.totalDeliveries,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // â”€â”€ Tab Row â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            stickyHeader {
                Surface(shadowElevation = 4.dp) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        edgePadding = 0.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        indicator = { tabPositions ->
                            Box(
                                modifier = Modifier
                                    .tabIndicatorOffset(tabPositions[selectedTab])
                                    .height(3.dp)
                                    .padding(horizontal = 16.dp)
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selectedTab == index)
                                            FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // â”€â”€ Tab Content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            when (selectedTab) {
                0 -> { // Listings
                    if (listings.isEmpty()) {
                        item {
                            EmptyState(
                                "No listings",
                                "This seller has no active listings",
                                modifier = Modifier.height(200.dp)
                            )
                        }
                    } else {
                        item {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                contentPadding = PaddingValues(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.height(
                                    ((listings.size / 2 + listings.size % 2) * 240).dp
                                )
                            ) {
                                items(listings, key = { it.id }) { listing ->
                                    com.swiftshop.core.ui.components.ListingCard(
                                        listing = listing,
                                        onClick = { onListingClick(listing.id) }
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> { // Posts
                    if (posts.isEmpty()) {
                        item { EmptyState("No posts", modifier = Modifier.height(200.dp)) }
                    } else {
                        items(posts, key = { it.id }) { post ->
                            com.swiftshop.core.ui.components.PostCard(
                                post = post,
                                onClick = { onPostClick(post.id) },
                                onUserClick = {},
                                onLikeClick = {},
                                onBookmarkClick = { onToggleBookmark(post.id, post.type.name) }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
                2 -> { // Shops
                    if (shops.isEmpty()) {
                        item { EmptyState("No shops", modifier = Modifier.height(200.dp)) }
                    } else {
                        items(shops, key = { it.id }) { shop ->
                            ShopListItem(shop = shop, onClick = { onShopClick(shop.id) })
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            // â”€â”€ Sign Out â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOwnProfile) {
                item {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f))
                            .clickable { onSignOut() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Logout, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Sign Out",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

// â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(32.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    )
}

@Composable
private fun ProfileStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// â”€â”€â”€ Wallet Card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
fun WalletCard(
    tier: UserTier,
    walletState: WalletUiState,
    onWalletClick: () -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.swiftColors
    val cardGradient = when (tier) {
        UserTier.BASIC -> listOf(Color(0xFF1D6BF3), Color(0xFF0D1B3E))
        UserTier.PREMIUM -> listOf(colors.premiumGradientStart, colors.premiumGradientEnd)
        UserTier.ELITE -> listOf(colors.eliteObsidian, Color(0xFF1A1A2E))
    }

    var balanceVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = cardGradient.first().copy(alpha = 0.3f),
                spotColor = cardGradient.first().copy(alpha = 0.4f)
            )
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(cardGradient))
            .clickable(onClick = onWalletClick)
            .padding(20.dp)
    ) {
        // Elite border
        if (tier == UserTier.ELITE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(colors.eliteGold, Color.Transparent, colors.eliteGold)
                        ),
                        RoundedCornerShape(24.dp)
                    )
            )
        }

        Column {
            // â”€â”€ Tier label + badge â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${tier.name} WALLET",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp
                )
                TierBadge(tier = tier, size = 34.dp)
            }

            Spacer(Modifier.height(16.dp))

            // â”€â”€ Balance â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (val ws = walletState) {
                    is WalletUiState.Loaded -> {
                        Text(
                            if (balanceVisible) ws.wallet.availableBalance.toDisplayString()
                            else "â€¢â€¢â€¢â€¢  â€¢â€¢â€¢â€¢",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (tier == UserTier.ELITE) colors.eliteGold else Color.White
                        )
                    }
                    is WalletUiState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    }
                    else -> Text(
                        "â€”",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable { balanceVisible = !balanceVisible },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (balanceVisible) Icons.Default.Visibility
                        else Icons.Default.VisibilityOff,
                        null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // â”€â”€ Action chips â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WalletChip("Deposit", Icons.Default.Add, onClick = onWalletClick)
                WalletChip("Withdraw", Icons.Default.ArrowUpward, onClick = onWalletClick)
                WalletChip("Transfer", Icons.Default.SwapHoriz, onClick = onWalletClick)
            }
        }
    }
}

@Composable
private fun WalletChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

// â”€â”€â”€ Performance Card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun PerformanceCard(
    reputationScore: Float,
    deliveries: Int,
    modifier: Modifier = Modifier
) {
    SwiftCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Performance",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star, null,
                        tint = SwiftShopColors.Warning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${"%.1f".format(reputationScore)} rating",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$deliveries",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "deliveries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// â”€â”€â”€ Shop List Item â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun ShopListItem(shop: Shop, onClick: () -> Unit) {
    SwiftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (shop.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = shop.logoUrl,
                    contentDescription = shop.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    SwiftEntityIcon(
                        entity = SwiftEntity.SHOP,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    shop.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    shop.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star, null,
                        tint = SwiftShopColors.Warning,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        " ${shop.rating} Â· ${shop.listingCount} listings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }


}
