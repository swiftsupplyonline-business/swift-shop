package com.swiftshop.feature.orders

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

@Composable
fun OrdersScreen(
    navController: NavController,
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Orders", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is OrdersUiState.Loading -> LoadingState()
            is OrdersUiState.Empty -> EmptyState(
                "No orders yet",
                "Your orders will appear here after you make a purchase"
            )
            is OrdersUiState.Error -> ErrorState(state.message, onRetry = { viewModel.load() })
            is OrdersUiState.Loaded -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
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
        OrderStatus.CONFIRMED -> "Confirmed" to SwiftShopColors.BrandBlue
        OrderStatus.PROCESSING -> "Processing" to SwiftShopColors.BrandBlue
        OrderStatus.READY -> "Ready" to SwiftShopColors.ElectricBlue
        OrderStatus.DISPATCHED -> "On its way" to SwiftShopColors.BrandBlue
        OrderStatus.DELIVERED -> "Delivered" to SwiftShopColors.Success
        OrderStatus.CANCELLED -> "Cancelled" to SwiftShopColors.Error
        OrderStatus.REFUNDED -> "Refunded" to SwiftShopColors.Warning
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .run {
                background(color.copy(alpha = 0.15f))
            }
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

                    // Price breakdown
                    item {
                        SwiftCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Order Summary", style = MaterialTheme.typography.titleMedium)
                                SummaryRow("Subtotal", order.subtotal.toDisplayString())
                                SummaryRow("Delivery", order.deliveryFee.toDisplayString())
                                SummaryRow("Platform fee", order.platformFee.toDisplayString())
                                Divider()
                                SummaryRow("Total", order.total.toDisplayString(), isTotal = true)
                            }
                        }
                    }

                    // Delivery address
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

                    // Actions
                    if (order.status == OrderStatus.DISPATCHED) {
                        item {
                            SwiftPrimaryButton(
                                text = "Track Delivery",
                                onClick = {
                                    navController.navigate(Screen.DeliveryTracking.createRoute(order.id))
                                },
                                leadingIcon = { Icon(Icons.Default.LocalShipping, null,
                                    modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.fillMaxWidth()
                            )
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
        OrderStatus.PENDING -> 0
        OrderStatus.CONFIRMED -> 1
        OrderStatus.PROCESSING, OrderStatus.READY -> 2
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
                        .run {
                            background(
                                if (i <= statusIndex) SwiftShopColors.BrandBlue
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                )
            }
            if (i < steps.size - 1) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.weight(0.5f).height(2.dp).run {
                        background(
                            if (i < statusIndex) SwiftShopColors.BrandBlue
                            else MaterialTheme.colorScheme.outline
                        )
                    }
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

private fun Modifier.background(color: Color): Modifier =
    this.then(Modifier.background(color))
