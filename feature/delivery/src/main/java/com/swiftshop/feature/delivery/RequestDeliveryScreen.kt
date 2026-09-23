package com.swiftshop.feature.delivery

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.swiftshop.core.model.DeliveryRequestStatus
import com.swiftshop.core.ui.components.OsmPinDropMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestDeliveryScreen(
    navController: NavController,
    viewModel: RequestDeliveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
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
            is RequestDeliveryUiState.Loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is RequestDeliveryUiState.Error -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is RequestDeliveryUiState.Picking -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Step 1: Tap the dropoff point",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        if (state.shop != null) {
                            Text(
                                "Requesting from ${state.shop.name} — ${state.listing.price.toDisplayString()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        OsmPinDropMap(
                            initialLocation = state.dropoff,
                            modifier = Modifier.fillMaxSize(),
                            onLocationSelected = { viewModel.onDropoffSelected(it) }
                        )
                    }

                    Surface(tonalElevation = 4.dp) {
                        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Button(
                                onClick = { viewModel.submitRequest() },
                                enabled = state.dropoff != null && !state.isSubmitting,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (state.isSubmitting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("Send Request")
                                }
                            }
                        }
                    }
                }
            }
            is RequestDeliveryUiState.Waiting -> {
                WaitingForResponse(
                    state = state,
                    onTryAnother = { viewModel.startOver() },
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun WaitingForResponse(
    state: RequestDeliveryUiState.Waiting,
    onTryAnother: () -> Unit,
    onDone: () -> Unit
) {
    var remainingSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.request.expiresAt, state.request.status) {
        while (state.request.status == DeliveryRequestStatus.PENDING) {
            remainingSeconds = ((state.request.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state.request.status) {
            DeliveryRequestStatus.PENDING -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Waiting for ${state.shop?.name ?: "the merchant"} to respond…",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("${remainingSeconds}s left", style = MaterialTheme.typography.bodyLarge)
            }
            DeliveryRequestStatus.ACCEPTED -> {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("Request accepted!", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("We'll notify you shortly to complete payment.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone) { Text("Done") }
            }
            DeliveryRequestStatus.DECLINED -> {
                Icon(Icons.Default.Cancel, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("This provider declined your request", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onTryAnother) { Text("Choose another provider") }
            }
            DeliveryRequestStatus.EXPIRED -> {
                Icon(Icons.Default.Cancel, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("No response in time", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("This provider didn't respond within 2 minutes.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onTryAnother) { Text("Choose another provider") }
            }
            DeliveryRequestStatus.CANCELLED -> {
                Text("Request cancelled", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
