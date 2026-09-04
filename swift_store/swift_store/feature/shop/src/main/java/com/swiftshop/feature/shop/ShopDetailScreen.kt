package com.swiftshop.feature.shop

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.ListingType
import com.swiftshop.core.model.Shop
import com.swiftshop.core.model.SwiftEntity
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.core.ui.theme.swiftColors

private fun ListingType.chipLabel(): String = when (this) {
    ListingType.BUY             -> "Buy"
    ListingType.MAKE_PAYMENT    -> "Payment"
    ListingType.SET_APPOINTMENT -> "Appointments"
    ListingType.PLACE_ORDER     -> "Orders"
    ListingType.REGISTER        -> "Register"
    ListingType.DELIVER         -> "Delivery"
    ListingType.TAKE_ME_THERE   -> "Directions"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopDetailScreen(
    navController: NavController,
    viewModel: ShopDetailViewModel = hiltViewModel()
) {
    val uiState        by viewModel.uiState.collectAsState()
    val isFollowing    by viewModel.isFollowing.collectAsState()
    val isFollowLoad   by viewModel.isFollowLoading.collectAsState()
    val isOwner        by viewModel.isOwner.collectAsState()
    val typeFilter     by viewModel.typeFilter.collectAsState()
    val filteredList   by viewModel.filteredListings.collectAsState()
    val context        = LocalContext.current
    val colors         = MaterialTheme.swiftColors

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState is ShopDetailUiState.Success)
                        Text((uiState as ShopDetailUiState.Success).shop.name)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (uiState is ShopDetailUiState.Success) {
                        val shop = (uiState as ShopDetailUiState.Success).shop
                        // Share
                        IconButton(onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                putExtra(Intent.EXTRA_TEXT, "Check out ${shop.name} on SwiftShop!")
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }) {
                            Icon(Icons.Default.Share, "Share")
                        }
                        // Owner: manage shop
                        if (isOwner) {
                            IconButton(onClick = {
                                navController.navigate(Screen.ManageShop.createRoute(shop.id))
                            }) {
                                Icon(Icons.Default.Settings, "Manage Shop")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is ShopDetailUiState.Loading ->
                LoadingState(modifier = Modifier.padding(padding))
            is ShopDetailUiState.Error ->
                ErrorState(state.message, onRetry = { viewModel.load() },
                    modifier = Modifier.padding(padding))
            is ShopDetailUiState.Success -> {
                ShopContent(
                    shop            = state.shop,
                    listings        = filteredList,
                    allListings     = state.listings,
                    padding         = padding,
                    isFollowing     = isFollowing,
                    isFollowLoading = isFollowLoad,
                    isOwner         = isOwner,
                    typeFilter      = typeFilter,
                    onFollowClick   = { viewModel.toggleFollow() },
                    onTypeFilter    = { viewModel.setTypeFilter(it) },
                    onListingClick  = { navController.navigate(Screen.ListingDetail.createRoute(it)) },
                    onLocationClick = {
                        val shop = state.shop
                        if (shop.locationAddress.isNotBlank()) {
                            val uri = Uri.parse("geo:0,0?q=${Uri.encode(shop.locationAddress)}")
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(mapIntent)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ShopContent(
    shop: Shop,
    listings: List<Listing>,
    allListings: List<Listing>,
    padding: PaddingValues,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    isOwner: Boolean,
    typeFilter: ListingType?,
    onFollowClick: () -> Unit,
    onTypeFilter: (ListingType?) -> Unit,
    onListingClick: (String) -> Unit,
    onLocationClick: () -> Unit
) {
    // Build filter tabs from types actually present in listings
    val availableTypes = remember(allListings) {
        allListings.map { it.listingType }.distinct().sorted()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cover + header
        item(span = { GridItemSpan(2) }) {
            ShopHeader(
                shop            = shop,
                isFollowing     = isFollowing,
                isFollowLoading = isFollowLoading,
                isOwner         = isOwner,
                onFollowClick   = onFollowClick,
                onLocationClick = onLocationClick
            )
        }

        // Filter chips row
        if (availableTypes.size > 1) {
            item(span = { GridItemSpan(2) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = typeFilter == null,
                        onClick  = { onTypeFilter(null) },
                        label    = { Text("All (${allListings.size})") }
                    )
                    availableTypes.forEach { type ->
                        val count = allListings.count { it.listingType == type }
                        FilterChip(
                            selected = typeFilter == type,
                            onClick  = { onTypeFilter(if (typeFilter == type) null else type) },
                            label    = { Text("${type.chipLabel()} ($count)") }
                        )
                    }
                }
            }
        }

        // Section label
        item(span = { GridItemSpan(2) }) {
            Text(
                if (typeFilter != null) typeFilter.chipLabel() else "All Listings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (listings.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                EmptyState(
                    title    = "No listings here",
                    subtitle = if (typeFilter != null)
                        "No ${typeFilter.chipLabel()} listings in this shop."
                    else "This shop hasn't added any listings yet.",
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            }
        } else {
            items(listings, key = { it.id }) { listing ->
                ListingCard(listing = listing, onClick = { onListingClick(listing.id) })
            }
        }
    }
}

@Composable
private fun ShopHeader(
    shop: Shop,
    isFollowing: Boolean,
    isFollowLoading: Boolean,
    isOwner: Boolean,
    onFollowClick: () -> Unit,
    onLocationClick: () -> Unit
) {
    val colors = MaterialTheme.swiftColors

    Column(modifier = Modifier.fillMaxWidth()) {

        // Cover image with gradient overlay
        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            AsyncImage(
                model = shop.coverUrl.ifEmpty { null },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // Gradient so logo + text are readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            startY = 80f
                        )
                    )
            )
            // Logo
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .size(76.dp),
                shape = CircleShape,
                tonalElevation = 4.dp,
                border = BorderStroke(2.dp, Color.White)
            ) {
                if (shop.logoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = shop.logoUrl,
                        contentDescription = "Shop logo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary)) {
                        SwiftEntityIcon(
                            entity = SwiftEntity.SHOP,
                            modifier = Modifier.size(38.dp),
                            tint = Color.White
                        )
                    }
                }
            }
            // Verified badge
            if (shop.isVerified) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 76.dp, bottom = 12.dp),
                    shape = CircleShape,
                    color = colors.brandBlue
                ) {
                    Icon(Icons.Default.Verified, "Verified",
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp).size(16.dp))
                }
            }
        }

        // Info section
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

            // Name + follow button row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shop.name, style = MaterialTheme.typography.headlineSmall)
                        if (shop.isVerified) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Verified, null,
                                tint = colors.brandBlue,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(shop.category,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (!isOwner) {
                    Spacer(Modifier.width(12.dp))
                    if (isFollowLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 2.dp)
                    } else {
                        if (isFollowing) {
                            OutlinedButton(
                                onClick = onFollowClick,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.PersonRemove, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Following")
                            }
                        } else {
                            Button(
                                onClick = onFollowClick,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Follow")
                            }
                        }
                    }
                } else {
                    AssistChip(
                        onClick = {},
                        label = { Text("Your Shop") },
                        leadingIcon = { Icon(Icons.Default.Store, null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Stats row
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip(value = shop.listingCount.toString(), label = "Listings")
                StatChip(value = shop.followerCount.toString(), label = "Followers")
                if (shop.rating > 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null,
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("%.1f".format(shop.rating),
                            style = MaterialTheme.typography.labelLarge)
                        if (shop.reviewCount > 0) {
                            Text(" (${shop.reviewCount})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Description
            if (shop.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(shop.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3)
            }

            // Location row
            if (shop.locationAddress.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, null,
                        tint = colors.brandBlue,
                        modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(shop.locationAddress,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onLocationClick,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Directions", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        HorizontalDivider()
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

