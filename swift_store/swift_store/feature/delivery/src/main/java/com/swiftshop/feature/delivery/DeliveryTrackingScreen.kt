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
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.core.ui.theme.SwiftShopColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

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
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // OSM Map
                    Box(modifier = Modifier.weight(1f)) {
                        OsmMapView(
                            route = state.route,
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

// ─── OSM Map View ─────────────────────────────────────────────────────────────

@Composable
fun OsmMapView(route: DeliveryRoute, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(14.0)

                // Set initial center to Maseru, Lesotho if no route
                val center = route.pickupLocation.takeIf { it.lat != 0.0 }
                    ?: GeoPoint(-29.3167, 27.4833) // Maseru default
                controller.setCenter(OsmGeoPoint(center.lat, center.lng))

                // Add pickup marker
                if (route.pickupLocation.lat != 0.0) {
                    val pickupMarker = Marker(this).apply {
                        position = OsmGeoPoint(route.pickupLocation.lat, route.pickupLocation.lng)
                        title = "Pickup"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    overlays.add(pickupMarker)
                }

                // Add dropoff marker
                if (route.dropoffLocation.lat != 0.0) {
                    val dropoffMarker = Marker(this).apply {
                        position = OsmGeoPoint(route.dropoffLocation.lat, route.dropoffLocation.lng)
                        title = "Destination"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    overlays.add(dropoffMarker)
                }

                // Add driver marker if tracking
                route.driverCurrentLocation?.let { driverLoc ->
                    val driverMarker = Marker(this).apply {
                        position = OsmGeoPoint(driverLoc.lat, driverLoc.lng)
                        title = "Driver"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    overlays.add(driverMarker)
                }
            }
        },
        update = { mapView ->
            // Update driver location marker when position changes
            route.driverCurrentLocation?.let { loc ->
                mapView.controller.animateTo(OsmGeoPoint(loc.lat, loc.lng))
            }
        },
        modifier = modifier
    )
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
