package com.swiftshop.feature.search

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.core.ui.theme.swiftColors

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Search bar row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back")
            }
            SwiftSearchBar(
                query = query,
                onQueryChange = { viewModel.onQueryChange(it) },
                modifier = Modifier.weight(1f).focusRequester(focusRequester)
            )
        }

        // Results / recent
        when (val state = results) {
            is SearchState.Idle -> {
                RecentSearches(
                    recent = recentSearches,
                    onRecentClick = { viewModel.onQueryChange(it) },
                    onClearAll = { viewModel.clearRecentSearches() }
                )
            }
            is SearchState.Loading -> LoadingState()
            is SearchState.Empty -> EmptyState(
                "No results for \"$query\"",
                "Try different keywords or check spelling"
            )
            is SearchState.Results -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.users.isNotEmpty()) {
                        item {
                            SearchSectionHeader("People")
                        }
                        items(state.users, key = { it.uid }) { user ->
                            UserSearchItem(user = user, onClick = { onNavigate(Screen.UserProfile.createRoute(user.uid)) })
                        }
                    }

                    if (state.listings.isNotEmpty()) {
                        item { SearchSectionHeader("Listings") }
                        items(state.listings, key = { it.id }) { listing ->
                            ListingSearchItem(
                                listing = listing,
                                onClick = { onNavigate(Screen.ListingDetail.createRoute(listing.id)) }
                            )
                        }
                    }

                    if (state.shops.isNotEmpty()) {
                        item { SearchSectionHeader("Shops") }
                        items(state.shops, key = { it.id }) { shop ->
                            ShopSearchItem(
                                shop = shop,
                                onClick = { onNavigate(Screen.ShopDetail.createRoute(shop.id)) }
                            )
                        }
                    }

                    if (state.posts.isNotEmpty()) {
                        item { SearchSectionHeader("Posts") }
                        items(state.posts, key = { it.id }) { post ->
                            PostSearchItem(
                                post = post,
                                onClick = { onNavigate(Screen.PostDetail.createRoute(post.id)) }
                            )
                        }
                    }
                }
            }
            is SearchState.Error -> ErrorState(state.message, onRetry = { viewModel.retry() })
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun RecentSearches(
    recent: List<String>,
    onRecentClick: (String) -> Unit,
    onClearAll: () -> Unit
) {
    if (recent.isEmpty()) {
        EmptyState("Search Swift Shop", "Find users, shops, listings, and more")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClearAll) { Text("Clear all") }
            }
        }
        items(recent) { term ->
            ListItem(
                headlineContent = { Text(term) },
                leadingContent = {
                    Icon(Icons.Default.History, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    Icon(Icons.Default.NorthWest, null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onRecentClick(term) }
            )
        }
    }
}

@Composable
private fun UserSearchItem(user: User, onClick: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SwiftAvatar(url = user.photoUrl, tier = user.tier)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(user.displayName, style = MaterialTheme.typography.titleSmall)
                if (user.isVerified) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Verified, null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.swiftColors.brandBlue)
                        Spacer(Modifier.width(4.dp))
                        Text("Verified", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.swiftColors.brandBlue)
                    }
                }
            }
            TierBadge(tier = user.tier, size = 20.dp)
        }
    }
}

@Composable
private fun ListingSearchItem(listing: Listing, onClick: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = listing.imageUrls.firstOrNull(),
                contentDescription = listing.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(listing.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(listing.price.toDisplayString(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShopSearchItem(shop: Shop, onClick: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = shop.logoUrl,
                contentDescription = shop.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.large)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(shop.name, style = MaterialTheme.typography.titleSmall)
                Text("${shop.category} · ${shop.listingCount} listings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.swiftColors.warning)
                Text("${shop.rating}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PostSearchItem(post: FeedPost, onClick: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (post.mediaUrls.isNotEmpty()) {
                AsyncImage(
                    model = post.mediaUrls.first(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(post.authorName, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(post.caption, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

// Extend swiftColors with warning
private val MaterialTheme.swiftColors: com.swiftshop.core.ui.theme.SwiftShopExtendedColors
    @Composable get() = com.swiftshop.core.ui.theme.LocalSwiftShopColors.current

private val com.swiftshop.core.ui.theme.SwiftShopExtendedColors.warning
    get() = com.swiftshop.core.ui.theme.SwiftShopColors.Warning
