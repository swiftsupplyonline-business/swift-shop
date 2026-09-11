package com.swiftshop.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.SwiftShopColors
import com.swiftshop.core.ui.navigation.Screen
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun OrdersScreen(
    navController: NavController,
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentRole by viewModel.currentRole.collectAsState()
    val availableRoles by viewModel.availableRoles.collectAsState()

    val roleLabels = mapOf(
        OrderRole.REQUESTER to "My Requests",
        OrderRole.SELLER to "Orders Received",
        OrderRole.SERVICE_PROVIDER to "Service Bookings",
        OrderRole.DELIVERY_PROVIDER to "Delivery Requests",
        OrderRole.LISTING_AUTHOR to "My Listings",
        OrderRole.RECIPIENT to "To Me",
        OrderRole.ADMIN to "Admin"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Orders", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = availableRoles.indexOf(currentRole).coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 16.dp
            ) {
                availableRoles.forEach { role ->
                    Tab(
                        selected = currentRole == role,
                        onClick = { viewModel.setRole(role) },
                        text = { Text(roleLabels[role] ?: role.name, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }


            when (val state = uiState) {
                is OrdersUiState.Loading -> LoadingState()
                is OrdersUiState.Empty -> EmptyState(
                    "No orders yet",
                    if (currentRole == OrderRole.REQUESTER) 
                        "Your orders will appear here after you make a purchase"
                    else "Orders received from your listings will appear here"
                )
                is OrdersUiState.Error -> ErrorState(state.message, onRetry = { viewModel.load() })
                is OrdersUiState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.orders, key = { it.id }) { order ->
                            OrderCard(
                                order = order,
                                onClick = { navController.navigate(Screen.OrderDetail.createRoute(order.id)) }
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun OrderCard(order: Order, onClick: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Order #${order.id.take(8).uppercase()}",
                    style = MaterialTheme.typography.titleSmall
                )
                OrderStatusBadge(status = order.status)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${order.items.size} item${if (order.items.size > 1) "s" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    order.total.toDisplayString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(Icons.Default.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OrderStatusBadge(status: OrderStatus) {
    val (label, color) = when (status) {
        OrderStatus.PENDING -> "Pending" to SwiftShopColors.Warning
        OrderStatus.RESERVED -> "Reserved" to SwiftShopColors.ElectricBlue
        OrderStatus.PAYMENT_PENDING -> "Awaiting Payment" to SwiftShopColors.Warning
        OrderStatus.CONFIRMED -> "Confirmed" to SwiftShopColors.BrandBlue
        OrderStatus.PROCESSING -> "Processing" to SwiftShopColors.BrandBlue
        OrderStatus.READY -> "Ready" to SwiftShopColors.ElectricBlue
        OrderStatus.DISPATCHED -> "On its way" to SwiftShopColors.BrandBlue
        OrderStatus.DELIVERED -> "Delivered" to SwiftShopColors.Success
        OrderStatus.CANCELLED -> "Cancelled" to SwiftShopColors.Error
        OrderStatus.REFUNDED -> "Refunded" to SwiftShopColors.Warning
        OrderStatus.HOLD -> "On Hold" to SwiftShopColors.Warning
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ─── Order Detail ─────────────────────────────────────────────────────────────

@Composable
fun OrderDetailScreen(
    navController: NavController,
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val detailState by viewModel.detailState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order Details", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = detailState) {
            is OrderDetailState.Loading -> LoadingState()
            is OrderDetailState.Error -> ErrorState(state.message, onRetry = { viewModel.loadDetail() })
            is OrderDetailState.Loaded -> {
                val order = state.order
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status card
                    item {
                        SwiftCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Order #${order.id.take(8).uppercase()}",
                                        style = MaterialTheme.typography.titleMedium)
                                    OrderStatusBadge(status = order.status)
                                }
                                Spacer(Modifier.height(12.dp))
                                OrderProgressBar(status = order.status)
                            }
                        }
                    }

                    // Items
                    item {
                        Text("Items", style = MaterialTheme.typography.titleMedium)
                    }
                    items(order.items, key = { it.listingId }) { item ->
                        SwiftCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    Text("Qty: ${item.quantity}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (item.selectedOptions.isNotEmpty()) {
                                        item.selectedOptions.forEach { (k, v) ->
                                            Text("$k: $v",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                Text(
                                    MoneyAmount("LSL",
                                        item.unitPrice.minorUnits * item.quantity).toDisplayString(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Appointment Details (for SERVICE orders)
                    if (com.swiftshop.domain.commerce.WorkflowMapping.isAppointment(order.type) && order.appointmentStartTime != null) {
                        item {
                            Text("Appointment", style = MaterialTheme.typography.titleMedium)
                        }
                        item {
                            SwiftCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(MaterialTheme.shapes.medium)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SwiftEntityIcon(
                                            entity = SwiftEntity.SERVICE,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        val startTime = order.appointmentStartTime ?: 0L
                                        val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
                                            .format(Date(startTime))
                                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
                                            .format(Date(startTime))
                                        
                                        Text(dateStr, style = MaterialTheme.typography.titleSmall)
                                        Text(timeStr, style = MaterialTheme.typography.bodyMedium, 
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        val statusLabel = when(order.status) {
                                            OrderStatus.CONFIRMED -> "Scheduled"
                                            OrderStatus.PENDING -> "Payment Pending"
                                            OrderStatus.CANCELLED -> "Cancelled"
                                            OrderStatus.HOLD -> "On Hold"
                                            else -> order.status.name.lowercase().replaceFirstChar { it.uppercase() }
                                        }
                                        Text("Status: $statusLabel", style = MaterialTheme.typography.labelLarge,
                                            color = if (order.status == OrderStatus.CONFIRMED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                                    }
                                }
                            }
                        }
                    }

                    // Price breakdown
                    item {
                        SwiftCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Order Summary", style = MaterialTheme.typography.titleMedium)
                                SummaryRow("Subtotal", order.subtotal.toDisplayString())
                                SummaryRow("Delivery", order.deliveryFee.toDisplayString())
                                SummaryRow("Platform fee", order.platformFee.toDisplayString())
                                HorizontalDivider()
                                SummaryRow("Total", order.total.toDisplayString(), isTotal = true)
                            }
                        }
                    }

                    // Order-Type Specific Payloads
                    order.payload?.let { payload ->
                        item {
                            Text("Workflow Details", style = MaterialTheme.typography.titleMedium)
                        }
                        item {
                            SwiftCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    when (payload) {
                                        is OrderPayload.ProductPurchase -> {
                                            if (!payload.buyerNotes.isNullOrBlank()) {
                                                Text("Buyer Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                Text(payload.buyerNotes!!, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        is OrderPayload.FoodOrder -> {
                                            if (!payload.preparationNotes.isNullOrBlank()) {
                                                Text("Preparation Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                Text(payload.preparationNotes!!, style = MaterialTheme.typography.bodyMedium)
                                            }
                                            payload.requestedDeliveryTime?.let {
                                                Text("Requested Time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)), style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                        is OrderPayload.ServiceBooking -> {
                                            Text("Booking Date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(payload.requestedDate, style = MaterialTheme.typography.bodyMedium)
                                            Text("Booking Time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(payload.requestedTime, style = MaterialTheme.typography.bodyMedium)
                                            Text("Duration", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text("${payload.durationMinutes} minutes", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        is OrderPayload.BulkPurchase -> {
                                            Text("Quantity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text("${payload.quantity} ${payload.unitOfMeasure}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        is OrderPayload.DeliveryRequest -> {
                                            Text("Package", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text(payload.packageDescription, style = MaterialTheme.typography.bodyMedium)
                                            Text("Recipient", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                            Text("${payload.recipientName} (${payload.recipientPhone})", style = MaterialTheme.typography.bodyMedium)
                                            if (payload.instructions.isNotBlank()) {
                                                Text("Instructions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                                Text(payload.instructions, style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Delivery address
                    if (com.swiftshop.domain.commerce.WorkflowMapping.needsDeliveryInfo(order.type)) {
                        item {
                            SwiftCard(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Delivery Address", style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(8.dp))
                                    Text(order.deliveryAddress.label,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(order.deliveryAddress.streetHint,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${order.deliveryAddress.city}, ${order.deliveryAddress.country}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    val trackableStatuses = setOf(
                        OrderStatus.CONFIRMED,
                        OrderStatus.PROCESSING,
                        OrderStatus.READY,
                        OrderStatus.DISPATCHED,
                        OrderStatus.DELIVERED
                    )
                    if (order.status in trackableStatuses) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (order.status == OrderStatus.CONFIRMED) {
                                    SwiftPrimaryButton(
                                        text = "Request Delivery",
                                        onClick = {
                                            navController.navigate(Screen.RequestDelivery.createRoute(order.id))
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.LocalShipping, null, modifier = Modifier.size(18.dp))
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                
                                OutlinedButton(
                                    onClick = {
                                        navController.navigate(Screen.TrackOrder.createRoute(order.id))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium
                                ) {
                                    SwiftEntityIcon(
                                        entity = SwiftEntity.DELIVERY,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (order.status == OrderStatus.DELIVERED) "View Delivery" else "Track Order")
                                }
                            }
                        }
                    }
                    if (order.status == OrderStatus.PENDING) {
                        item {
                            OutlinedButton(
                                onClick = { viewModel.cancelOrder(order.id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.Cancel, null, modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text("Cancel Order", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun OrderProgressBar(status: OrderStatus) {
    val steps = listOf("Pending", "Confirmed", "Processing", "Dispatched", "Delivered")
    val statusIndex = when (status) {
        OrderStatus.PENDING, OrderStatus.RESERVED, OrderStatus.PAYMENT_PENDING -> 0
        OrderStatus.CONFIRMED -> 1
        OrderStatus.PROCESSING, OrderStatus.READY, OrderStatus.HOLD -> 2
        OrderStatus.DISPATCHED -> 3
        OrderStatus.DELIVERED -> 4
        else -> -1
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, label ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (i <= statusIndex) SwiftShopColors.BrandBlue
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
            if (i < steps.size - 1) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.weight(0.5f).height(2.dp)
                        .background(
                            if (i < statusIndex) SwiftShopColors.BrandBlue
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, isTotal: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (isTotal) MaterialTheme.typography.titleSmall
        else MaterialTheme.typography.bodyMedium)
        Text(value, style = if (isTotal) MaterialTheme.typography.titleSmall
        else MaterialTheme.typography.bodyMedium,
            color = if (isTotal) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground)
    }
}


