package com.swiftshop.feature.checkout

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.domain.commerce.CartItem
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

// ─── Step definition ──────────────────────────────────────────────────────────
//
// Delivery-first sequence:
//   CART (choose provider) → AWAITING_DELIVERY → ADDRESS → PAYMENT → [verification] → CONFIRMATION
//
// Non-delivery sequence:
//   CART → PAYMENT → [verification] → CONFIRMATION
//
enum class CheckoutStep {
    CART,
    AWAITING_DELIVERY,
    ADDRESS,
    PAYMENT,
    VERIFICATION,
    CONFIRMATION
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    navController: NavController,
    sessionId: String? = null,
    viewModel: CheckoutViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val hasCartItems by viewModel.hasCartItems.collectAsState()
    val cartItems by viewModel.cartItems.collectAsState()
    val acceptedDelivery by viewModel.acceptedDelivery.collectAsState()

    var step by remember { mutableStateOf(CheckoutStep.CART) }
    var showClearCartConfirm by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    // Handle return from MoPay deep-link
    LaunchedEffect(sessionId) {
        if (sessionId != null) viewModel.verifyPayment(sessionId)
    }

    // React to VM state transitions
    LaunchedEffect(uiState) {
        when (uiState) {
            is CheckoutUiState.OrderPlaced -> step = CheckoutStep.CONFIRMATION
            is CheckoutUiState.AwaitingPayment -> {
                val state = uiState as CheckoutUiState.AwaitingPayment
                step = CheckoutStep.VERIFICATION
                uriHandler.openUri(state.paymentUrl)
            }
            is CheckoutUiState.AwaitingDeliveryAcceptance -> step = CheckoutStep.AWAITING_DELIVERY
            is CheckoutUiState.CartLoaded -> {
                // If we were waiting and delivery was accepted, advance automatically.
                if (step == CheckoutStep.AWAITING_DELIVERY && acceptedDelivery != null) {
                    step = CheckoutStep.ADDRESS
                }
                // If delivery was declined/cancelled, return to CART.
                if (step == CheckoutStep.AWAITING_DELIVERY && acceptedDelivery == null) {
                    step = CheckoutStep.CART
                }
            }
            else -> {}
        }
    }

