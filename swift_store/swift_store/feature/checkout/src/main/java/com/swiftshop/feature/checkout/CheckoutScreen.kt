package com.swiftshop.feature.checkout

import androidx.compose.foundation.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.domain.commerce.CartItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    navController: NavController,
    viewModel: CheckoutViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentStep by viewModel.currentStep.collectAsState()
    val deliveryListingsState by viewModel.deliveryListingsState.collectAsState()
    val selectedDeliveryListing by viewModel.selectedDeliveryListing.collectAsState()
    val shopMarkers by viewModel.shopMarkers.collectAsState()
    val confirmedLocation by viewModel.confirmedLocationSnapshot.collectAsState()
    
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()


    LaunchedEffect(uiState) {
        if (uiState is CheckoutUiState.AwaitingPayment) {
            val state = uiState as CheckoutUiState.AwaitingPayment
            uriHandler.openUri(state.paymentUrl)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.verifyPaymentOnReturn()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }


    // Load delivery listings when buyer enters the delivery step.
    LaunchedEffect(currentStep) {
        if (currentStep == CheckoutStep.DELIVERY) {
            viewModel.loadDeliveryListings()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {

            TopAppBar(
                title = {
                    Text(
                        when (currentStep) {
                            CheckoutStep.CART -> "My Cart"
                            CheckoutStep.DELIVERY -> "Choose Delivery"
                            CheckoutStep.ADDRESS -> "Delivery Address"
                            CheckoutStep.PAYMENT -> "Payment"
                            CheckoutStep.VERIFICATION -> "Verifying Payment"
                            CheckoutStep.CONFIRMATION -> "Order Confirmed"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    if (currentStep != CheckoutStep.CONFIRMATION && currentStep != CheckoutStep.VERIFICATION) {
                        IconButton(onClick = {
                            if (currentStep == CheckoutStep.CART) navController.popBackStack()
                            else viewModel.setStep(CheckoutStep.values()[currentStep.ordinal - 1])
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (currentStep != CheckoutStep.CONFIRMATION && currentStep != CheckoutStep.VERIFICATION) {
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

                        val buttonText = when (currentStep) {
                            CheckoutStep.CART -> "Choose Delivery"
                            CheckoutStep.DELIVERY -> "Continue to Address"
                            CheckoutStep.ADDRESS -> "Continue to Payment"
                            CheckoutStep.PAYMENT -> "Place Order"
                            else -> ""
                        }

                        // Delivery step: only enable Continue once a listing is selected.
                        val deliveryStepReady = currentStep != CheckoutStep.DELIVERY || selectedDeliveryListing != null

                        SwiftPrimaryButton(
                            text = buttonText,
                            onClick = {
                                when (currentStep) {
                                    CheckoutStep.CART -> viewModel.setStep(CheckoutStep.DELIVERY)
                                    CheckoutStep.DELIVERY -> if (selectedDeliveryListing != null) viewModel.setStep(CheckoutStep.ADDRESS)
                                    CheckoutStep.ADDRESS -> viewModel.setStep(CheckoutStep.PAYMENT)
                                    CheckoutStep.PAYMENT -> viewModel.placeOrder()
                                    else -> {}
                                }
                            },
                            isLoading = uiState is CheckoutUiState.PlacingOrder || (uiState is CheckoutUiState.Loading && currentStep == CheckoutStep.PAYMENT),
                            enabled = deliveryStepReady && when (uiState) {
                                is CheckoutUiState.CartLoaded -> true
                                is CheckoutUiState.Loading -> currentStep == CheckoutStep.CART || currentStep == CheckoutStep.DELIVERY || currentStep == CheckoutStep.ADDRESS
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
            if (currentStep != CheckoutStep.CONFIRMATION && currentStep != CheckoutStep.VERIFICATION) {
                CheckoutStepIndicator(currentStep = currentStep)
            }

            when (currentStep) {
                CheckoutStep.CART -> CartStep(
                    state = uiState,
                    onRemoveItem = { viewModel.removeItem(it) }
                )
                CheckoutStep.DELIVERY -> DeliverySelectionStep(
                    deliveryListingsState = deliveryListingsState,
                    selectedListing = selectedDeliveryListing,
                    onSelect = { viewModel.selectDeliveryListing(it) }
                )
                CheckoutStep.ADDRESS -> AddressStep(
                    address = viewModel.deliveryAddress,
                    markers = shopMarkers,
                    isConfirmed = confirmedLocation != null,
                    onAddressUpdate = { viewModel.updateAddress(it) },
                    onLocationConfirmed = { 
                        viewModel.onLocationConfirmed(it)
                        scope.launch {
                            snackbarHostState.showSnackbar("Location confirmed")
                        }
                    }
                )

                CheckoutStep.PAYMENT -> PaymentStep(
                    state = uiState,
                    selectedMethod = viewModel.paymentMethod,
                    selectedProvider = viewModel.paymentProvider,
                    phone = viewModel.paymentPhone,
                    onMethodChange = { method, provider ->
                        viewModel.paymentMethod = method
                        viewModel.paymentProvider = provider
                    },
                    onPhoneChange = { viewModel.paymentPhone = it },
                    buyerNotes = viewModel.buyerNotes,
                    onNotesChange = { viewModel.buyerNotes = it }
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
                        navController.navigate(Screen.TrackOrder.createRoute(orderId)) {
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

// ─── Delivery Selection Step ──────────────────────────────────────────────────
//
// The buyer selects a delivery offering created by a delivery provider.
// The price shown is the canonical fee — the backend will validate and re-read
// the price from the listing document; no fee is trusted from the client.

@Composable
private fun DeliverySelectionStep(
    deliveryListingsState: DeliveryListingsState,
    selectedListing: DeliveryListing?,
    onSelect: (DeliveryListing) -> Unit
) {
    when (deliveryListingsState) {
        is DeliveryListingsState.Loading -> LoadingState(modifier = Modifier.fillMaxSize())

        is DeliveryListingsState.Error -> Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Could not load delivery options",
                style = MaterialTheme.typography.titleMedium)
            Text(deliveryListingsState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        is DeliveryListingsState.Loaded -> {
            val listings = deliveryListingsState.listings
            if (listings.isEmpty()) {
                EmptyState(
                    title = "No delivery options available",
                    subtitle = "Check back soon — delivery providers are being onboarded.",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        "Select a delivery provider for your order.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listings, key = { it.id }) { listing ->
                            DeliveryListingCard(
                                listing = listing,
                                isSelected = selectedListing?.id == listing.id,
                                onSelect = { onSelect(listing) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryListingCard(
    listing: DeliveryListing,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    SwiftCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Provider avatar / listing image
            AsyncImage(
                model = listing.imageUrl.ifBlank { listing.providerAvatarUrl },
                contentDescription = listing.providerName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(listing.title, style = MaterialTheme.typography.titleSmall)
                if (listing.providerName.isNotBlank()) {
                    Text(
                        listing.providerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (listing.coverageArea.isNotBlank()) {
                    Text(
                        listing.coverageArea,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (listing.estimatedMinutes > 0) {
                    Text(
                        "Est. ${listing.estimatedMinutes} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    listing.price.toDisplayString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp).padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ─── Verification Step ────────────────────────────────────────────────────────

@Composable
private fun VerificationStep(
    state: CheckoutUiState,
    onVerify: () -> Unit
) {
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
        Text("We've opened MoPay in your browser. Please complete the payment and return here to confirm.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

// ─── Step Indicator ───────────────────────────────────────────────────────────

@Composable
private fun CheckoutStepIndicator(currentStep: CheckoutStep) {
    val steps = listOf("Cart", "Delivery", "Address", "Payment")
    val currentIndex = currentStep.ordinal.coerceAtMost(steps.size - 1)

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
                    .let { m ->
                        if (index == currentIndex) m.swiftGlowCircle(width = 3.dp) else m
                    }
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
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (index < currentIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
    }
}

// ─── Cart Step ────────────────────────────────────────────────────────────────

@Composable
private fun CartStep(
    state: CheckoutUiState,
    onRemoveItem: (String) -> Unit
) {
    val items = (state as? CheckoutUiState.CartLoaded)?.items ?: emptyList()
    val summary = (state as? CheckoutUiState.CartLoaded)?.summary
    // Only show a meaningful total after delivery is selected.
    val hasDelivery = summary != null && summary.selectedDeliveryListingId.isNotBlank()

    Column(modifier = Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            EmptyState("Your cart is empty", "Add items to get started",
                modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.listingId }) { item ->
                    CartItemRow(item = item, onRemove = { onRemoveItem(item.listingId) })
                }
            }
        }

        if (hasDelivery && summary != null) {
            Surface(tonalElevation = 2.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OrderSummaryRow("Subtotal", summary.subtotal.toDisplayString())
                    OrderSummaryRow("Delivery", summary.deliveryFee.toDisplayString())
                    OrderSummaryRow("Platform fee", summary.platformFee.toDisplayString())
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    OrderSummaryRow("Total", summary.total.toDisplayString(), isTotal = true)

                }
            }
        } else if (state is CheckoutUiState.Loading) {
            LoadingState(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CartItemRow(item: CartItem, onRemove: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
        Text(label,
            style = if (isTotal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(value,
            style = if (isTotal) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (isTotal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
    }
}

// ─── Address Step ─────────────────────────────────────────────────────────────

@Composable
private fun AddressStep(
    address: DeliveryAddress,
    markers: List<MapMarker> = emptyList(),
    isConfirmed: Boolean = false,
    onAddressUpdate: (DeliveryAddress) -> Unit,
    onLocationConfirmed: (GeoPoint) -> Unit
) {
    var focusedField by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ... Card ...
        SwiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Delivery Details", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = address.label,
                    onValueChange = { onAddressUpdate(address.copy(label = it)) },
                    label = { Text("Address Label (e.g. Home, Office)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if(it.isFocused) focusedField = "label" else if(focusedField == "label") focusedField = null }
                        .let { if(focusedField == "label") it.swiftGlowBorder(shape = MaterialTheme.shapes.medium) else it },
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true
                )
                OutlinedTextField(
                    value = address.streetHint,
                    onValueChange = { onAddressUpdate(address.copy(streetHint = it)) },
                    label = { Text("Street / Landmark Hint") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if(it.isFocused) focusedField = "street" else if(focusedField == "street") focusedField = null }
                        .let { if(focusedField == "street") it.swiftGlowBorder(shape = MaterialTheme.shapes.medium) else it },
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = address.city,
                    onValueChange = { onAddressUpdate(address.copy(city = it)) },
                    label = { Text("City") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if(it.isFocused) focusedField = "city" else if(focusedField == "city") focusedField = null }
                        .let { if(focusedField == "city") it.swiftGlowBorder(shape = MaterialTheme.shapes.medium) else it },
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true
                )
                OutlinedTextField(
                    value = address.district,
                    onValueChange = { onAddressUpdate(address.copy(district = it)) },
                    label = { Text("District") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if(it.isFocused) focusedField = "district" else if(focusedField == "district") focusedField = null }
                        .let { if(focusedField == "district") it.swiftGlowBorder(shape = MaterialTheme.shapes.medium) else it },
                    shape = MaterialTheme.shapes.medium, singleLine = true
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("Select Exact Location", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        DropYourPinComponent(
            modifier = Modifier.fillMaxWidth(),
            initialLocation = if (address.lat != 0.0) GeoPoint(address.lat, address.lng) else null,
            markers = markers,
            isConfirmed = isConfirmed,
            onLocationConfirmed = onLocationConfirmed
        )


        Spacer(Modifier.height(16.dp))
        
        Text("Fulfillment Instructions", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = address.streetHint, // Using streetHint for instructions context for now
            onValueChange = { onAddressUpdate(address.copy(streetHint = it)) },
            label = { Text("e.g. Gate number, house color...") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if(it.isFocused) focusedField = "instructions" else if(focusedField == "instructions") focusedField = null }
                .let { if(focusedField == "instructions") it.swiftGlowBorder(shape = MaterialTheme.shapes.medium) else it },
            shape = MaterialTheme.shapes.medium,
            minLines = 2
        )
    }
}

// ─── Payment Step ─────────────────────────────────────────────────────────────

@Composable
private fun PaymentStep(
    state: CheckoutUiState,
    selectedMethod: PaymentMethod,
    selectedProvider: String?,
    phone: String,
    onMethodChange: (PaymentMethod, String?) -> Unit,
    onPhoneChange: (String) -> Unit,
    buyerNotes: String,
    onNotesChange: (String) -> Unit
) {
    var focusedField by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // ... summary card ...
        (state as? CheckoutUiState.CartLoaded)?.summary?.let { summary ->
            SwiftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Order Summary", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OrderSummaryRow("Subtotal", summary.subtotal.toDisplayString())
                    OrderSummaryRow("Delivery", summary.deliveryFee.toDisplayString())
                    OrderSummaryRow("Platform fee", summary.platformFee.toDisplayString())
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    OrderSummaryRow("Total", summary.total.toDisplayString(), isTotal = true)
                }
            }
            Spacer(Modifier.height(16.dp))
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
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if(it.isFocused) focusedField = "phone" else if(focusedField == "phone") focusedField = null }
                    .let { if(focusedField == "phone") it.swiftGlowBorder(shape = MaterialTheme.shapes.medium) else it },
                shape = MaterialTheme.shapes.medium
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Order Notes (Optional)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = buyerNotes,
            onValueChange = onNotesChange,
            label = { Text("Instructions for seller or driver...") },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if(it.isFocused) focusedField = "notes" else if(focusedField == "notes") focusedField = null }
                .let { if(focusedField == "notes") it.swiftGlowBorder(shape = MaterialTheme.shapes.medium) else it },
            shape = MaterialTheme.shapes.medium,
            minLines = 3
        )
    }
}


// ─── Confirmation Step ────────────────────────────────────────────────────────

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
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        SwiftPrimaryButton("Track Order", onClick = onTrackOrder, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onContinueShopping, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium) {
            Text("Continue Shopping")
        }
    }
}
