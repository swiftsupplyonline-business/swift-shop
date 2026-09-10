package com.swiftshop.feature.delivery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.core.ui.theme.swiftColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDeliveryScreen(
    navController: NavController,
    viewModel: RequestDeliveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val destination by viewModel.destination.collectAsState()
    val selectedListing by viewModel.selectedListing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionState) {
        if (actionState is DeliveryRequestActionState.Success) {
            val routeId = (actionState as DeliveryRequestActionState.Success).routeId
            navController.navigate(Screen.DeliveryTracking.createRoute(routeId = routeId)) {
                popUpTo(Screen.RequestDelivery.route) { inclusive = true }
            }
        } else if (actionState is DeliveryRequestActionState.Error) {
            snackbarHostState.showSnackbar((actionState as DeliveryRequestActionState.Error).message)
            viewModel.clearActionState()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Request Delivery") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is RequestDeliveryUiState.Loading -> LoadingState(modifier = Modifier.padding(padding))
            is RequestDeliveryUiState.Error -> ErrorState(state.message, onRetry = { viewModel.load() }, modifier = Modifier.padding(padding))
            is RequestDeliveryUiState.Success -> {
                RequestDeliveryContent(
                    order = state.order,
                    shop = state.shop,
                    listings = state.deliveryListings,
                    destination = destination,
                    selectedListing = selectedListing,
                    actionState = actionState,
                    padding = padding,
                    onListingSelect = viewModel::onListingSelected,
                    onSubmit = viewModel::submitRequest
                )
            }
        }
    }
}

@Composable
private fun RequestDeliveryContent(
    order: Order,
    shop: Shop,
    listings: List<DeliveryListing>,
    destination: GeoPoint?,
    selectedListing: DeliveryListing?,
    actionState: DeliveryRequestActionState,
    padding: PaddingValues,
    onListingSelect: (DeliveryListing) -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Pickup (Shop) ---
        Text(
            "1. Pickup Location",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        val origin = order.originLocationSnapshot
        SwiftCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Store, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(shop.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        origin?.addressSnapshot ?: shop.locationAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- 2. Destination (Immutable Purchased Location) ---
        Text(
            "2. Delivery Destination",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        val shopMarker = remember(order) {
            order.originLocationSnapshot?.let {
                MapMarker(
                    id = "shop_origin",
                    position = it.toGeoPoint(),
                    title = shop.name,
                    snippet = "Pickup Location",
                    entityType = SwiftEntity.SHOP
                )
            }
        }
        val purchasedDest = remember(order) {
            order.destinationLocationSnapshot?.let {
                MapMarker(
                    id = "purchased_dest",
                    position = it.toGeoPoint(),
                    title = "Purchased Destination",
                    snippet = it.addressSnapshot,
                    entityType = SwiftEntity.PIN
                )
            }
        }
        
        Box(modifier = Modifier.padding(horizontal = 16.dp).height(300.dp).clip(MaterialTheme.shapes.large)) {
            val mapState = rememberMapState(initialCenter = destination ?: shop.location)
            SwiftMapView(
                state = mapState,
                markers = listOfNotNull(shopMarker, purchasedDest),
                modifier = Modifier.fillMaxSize()
            )
            
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                tonalElevation = 2.dp
            ) {
                Text(
                    "Logistics locked to purchased destination",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // --- 3. Delivery Option ---
        if (listings.isNotEmpty()) {
            Text(
                "3. Purchased Provider",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            listings.forEach { listing ->
                val isPurchased = listing.id == order.selectedDeliveryListingId
                val displayPrice = if (isPurchased) {
                    order.deliveryListingSnapshot?.let { 
                        MoneyAmount(it.currency, it.priceMinorUnits) 
                    } ?: listing.price
                } else {
                    listing.price
                }

                DeliveryOptionCard(
                    listing = listing,
                    isSelected = selectedListing?.id == listing.id,
                    isPurchased = isPurchased,
                    displayPrice = displayPrice,
                    onSelect = { if (isPurchased) onListingSelect(listing) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- Submit ---
        SwiftPrimaryButton(
            text = "Request Delivery",
            isLoading = actionState is DeliveryRequestActionState.Loading,
            enabled = selectedListing?.id == order.selectedDeliveryListingId && actionState !is DeliveryRequestActionState.Loading,
            onClick = onSubmit,
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        )
    }
}

@Composable
private fun DeliveryOptionCard(
    listing: DeliveryListing,
    isSelected: Boolean,
    isPurchased: Boolean,
    displayPrice: MoneyAmount,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary 
        isPurchased -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    
    val cardAlpha = if (isPurchased) 1f else 0.5f

    SwiftCard(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, MaterialTheme.shapes.medium)
            .alpha(cardAlpha)
            .clickable(enabled = isPurchased) { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SwiftAvatar(
                url = listing.imageUrl.ifBlank { listing.providerAvatarUrl },
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listing.title, style = MaterialTheme.typography.titleSmall)
                    if (isPurchased) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.Verified, "Paid", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Text(listing.providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                displayPrice.toDisplayString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
