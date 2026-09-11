package com.swiftshop.feature.delivery

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.core.model.DeliveryStatus
import com.swiftshop.core.model.GeoPoint
import com.swiftshop.core.model.MapMarker
import com.swiftshop.core.model.MapPolyline
import com.swiftshop.core.model.SwiftEntity
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.core.ui.theme.SwiftShopColors

@Composable
fun DeliveryTrackingScreen(
    navController: NavController,
    viewModel: DeliveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track Delivery", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is DeliveryUiState.Loading -> LoadingState()
            is DeliveryUiState.Error -> ErrorState(state.message, onRetry = { viewModel.load() })
            is DeliveryUiState.Tracking -> {
                val route = state.route
                val markers = remember(route) {
                    val list = mutableListOf<MapMarker>()
                    if (route.pickupLocation.lat != 0.0) {
                        list.add(MapMarker("pickup", route.pickupLocation, "Pickup", entityType = SwiftEntity.SHOP))
                    }
                    if (route.dropoffLocation.lat != 0.0) {
                        list.add(MapMarker("destination", route.dropoffLocation, "Destination", entityType = SwiftEntity.BUYER))
                    }
                    route.driverCurrentLocation?.let {
                        list.add(MapMarker("driver", it, "Driver", entityType = SwiftEntity.PROVIDER))
                    }
                    list
                }
                
                val polylines = remember(route) {
                    val points = mutableListOf<GeoPoint>()
                    if (route.pickupLocation.lat != 0.0) points.add(route.pickupLocation)
                    route.driverCurrentLocation?.let { points.add(it) }
                    if (route.dropoffLocation.lat != 0.0) points.add(route.dropoffLocation)
                    
                    if (points.size >= 2) {
                        listOf(MapPolyline("route", points))
                    } else emptyList()
                }

                val mapState = rememberMapState(
                    initialCenter = route.driverCurrentLocation 
                        ?: route.pickupLocation.takeIf { it.lat != 0.0 } 
                        ?: GeoPoint(-29.3167, 27.4833)
                )

                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // consolidated Map
                    Box(modifier = Modifier.weight(1f)) {
                        SwiftMapView(
                            state = mapState,
                            markers = markers,
                            polylines = polylines,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Driver ETA overlay
                        if (state.route.estimatedMinutes > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("ETA", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${state.route.estimatedMinutes} min",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // Status panel
                    DeliveryStatusPanel(
                        route = state.route,
                        onMessage = { navController.navigate(Screen.Conversation.createRoute(state.route.conversationId)) }
                    )
                }
            }
        }
    }
}

// ─── Status Panel ─────────────────────────────────────────────────────────────

@Composable
private fun DeliveryStatusPanel(route: DeliveryRoute, onMessage: () -> Unit) {
    Surface(tonalElevation = 8.dp) {
        Column(modifier = Modifier.padding(16.dp).navigationBarsPadding()) {

            // Status steps
            val steps = listOf(
                DeliveryStatus.REQUESTED to "Order Placed",
                DeliveryStatus.ASSIGNED to "Driver Assigned",
                DeliveryStatus.PICKUP to "Driver at Pickup",
                DeliveryStatus.IN_TRANSIT to "On the Way",
                DeliveryStatus.DELIVERED to "Delivered"
            )
            val currentIndex = steps.indexOfFirst { it.first == route.status }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.forEachIndexed { i, (_, label) ->
                    val isCompleted = i <= currentIndex
                    val isCurrent = i == currentIndex
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isCurrent) 12.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isCurrent -> SwiftShopColors.BrandBlue
                                        isCompleted -> SwiftShopColors.Success
                                        else -> MaterialTheme.colorScheme.outline
                                    }
                                )
                        )
                        if (isCurrent) {
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                color = SwiftShopColors.BrandBlue)
                        }
                    }
                    if (i < steps.size - 1) {
                        Box(
                            modifier = Modifier
                                .weight(0.3f)
                                .height(2.dp)
                                .background(
                                    if (i < currentIndex) SwiftShopColors.Success
                                    else MaterialTheme.colorScheme.outline
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Distance: ${"%.1f".format(route.distanceMeters / 1000)} km",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("Order #${route.orderId.take(8).uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Message driver button
                OutlinedButton(
                    onClick = onMessage,
                    shape = MaterialTheme.shapes.medium,
                    enabled = route.conversationId.isNotEmpty()
                ) {
                    Icon(Icons.Default.Message, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Message Driver")
                }
            }
        }
    }
}
