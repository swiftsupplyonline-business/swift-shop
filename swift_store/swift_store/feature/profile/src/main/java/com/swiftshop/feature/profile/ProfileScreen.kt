package com.swiftshop.feature.profile

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        is ProfileUiState.Success -> {
            ProfileContent(
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
    onCreatePost: () -> Unit,
    onCreateListing: () -> Unit,
    onCreateShop: () -> Unit,
    onUpgradeTier: () -> Unit,
    onSignOut: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val colors = MaterialTheme.swiftColors
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Listings", "Posts", "Reels", "Shops")

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

        // ── Cover + Avatar ─────────────────────────────────────────────────
        item {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                // Cover photo
                AsyncImage(
                    model = profile.coverUrl,
                    contentDescription = "Cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Gradient overlay
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                        )
                    )
                )
                // Back / Settings row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    if (isOwnProfile) {
                        Row {
                            IconButton(
                                onClick = onBookmarksClick,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.3f))
                            ) {
                                Icon(Icons.Default.BookmarkBorder, "Saved", tint = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = onSettings,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.3f))
                            ) {
                                Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ── Avatar row ─────────────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp)
            ) {
                // Avatar positioned to overlap cover
                SwiftAvatar(
                    url = profile.avatarUrl,
                    size = 80.dp,
                    tier = profile.tier,
                    modifier = Modifier.offset(y = (-40).dp)
                )
                // Action buttons — right aligned
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!isOwnProfile) {
                        OutlinedButton(
                            onClick = onMessage,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Message, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Message")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onFollow,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(if (profile.isFollowedByMe) "Following" else "Follow")
                        }
                    } else {
                        OutlinedButton(onClick = onEditProfile, shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit Profile")
                        }
                    }
                }
            }
        }

        // ── Action Row (own profile) ───────────────────────────────────────
        if (isOwnProfile) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCreatePost,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Post")
                    }
                    OutlinedButton(
                        onClick = onCreateListing,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.AddShoppingCart, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Listing")
                    }
                    OutlinedButton(
                        onClick = onCreateShop,
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(Icons.Default.Storefront, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Shop")
                    }
                }
            }
        }

        // ── Name + Bio ─────────────────────────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp)
                    .padding(top = 48.dp, bottom = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.displayName, style = MaterialTheme.typography.headlineSmall)
                    if (user.isVerified) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Verified, "Verified",
                            tint = colors.brandBlue, modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TierBadge(tier = profile.tier, size = 24.dp)
                }
                if (profile.location.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(profile.location, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (profile.bio.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(profile.bio, style = MaterialTheme.typography.bodyMedium)
                }

                // Stats row
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ProfileStat(profile.followerCount.toString(), "Followers")
                    ProfileStat(profile.followingCount.toString(), "Following")
                    ProfileStat(profile.activeListingCount.toString(), "Listings")
                    ProfileStat(profile.shopCount.toString(), "Shops")
                }
            }
        }

        // ── Wallet Card (own profile) ──────────────────────────────────────
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

        // ── Performance Card ───────────────────────────────────────────────
        item {
            Spacer(Modifier.height(8.dp))
            PerformanceCard(
                reputationScore = profile.reputationScore,
                deliveries = profile.totalDeliveries,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // ── Tab Row ────────────────────────────────────────────────────────
        stickyHeader {
            Surface(tonalElevation = 4.dp) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }
        }

        // ── Tab Content ────────────────────────────────────────────────────
        when (selectedTab) {
            0 -> { // Listings
                if (listings.isEmpty()) {
                    item {
                        EmptyState("No listings", "This seller has no active listings",
                            modifier = Modifier.height(200.dp))
                    }
                } else {
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.height(
                                ((listings.size / 2 + listings.size % 2) * 220).dp
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
                            Spacer(Modifier.height(8.dp))
                        }
                }
            }
            2 -> { // Reels
                if (reels.isEmpty()) {
                    item { EmptyState("No reels", modifier = Modifier.height(200.dp)) }
                } else {
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(1.dp),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier.height(
                                ((reels.size / 3 + if (reels.size % 3 > 0) 1 else 0) * 160).dp
                            )
                        ) {
                            items(reels, key = { it.id }) { reel ->
                                Box(modifier = Modifier
                                    .aspectRatio(0.75f)
                                    .clickable { onReelClick(reel.id) }
                                ) {
                                    AsyncImage(
                                        model = reel.thumbnailUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Play icon overlay
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .align(Alignment.BottomStart)
                                            .padding(4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            3 -> { // Shops
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

        // Sign out (own profile)
        if (isOwnProfile) {
            item {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Logout, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
}

// ─── Wallet Card ──────────────────────────────────────────────────────────────

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
        UserTier.BASIC -> listOf(colors.brandBlue, colors.deepNavy)
        UserTier.PREMIUM -> listOf(colors.premiumGradientStart, colors.premiumGradientEnd)
        UserTier.ELITE -> listOf(colors.eliteObsidian, Color(0xFF1A1A2E))
    }

    var balanceVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(cardGradient))
            .clickable(onClick = onWalletClick)
            .padding(20.dp)
    ) {
        // Elite shimmer border
        if (tier == UserTier.ELITE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(colors.eliteGold, Color.Transparent, colors.eliteGold)),
                        MaterialTheme.shapes.extraLarge
                    )
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "${tier.name} WALLET",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (val ws = walletState) {
                            is WalletUiState.Loaded -> {
                                Text(
                                    if (balanceVisible) ws.wallet.availableBalance.toDisplayString()
                                    else "•••• ••••",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = if (tier == UserTier.ELITE) colors.eliteGold else Color.White
                                )
                            }
                            is WalletUiState.Loading -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            }
                            else -> Text("—", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { balanceVisible = !balanceVisible },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (balanceVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                null, tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                TierBadge(tier = tier, size = 36.dp)
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

// ─── Performance Card ─────────────────────────────────────────────────────────

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
                Text("Performance", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null,
                        tint = SwiftShopColors.Warning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${"%.1f".format(reputationScore)} rating",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$deliveries", style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary)
                Text("deliveries", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShopListItem(shop: Shop, onClick: () -> Unit) {
    SwiftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (shop.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = shop.logoUrl,
                    contentDescription = shop.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.large)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.large)
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
                Text(shop.name, style = MaterialTheme.typography.titleSmall)
                Text(shop.category, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, null,
                        tint = SwiftShopColors.Warning, modifier = Modifier.size(12.dp))
                    Text(" ${shop.rating} · ${shop.listingCount} listings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
