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
                    onDestinationSelect = viewModel::onDestinationSelected,
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
    onDestinationSelect: (GeoPoint) -> Unit,
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
                    Text(shop.locationAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // --- 2. Destination ---
        Text(
            "2. Set Destination",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        val shopMarker = remember(shop) {
            MapMarker(
                id = "shop_origin",
                position = shop.location,
                title = shop.name,
                snippet = "Pickup Location",
                entityType = SwiftEntity.SHOP
            )
        }
        DropYourPinComponent(
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            initialLocation = destination,
            markers = listOf(shopMarker),
            onLocationConfirmed = onDestinationSelect,
            isConfirmed = destination != null
        )

        // --- 3. Delivery Option ---
        if (listings.isNotEmpty()) {
            Text(
                "3. Choose Provider",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            listings.forEach { listing ->
                DeliveryOptionCard(
                    listing = listing,
                    isSelected = selectedListing?.id == listing.id,
                    onSelect = { onListingSelect(listing) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // --- Submit ---
        SwiftPrimaryButton(
            text = "Request Delivery",
            isLoading = actionState is DeliveryRequestActionState.Loading,
            enabled = destination != null && selectedListing != null && actionState !is DeliveryRequestActionState.Loading,
            onClick = onSubmit,
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        )
    }
}

@Composable
private fun DeliveryOptionCard(
    listing: DeliveryListing,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary 
                     else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    
    SwiftCard(
        modifier = modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable { onSelect() }
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
                Text(listing.title, style = MaterialTheme.typography.titleSmall)
                Text(listing.providerName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                listing.price.toDisplayString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
