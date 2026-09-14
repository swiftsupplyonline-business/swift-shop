package com.swiftshop.feature.delivery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.swiftshop.core.model.DeliveryRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingDeliveryRequestsScreen(
    navController: NavController,
    viewModel: IncomingDeliveryRequestsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val respondingIds by viewModel.respondingIds.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionError) {
        actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delivery Requests") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val state = uiState) {
            is IncomingDeliveryRequestsUiState.Loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is IncomingDeliveryRequestsUiState.Empty -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LocalShipping, null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("No pending delivery requests", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            is IncomingDeliveryRequestsUiState.Error -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                }
            }
            is IncomingDeliveryRequestsUiState.Loaded -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.requests, key = { it.id }) { request ->
                        DeliveryRequestCard(
                            request = request,
                            isResponding = respondingIds.contains(request.id),
                            onAccept = { viewModel.respond(request.id, accept = true) },
                            onDecline = { viewModel.respond(request.id, accept = false) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryRequestCard(
    request: DeliveryRequest,
    isResponding: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    var remainingSeconds by remember(request.id, request.expiresAt) {
        mutableLongStateOf(((request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }

    LaunchedEffect(request.id, request.expiresAt) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            remainingSeconds = ((request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        }
    }

    val pickupText = request.pickupLabel.ifBlank { "%.4f, %.4f".format(request.pickup.lat, request.pickup.lng) }
    val dropoffText = request.dropoffLabel.ifBlank { "%.4f, %.4f".format(request.dropoff.lat, request.dropoff.lng) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(request.deliveryFee.toDisplayString(), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Pickup: $pickupText", style = MaterialTheme.typography.bodyMedium)
            Text("Dropoff: $dropoffText", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (remainingSeconds > 0) "${remainingSeconds}s left to respond" else "Expiring…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onAccept,
                    enabled = !isResponding && remainingSeconds > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Accept")
                }
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isResponding && remainingSeconds > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Cancel, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Decline")
                }
            }
        }
    }
}
