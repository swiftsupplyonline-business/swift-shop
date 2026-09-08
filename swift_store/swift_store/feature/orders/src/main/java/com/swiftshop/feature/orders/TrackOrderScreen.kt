package com.swiftshop.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.core.ui.theme.SwiftShopColors
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.delivery.DeliveryRepository
import com.swiftshop.domain.delivery.DeliveryRole
import com.swiftshop.domain.delivery.ObserveDeliveryRoutesByOrderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ─── UI State ────────────────────────────────────────────────────────────────

sealed interface TrackOrderUiState {
    data object Loading : TrackOrderUiState
    data class Error(val message: String) : TrackOrderUiState
    data class Loaded(
        val order: Order,
        val route: DeliveryRoute?,
        val viewerRole: TrackingRole,
        val mapMarkers: List<MapMarker>,
        val mapPolylines: List<MapPolyline>
    ) : TrackOrderUiState
}

/**
 * Role of the currently authenticated user in the context of this order.
 * Determines which UI elements and which map markers are shown.
 */
enum class TrackingRole { BUYER, SELLER, DRIVER, ADMIN }

// ─── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class TrackOrderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val commerceRepository: CommerceRepository,
    private val deliveryRepository: DeliveryRepository,
    private val observeRoutesByOrder: ObserveDeliveryRoutesByOrderUseCase
) : ViewModel() {

    val orderId: String = checkNotNull(savedStateHandle["orderId"])

    private val _uiState = MutableStateFlow<TrackOrderUiState>(TrackOrderUiState.Loading)
    val uiState: StateFlow<TrackOrderUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = TrackOrderUiState.Loading
            observeCurrentUser().filterNotNull().flatMapLatest { user ->
                // Load order once, then combine with live route stream
                val orderResult = commerceRepository.getOrder(orderId)
                if (orderResult.isFailure) {
                    _uiState.value = TrackOrderUiState.Error(
                        orderResult.exceptionOrNull()?.message ?: "Order not found"
                    )
                    return@flatMapLatest emptyFlow()
                }
                val order = orderResult.getOrThrow()

                // Determine this user's role in the order
                val role = when {
                    user.uid == order.buyerId -> TrackingRole.BUYER
                    user.uid == order.sellerId -> TrackingRole.SELLER
                    else -> TrackingRole.BUYER // driver role determined from route
                }

                // Observe live delivery route if one exists
                val deliveryRole = when (role) {
                    TrackingRole.SELLER -> DeliveryRole.SELLER
                    TrackingRole.DRIVER -> DeliveryRole.DRIVER
                    else -> DeliveryRole.BUYER
                }

                observeRoutesByOrder(orderId, user.uid, deliveryRole)
                    .map { routes ->
                        val route = selectActiveRoute(routes)

                        // Resolve effective role — could be driver
                        val effectiveRole = when {
                            route != null && route.driverId == user.uid -> TrackingRole.DRIVER
                            else -> role
                        }

                        val markers = buildMarkers(order, route, effectiveRole)
                        val polylines = buildPolylines(order, route)

                        TrackOrderUiState.Loaded(
                            order = order,
                            route = route,
                            viewerRole = effectiveRole,
                            mapMarkers = markers,
                            mapPolylines = polylines
                        )
                    }
                    .catch {
                        // Route collection may not exist yet (pre-dispatch) — that's OK
                        val markers = buildMarkersFromOrder(order)
                        emit(
                            TrackOrderUiState.Loaded(
                                order = order,
                                route = null,
                                viewerRole = role,
                                mapMarkers = markers,
                                mapPolylines = emptyList()
                            )
                        )
                    }
            }.collect { _uiState.value = it }
        }
    }

    private fun selectActiveRoute(routes: List<DeliveryRoute>): DeliveryRoute? {
        val inProgress = listOf(
            DeliveryStatus.ASSIGNED, DeliveryStatus.PICKUP, DeliveryStatus.IN_TRANSIT
        )
        return routes.find { it.status in inProgress }
            ?: routes.find { it.status == DeliveryStatus.REQUESTED }
            ?: routes.maxByOrNull { it.createdAt }
    }

    /**
     * Build map markers contextually for the viewer's role.
     * - BUYER sees: their pin (destination) + shop pin (origin) + driver pin
     * - SELLER sees: buyer pin + their shop pin + driver pin
     * - DRIVER sees: shop pin (go pick up) + buyer pin (deliver to) + own pin
     */
    private fun buildMarkers(
        order: Order,
        route: DeliveryRoute?,
        role: TrackingRole
    ): List<MapMarker> {
        val markers = mutableListOf<MapMarker>()

        // Shop / pickup location
        val shopGeo = order.originLocationSnapshot?.toGeoPoint()
            ?: route?.pickupLocation?.takeIf { it.isValid() }
        if (shopGeo != null && shopGeo.isValid()) {
            markers.add(
                MapMarker(
                    id = "shop_pin",
                    position = shopGeo,
                    title = if (role == TrackingRole.DRIVER) "📦 Pickup Here" else "🏪 Shop",
                    snippet = "Pickup location",
                    entityType = SwiftEntity.SHOP
                )
            )
        }

        // Buyer / destination location
        val buyerGeo = order.destinationLocationSnapshot?.toGeoPoint()
            ?: route?.dropoffLocation?.takeIf { it.isValid() }
        if (buyerGeo != null && buyerGeo.isValid()) {
            val buyerLabel = when (role) {
                TrackingRole.BUYER -> "📍 Your Location"
                TrackingRole.DRIVER -> "🏠 Deliver Here"
                else -> "🏠 Buyer Location"
            }
            markers.add(
                MapMarker(
                    id = "buyer_pin",
                    position = buyerGeo,
                    title = buyerLabel,
                    snippet = order.destinationLocationSnapshot?.addressSnapshot
                        ?: order.deliveryAddress.run { "$streetHint, $city" },
                    entityType = SwiftEntity.BUYER
                )
            )
        }

        // Driver live location (when route is active)
        route?.driverCurrentLocation?.takeIf { it.isValid() }?.let { driverGeo ->
            markers.add(
                MapMarker(
                    id = "driver_pin",
                    position = driverGeo,
                    title = "🛵 Driver",
                    snippet = "En route",
                    entityType = SwiftEntity.PROVIDER
                )
            )
        }

        return markers
    }

    private fun buildMarkersFromOrder(order: Order): List<MapMarker> =
        buildMarkers(order, null, TrackingRole.BUYER)

    private fun buildPolylines(order: Order, route: DeliveryRoute?): List<MapPolyline> {
        val points = mutableListOf<GeoPoint>()

        val shopGeo = order.originLocationSnapshot?.toGeoPoint()
            ?: route?.pickupLocation?.takeIf { it.isValid() }
        if (shopGeo != null && shopGeo.isValid()) points.add(shopGeo)

        route?.driverCurrentLocation?.takeIf { it.isValid() }?.let { points.add(it) }

        val buyerGeo = order.destinationLocationSnapshot?.toGeoPoint()
            ?: route?.dropoffLocation?.takeIf { it.isValid() }
        if (buyerGeo != null && buyerGeo.isValid()) points.add(buyerGeo)

        return if (points.size >= 2) {
            listOf(MapPolyline(id = "delivery_route", points = points))
        } else emptyList()
    }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOrderScreen(
    navController: NavController,
    viewModel: TrackOrderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Track Order", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Refresh button
                    IconButton(onClick = { viewModel.load() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is TrackOrderUiState.Loading -> LoadingState(
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            is TrackOrderUiState.Error -> ErrorState(
                message = state.message,
                onRetry = { viewModel.load() },
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            is TrackOrderUiState.Loaded -> TrackOrderContent(
                state = state,
                padding = padding,
                onMessageDriver = { conversationId ->
                    navController.navigate(Screen.Conversation.createRoute(conversationId))
                },
                onViewOrderDetail = {
                    navController.navigate(Screen.OrderDetail.createRoute(state.order.id))
                }
            )
        }
    }
}

@Composable
private fun TrackOrderContent(
    state: TrackOrderUiState.Loaded,
    padding: PaddingValues,
    onMessageDriver: (String) -> Unit,
    onViewOrderDetail: () -> Unit
) {
    // Pick initial map center: prefer driver → buyer → shop → Maseru
    val mapCenter = remember(state.mapMarkers) {
        state.route?.driverCurrentLocation?.takeIf { it.isValid() }
            ?: state.order.destinationLocationSnapshot?.toGeoPoint()?.takeIf { it.isValid() }
            ?: state.order.originLocationSnapshot?.toGeoPoint()?.takeIf { it.isValid() }
            ?: GeoPoint(-29.3167, 27.4833)
    }
    val mapState = rememberMapState(initialCenter = mapCenter, initialZoom = 14.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        // ── Role badge ──────────────────────────────────────────────────────
        RoleBanner(role = state.viewerRole)

        // ── Map ─────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            SwiftMapView(
                state = mapState,
                markers = state.mapMarkers,
                polylines = state.mapPolylines,
                modifier = Modifier.fillMaxSize()
            )

            // ETA chip
            state.route?.let { route ->
                if (route.estimatedMinutes > 0 &&
                    route.status in listOf(
                        DeliveryStatus.ASSIGNED,
                        DeliveryStatus.PICKUP,
                        DeliveryStatus.IN_TRANSIT
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.93f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "ETA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${route.estimatedMinutes} min",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Map legend — always visible so each party knows what each pin means
            MapLegend(
                role = state.viewerRole,
                hasDriver = state.route?.driverCurrentLocation?.isValid() == true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            )
        }

        // ── Status + info panel ─────────────────────────────────────────────
        Surface(tonalElevation = 8.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Delivery status timeline
                state.route?.let { route ->
                    DeliveryTimeline(status = route.status)
                } ?: PendingDispatchNote(order = state.order)

                // Order reference
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Order #${state.order.id.take(8).uppercase()}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            state.order.total.toDisplayString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Message driver (when route exists and has conversation)
                        state.route?.conversationId?.takeIf { it.isNotEmpty() }?.let { convId ->
                            OutlinedButton(
                                onClick = { onMessageDriver(convId) },
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(
                                    Icons.Default.Message, null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Driver")
                            }
                        }

                        // View full order detail
                        OutlinedButton(
                            onClick = onViewOrderDetail,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(
                                Icons.Default.Receipt, null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Details")
                        }
                    }
                }

                // Delivery address summary
                state.order.destinationLocationSnapshot?.let { dest ->
                    if (dest.addressSnapshot.isNotEmpty() || dest.instructions.isNotEmpty()) {
                        SwiftCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.LocationOn, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Delivery Address",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                if (dest.addressSnapshot.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        dest.addressSnapshot,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (dest.instructions.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "📝 ${dest.instructions}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Role Banner ─────────────────────────────────────────────────────────────

@Composable
private fun RoleBanner(role: TrackingRole) {
    val (label, color, icon) = when (role) {
        TrackingRole.BUYER -> Triple("Tracking your order", SwiftShopColors.BrandBlue, Icons.Default.ShoppingBag)
        TrackingRole.SELLER -> Triple("Seller view", SwiftShopColors.ElectricBlue, Icons.Default.Storefront)
        TrackingRole.DRIVER -> Triple("Driver view — active delivery", SwiftShopColors.Success, Icons.Default.DirectionsBike)
        TrackingRole.ADMIN -> Triple("Admin view", SwiftShopColors.Warning, Icons.Default.AdminPanelSettings)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

// ─── Map Legend ──────────────────────────────────────────────────────────────

@Composable
private fun MapLegend(
    role: TrackingRole,
    hasDriver: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.93f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LegendItem(
            label = if (role == TrackingRole.DRIVER) "Pickup" else "Shop",
            color = SwiftShopColors.BrandBlue
        )
        LegendItem(
            label = if (role == TrackingRole.DRIVER) "Deliver to" else "Your location",
            color = SwiftShopColors.ElectricBlue
        )
        if (hasDriver) {
            LegendItem(label = "Driver", color = SwiftShopColors.Success)
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ─── Delivery Timeline ───────────────────────────────────────────────────────

@Composable
private fun DeliveryTimeline(status: DeliveryStatus) {
    val steps = listOf(
        DeliveryStatus.REQUESTED to "Order Placed",
        DeliveryStatus.ASSIGNED to "Driver Assigned",
        DeliveryStatus.PICKUP to "Driver at Shop",
        DeliveryStatus.IN_TRANSIT to "On the Way",
        DeliveryStatus.DELIVERED to "Delivered"
    )
    val currentIndex = steps.indexOfFirst { it.first == status }.coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Delivery Status", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { i, (_, label) ->
                val isComplete = i < currentIndex
                val isCurrent = i == currentIndex
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isCurrent) 14.dp else 9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCurrent -> SwiftShopColors.BrandBlue
                                    isComplete -> SwiftShopColors.Success
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                    )
                    if (isCurrent) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = SwiftShopColors.BrandBlue
                        )
                    }
                }
                if (i < steps.size - 1) {
                    Box(
                        modifier = Modifier
                            .weight(0.5f)
                            .height(2.dp)
                            .background(
                                if (i < currentIndex) SwiftShopColors.Success
                                else MaterialTheme.colorScheme.outline
                            )
                    )
                }
            }
        }
    }
}

// ─── Pending Dispatch Note ────────────────────────────────────────────────────

@Composable
private fun PendingDispatchNote(order: Order) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Schedule, null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
        Column {
            Text(
                "Awaiting dispatch",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                "Order status: ${order.status.name.lowercase().replaceFirstChar { it.uppercase() }}. " +
                        "Your pins are confirmed. A driver will be assigned once the seller dispatches.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