    if (showClearCartConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCartConfirm = false },
            title = { Text("Clear cart?") },
            text = { Text("This removes all items from your cart. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onClearCartClicked()
                    showClearCartConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearCartConfirm = false }) { Text("Cancel") }
            }
        )
    }

    val isTransientStep = step == CheckoutStep.AWAITING_DELIVERY
        || step == CheckoutStep.VERIFICATION
        || step == CheckoutStep.CONFIRMATION

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            CheckoutStep.CART -> "My Cart"
                            CheckoutStep.AWAITING_DELIVERY -> "Waiting for Provider"
                            CheckoutStep.ADDRESS -> "Delivery Address"
                            CheckoutStep.PAYMENT -> "Payment"
                            CheckoutStep.VERIFICATION -> "Verifying Payment"
                            CheckoutStep.CONFIRMATION -> "Order Confirmed"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    if (!isTransientStep) {
                        IconButton(onClick = {
                            when (step) {
                                CheckoutStep.CART -> navController.popBackStack()
                                CheckoutStep.ADDRESS -> step = CheckoutStep.CART
                                CheckoutStep.PAYMENT -> {
                                    step = if (viewModel.requiresDelivery) CheckoutStep.ADDRESS
                                    else CheckoutStep.CART
                                }
                                else -> step = CheckoutStep.CART
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (step == CheckoutStep.CART && hasCartItems) {
                        IconButton(onClick = { showClearCartConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear cart")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!isTransientStep) {
                Surface(tonalElevation = 8.dp) {
                    Column(modifier = Modifier.padding(16.dp).navigationBarsPadding()) {
                        if (uiState is CheckoutUiState.Error) {
                            Text(
                                (uiState as CheckoutUiState.Error).message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        val buttonText = when (step) {
                            CheckoutStep.CART -> {
                                if (viewModel.requiresDelivery && acceptedDelivery == null)
                                    "Request Delivery"
                                else if (viewModel.requiresDelivery && acceptedDelivery != null)
                                    "Continue to Address"
                                else
                                    "Continue to Payment"
                            }
                            CheckoutStep.ADDRESS -> "Continue to Payment"
                            CheckoutStep.PAYMENT -> "Place Order"
                            else -> ""
                        }

                        SwiftPrimaryButton(
                            text = buttonText,
                            onClick = {
                                when (step) {
                                    CheckoutStep.CART -> {
                                        if (viewModel.requiresDelivery && acceptedDelivery == null) {
                                            // Submit the delivery request — step will advance to
                                            // AWAITING_DELIVERY via LaunchedEffect on uiState.
                                            viewModel.requestDelivery()
                                        } else if (viewModel.requiresDelivery) {
                                            step = CheckoutStep.ADDRESS
                                        } else {
                                            step = CheckoutStep.PAYMENT
                                        }
                                    }
                                    CheckoutStep.ADDRESS -> step = CheckoutStep.PAYMENT
                                    CheckoutStep.PAYMENT -> viewModel.placeOrder()
                                    else -> {}
                                }
                            },
                            isLoading = uiState is CheckoutUiState.PlacingOrder
                                || (uiState is CheckoutUiState.Loading && step != CheckoutStep.CART),
                            enabled = when {
                                uiState is CheckoutUiState.CartLoaded -> true
                                uiState is CheckoutUiState.Loading && step == CheckoutStep.CART -> true
                                else -> false
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!isTransientStep) {
                CheckoutStepIndicator(
                    currentStep = step,
                    requiresDelivery = viewModel.requiresDelivery
                )
            }

            when (step) {
                CheckoutStep.CART -> Column {
                    DeliveryChoiceToggle(
                        requiresDelivery = viewModel.requiresDelivery,
                        onChange = { viewModel.updateRequiresDelivery(it) }
                    )
                    if (viewModel.requiresDelivery) {
                        val providers by viewModel.deliveryListings.collectAsState()
                        // Show the accepted provider chip if delivery is already accepted.
                        if (acceptedDelivery != null) {
                            AcceptedDeliveryBanner(
                                accepted = acceptedDelivery!!,
                                onClear = {
                                    viewModel.updateRequiresDelivery(false)
                                    viewModel.updateRequiresDelivery(true)
                                }
                            )
                        } else {
                            DeliveryProviderSelector(
                                providers = providers,
                                selectedProviderId = viewModel.selectedDeliveryListingId,
                                onProviderSelected = { viewModel.updateSelectedDeliveryListing(it) }
                            )
                        }
                    }
                    CartStep(
                        state = uiState,
                        cartItems = cartItems,
                        onRemoveItem = { viewModel.removeItem(it) }
                    )
                }

                CheckoutStep.AWAITING_DELIVERY -> {
                    val requestId = (uiState as? CheckoutUiState.AwaitingDeliveryAcceptance)?.requestId ?: ""
                    DeliveryWaitingStep(
                        providerName = viewModel.deliveryListings.collectAsState().value
                            .firstOrNull { it.id == viewModel.selectedDeliveryListingId }?.title
                            ?: "the provider",
                        onCancel = {
                            viewModel.cancelPendingDeliveryRequest()
                            // Step will revert to CART via LaunchedEffect when CartLoaded emits.
                        }
                    )
                }

                CheckoutStep.ADDRESS -> AddressStep(
                    address = viewModel.deliveryAddress,
                    onAddressUpdate = { viewModel.updateAddress(it) }
                )

                CheckoutStep.PAYMENT -> PaymentStep(
                    state = uiState,
                    acceptedDelivery = acceptedDelivery,
                    selectedMethod = viewModel.paymentMethod,
                    selectedProvider = viewModel.paymentProvider,
                    phone = viewModel.paymentPhone,
                    onMethodChange = { method, provider ->
                        viewModel.paymentMethod = method
                        viewModel.paymentProvider = provider
                    },
                    onPhoneChange = { viewModel.paymentPhone = it }
                )

                CheckoutStep.VERIFICATION -> VerificationStep(
                    state = uiState,
                    onVerify = {
                        (uiState as? CheckoutUiState.AwaitingPayment)?.sessionId?.let {
                            viewModel.verifyPayment(it)
                        }
                    }
                )

                CheckoutStep.CONFIRMATION -> ConfirmationStep(
                    state = uiState,
                    onTrackOrder = {
                        val orderId = (uiState as? CheckoutUiState.OrderPlaced)?.orderId ?: return@ConfirmationStep
                        navController.navigate(Screen.OrderDetail.createRoute(orderId)) {
                            popUpTo(Screen.Home.route)
                        }
                    },
                    onContinueShopping = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

// ─── Accepted delivery banner ─────────────────────────────────────────────────

@Composable
private fun AcceptedDeliveryBanner(
    accepted: AcceptedDelivery,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Delivery accepted",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "${accepted.providerName} · ${accepted.fee.toDisplayString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            TextButton(onClick = onClear) { Text("Change") }
        }
    }
}

// ─── Delivery waiting step ────────────────────────────────────────────────────

@Composable
private fun DeliveryWaitingStep(
    providerName: String,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(32.dp))
        Text("Waiting for $providerName", style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your delivery request has been sent. The provider has 2 minutes to accept.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel Request")
        }
    }
}

// ─── Verification step ────────────────────────────────────────────────────────

@Composable
private fun VerificationStep(state: CheckoutUiState, onVerify: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Payment, null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Authorize Payment", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("We've opened MoPay in your browser. Complete the payment and return here.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        if (state is CheckoutUiState.VerifyingPayment) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Verifying...", style = MaterialTheme.typography.labelLarge)
        } else {
            if (state is CheckoutUiState.Error) {
                Text(state.message, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp))
            }
            SwiftPrimaryButton("I Have Paid", onClick = onVerify, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ─── Step indicator ───────────────────────────────────────────────────────────

@Composable
private fun CheckoutStepIndicator(currentStep: CheckoutStep, requiresDelivery: Boolean) {
    // Named steps visible in the indicator (transient steps are not shown).
    val steps = if (requiresDelivery)
        listOf("Cart", "Address", "Payment")
    else
        listOf("Cart", "Payment")

    val currentIndex = when {
        !requiresDelivery && currentStep == CheckoutStep.PAYMENT -> 1
        currentStep == CheckoutStep.CART -> 0
        currentStep == CheckoutStep.ADDRESS -> 1
        currentStep == CheckoutStep.PAYMENT -> 2
        else -> 0
    }.coerceAtMost(steps.size - 1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (index <= currentIndex) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (index < currentIndex) {
                    Icon(Icons.Default.Check, null, tint = Color.White,
                        modifier = Modifier.size(16.dp))
                } else {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                        color = if (index <= currentIndex) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (index < steps.size - 1) {
                Box(modifier = Modifier.weight(1f).height(2.dp).background(
                    if (index < currentIndex) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ))
            }
        }
    }
}

// ─── Delivery toggle ──────────────────────────────────────────────────────────

@Composable
private fun DeliveryChoiceToggle(requiresDelivery: Boolean, onChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Delivery", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.selectable(selected = requiresDelivery, onClick = { onChange(true) })) {
            RadioButton(selected = requiresDelivery, onClick = { onChange(true) })
            Text("I want it delivered")
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.selectable(selected = !requiresDelivery, onClick = { onChange(false) })) {
            RadioButton(selected = !requiresDelivery, onClick = { onChange(false) })
            Text("I'll collect / arrange it myself")
        }
    }
}

// ─── Delivery provider selector ───────────────────────────────────────────────

@Composable
private fun DeliveryProviderSelector(
    providers: List<Listing>,
    selectedProviderId: String?,
    onProviderSelected: (String) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Choose a delivery provider", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (providers.isEmpty()) {
            Text("No delivery providers available right now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 16.dp)
            ) {
                items(providers) { provider ->
                    val isSelected = provider.id == selectedProviderId
                    SwiftCard(
                        modifier = Modifier
                            .width(160.dp)
                            .clickable { onProviderSelected(provider.id) }
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                shape = MaterialTheme.shapes.medium
                            )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(provider.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(provider.price.toDisplayString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary)
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp).align(Alignment.End))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Cart step ────────────────────────────────────────────────────────────────

@Composable
private fun CartStep(
    state: CheckoutUiState,
    cartItems: List<CartItem>,
    onRemoveItem: (String) -> Unit
) {
    val cartLoaded = state as? CheckoutUiState.CartLoaded
    val summary = cartLoaded?.summary
    val isRecalculating = cartLoaded?.isRecalculating ?: false

    Column(modifier = Modifier.fillMaxSize()) {
        if (cartItems.isEmpty()) {
            when (state) {
                is CheckoutUiState.Loading -> LoadingState(modifier = Modifier.weight(1f))
                is CheckoutUiState.Error -> EmptyState("Something went wrong", "See the message below",
                    modifier = Modifier.weight(1f))
                else -> EmptyState("Your cart is empty", "Add items to get started",
                    modifier = Modifier.weight(1f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(cartItems, key = { it.listingId }) { item ->
                    CartItemRow(item = item, onRemove = { onRemoveItem(item.listingId) })
                }
            }
        }

        if (summary != null) {
            Surface(tonalElevation = 2.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .alpha(if (isRecalculating) 0.5f else 1f)
                    ) {
                        OrderSummaryRow("Subtotal", summary.subtotal.toDisplayString())
                        OrderSummaryRow("Delivery", summary.deliveryFee.toDisplayString())
                        OrderSummaryRow("Platform fee", summary.platformFee.toDisplayString())
                        Divider(modifier = Modifier.padding(vertical = 8.dp))
                        OrderSummaryRow("Total", summary.total.toDisplayString(), isTotal = true)
                    }
                    if (isRecalculating) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun CartItemRow(item: CartItem, onRemove: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text("Qty: ${item.quantity}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.unitPrice.toDisplayString(), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun OrderSummaryRow(label: String, value: String, isTotal: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = if (isTotal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(value,
            style = if (isTotal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (isTotal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
    }
}

// ─── Address step ─────────────────────────────────────────────────────────────

@Composable
private fun AddressStep(address: DeliveryAddress, onAddressUpdate: (DeliveryAddress) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SwiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Delivery Details", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = address.label,
                    onValueChange = { onAddressUpdate(address.copy(label = it)) },
                    label = { Text("Address Label (e.g. Home, Office)") },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, singleLine = true)
                OutlinedTextField(value = address.streetHint,
                    onValueChange = { onAddressUpdate(address.copy(streetHint = it)) },
                    label = { Text("Street / Landmark Hint") },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                OutlinedTextField(value = address.city,
                    onValueChange = { onAddressUpdate(address.copy(city = it)) },
                    label = { Text("City") },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, singleLine = true)
                OutlinedTextField(value = address.district,
                    onValueChange = { onAddressUpdate(address.copy(district = it)) },
                    label = { Text("District") },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, singleLine = true)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Select Delivery Location on Map *", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp))) {
            AndroidView(
                factory = { ctx ->
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    val initialLat = if (address.lat != 0.0) address.lat else -29.3167
                    val initialLng = if (address.lng != 0.0) address.lng else 27.4833
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)
                        controller.setCenter(OsmGeoPoint(initialLat, initialLng))
                        val dropoffMarker = Marker(this).apply {
                            position = OsmGeoPoint(initialLat, initialLng)
                            title = "Delivery Point"
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        overlays.add(dropoffMarker)
                        val touchOverlay = object : Overlay() {
                            override fun onSingleTapUp(e: android.view.MotionEvent, mapView: MapView): Boolean {
                                val proj = mapView.projection
                                val geoPoint = proj.fromPixels(e.x.toInt(), e.y.toInt()) as OsmGeoPoint
                                onAddressUpdate(address.copy(lat = geoPoint.latitude, lng = geoPoint.longitude))
                                dropoffMarker.position = geoPoint
                                mapView.invalidate()
                                return true
                            }
                        }
                        overlays.add(touchOverlay)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ─── Payment step ─────────────────────────────────────────────────────────────

@Composable
private fun PaymentStep(
    state: CheckoutUiState,
    acceptedDelivery: AcceptedDelivery?,
    selectedMethod: PaymentMethod,
    selectedProvider: String?,
    phone: String,
    onMethodChange: (PaymentMethod, String?) -> Unit,
    onPhoneChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Show the accepted delivery context so the customer remembers what they're paying for.
        if (acceptedDelivery != null) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocalShipping, null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Delivery: ${acceptedDelivery.providerName} · ${acceptedDelivery.fee.toDisplayString()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Text("Select Payment Method", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        val methods = listOf(
            Triple(PaymentMethod.MOPAY, "MPESA", "M-Pesa"),
            Triple(PaymentMethod.MOPAY, "ECOCASH", "EcoCash"),
            Triple(PaymentMethod.MOPAY, "BANK", "Bank Transfer"),
            Triple(PaymentMethod.SWIFT_WALLET, null, "Swift Wallet")
        )

        methods.forEach { (method, provider, label) ->
            SwiftCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { onMethodChange(method, provider) }
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedMethod == method && selectedProvider == provider,
                        onClick = { onMethodChange(method, provider) }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (selectedMethod == PaymentMethod.MOPAY) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                label = { Text("Phone / Account Number") },
                prefix = { Text("+266 ") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
        }
    }
}

// ─── Confirmation step ────────────────────────────────────────────────────────

@Composable
private fun ConfirmationStep(
    state: CheckoutUiState,
    onTrackOrder: () -> Unit,
    onContinueShopping: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CheckCircle, null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text("Order Placed!", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (state is CheckoutUiState.OrderPlaced) {
            Text("Order #${state.orderId.take(8).uppercase()}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text("Your seller has been notified and will process your order soon.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        SwiftPrimaryButton("Track Order", onClick = onTrackOrder, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onContinueShopping, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium) {
            Text("Continue Shopping")
        }
    }
}
