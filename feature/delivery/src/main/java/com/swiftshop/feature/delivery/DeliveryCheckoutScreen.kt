package com.swiftshop.feature.delivery

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
import androidx.navigation.NavController
import com.swiftshop.core.model.DeliveryRequest
import com.swiftshop.core.model.GeoPoint
import com.swiftshop.core.ui.components.OsmPinDropMap
import com.swiftshop.core.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryCheckoutScreen(
    navController: NavController,
    viewModel: DeliveryCheckoutViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Arrange Delivery") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val s = state) {
            DeliveryCheckoutUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is DeliveryCheckoutUiState.Error -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = viewModel::loadOptions) { Text("Try Again") }
            }
            is DeliveryCheckoutUiState.Choosing -> ChoosingDelivery(
                state = s,
                onProviderSelected = viewModel::selectProvider,
                onDropoffSelected = viewModel::selectDropoff,
                onSubmit = viewModel::submit
            )
            is DeliveryCheckoutUiState.Waiting -> WaitingForDeliveryProvider(s.request)
            is DeliveryCheckoutUiState.CreatingJob -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            is DeliveryCheckoutUiState.ReadyForTracking -> {
                LaunchedEffect(s.routeId) {
                    navController.navigate(Screen.DeliveryTracking.createRoute(s.routeId)) {
                        popUpTo(Screen.DeliveryCheckout.route) { inclusive = true }
                    }
                }
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun ChoosingDelivery(
    state: DeliveryCheckoutUiState.Choosing,
    onProviderSelected: (String) -> Unit,
    onDropoffSelected: (GeoPoint) -> Unit,
    onSubmit: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Your product purchase is already complete. The delivery fee is taken from your Swift wallet when you " +
                "request delivery and returned in full if the provider declines, the request expires or the delivery fails.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp)
        )

        Text("Choose a delivery provider", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 220.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.options) { option ->
                OutlinedButton(
                    onClick = { onProviderSelected(option.listingId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                        Text(option.title)
                        Text(
                            "${option.price.toDisplayString()} · ${option.deliveryEstimateDays} day estimate",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Text("Select your drop-off point", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Box(Modifier.weight(1f)) {
            OsmPinDropMap(
                initialLocation = state.dropoff,
                modifier = Modifier.fillMaxSize(),
                onLocationSelected = onDropoffSelected
            )
        }

        Button(
            onClick = onSubmit,
            enabled = state.selectedListingId != null && state.dropoff != null && !state.submitting,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("Request Delivery")
        }
    }
}

@Composable
private fun WaitingForDeliveryProvider(request: DeliveryRequest) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text("Waiting for delivery provider", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Your product purchase is not being reversed while the provider decides.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("Status: ${request.status.name.lowercase()}")
    }
}
