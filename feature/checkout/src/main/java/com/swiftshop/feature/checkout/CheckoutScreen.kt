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

enum class CheckoutStep { CART, ADDRESS, PAYMENT, VERIFICATION, CONFIRMATION }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    navController: NavController,
    viewModel: CheckoutViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var step by remember { mutableStateOf(CheckoutStep.CART) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(uiState) {
        when (uiState) {
            is CheckoutUiState.OrderPlaced -> step = CheckoutStep.CONFIRMATION
            is CheckoutUiState.AwaitingPayment -> {
                val state = uiState as CheckoutUiState.AwaitingPayment
                step = CheckoutStep.VERIFICATION
                uriHandler.openUri(state.paymentUrl)
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            CheckoutStep.CART -> "My Cart"
                            CheckoutStep.ADDRESS -> "Delivery Address"
                            CheckoutStep.PAYMENT -> "Payment"
                            CheckoutStep.VERIFICATION -> "Verifying Payment"
                            CheckoutStep.CONFIRMATION -> "Order Confirmed"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    if (step != CheckoutStep.CONFIRMATION && step != CheckoutStep.VERIFICATION) {
                        IconButton(onClick = {
                            if (step == CheckoutStep.CART) navController.popBackStack()
                            else step = CheckoutStep.values()[step.ordinal - 1]
                        }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (step != CheckoutStep.CONFIRMATION && step != CheckoutStep.VERIFICATION) {
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
                            CheckoutStep.CART -> "Proceed to Address"
                            CheckoutStep.ADDRESS -> "Continue to Payment"
                            CheckoutStep.PAYMENT -> "Place Order"
                            else -> ""
                        }

                        SwiftPrimaryButton(
                            text = buttonText,
                            onClick = {
                                when (step) {
                                    CheckoutStep.CART -> step = CheckoutStep.ADDRESS
                                    CheckoutStep.ADDRESS -> step = CheckoutStep.PAYMENT
                                    CheckoutStep.PAYMENT -> viewModel.placeOrder()
                                    else -> {}
                                }
                            },
                            isLoading = uiState is CheckoutUiState.PlacingOrder || (uiState is CheckoutUiState.Loading && step != CheckoutStep.CART),
                            enabled = when (uiState) {
                                is CheckoutUiState.CartLoaded -> true
                                is CheckoutUiState.Loading -> step == CheckoutStep.CART
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
            // Step indicator
            if (step != CheckoutStep.CONFIRMATION && step != CheckoutStep.VERIFICATION) {
                CheckoutStepIndicator(currentStep = step)
            }

            when (step) {
                CheckoutStep.CART -> CartStep(
                    state = uiState,
                    onRemoveItem = { viewModel.removeItem(it) }
                )
                CheckoutStep.ADDRESS -> AddressStep(
                    address = viewModel.deliveryAddress,
                    onAddressUpdate = { viewModel.updateAddress(it) }
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

@Composable
private fun CheckoutStepIndicator(currentStep: CheckoutStep) {
    val steps = listOf("Cart", "Address", "Payment")
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

        // Order summary
        if (summary != null) {
            Surface(tonalElevation = 2.dp) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OrderSummaryRow("Subtotal", summary.subtotal.toDisplayString())
                    OrderSummaryRow("Delivery", summary.deliveryFee.toDisplayString())
                    OrderSummaryRow("Platform fee", summary.platformFee.toDisplayString())
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
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
    onAddressUpdate: (DeliveryAddress) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SwiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Delivery Details", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = address.label,
                    onValueChange = { onAddressUpdate(address.copy(label = it)) },
                    label = { Text("Address Label (e.g. Home, Office)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true
                )
                OutlinedTextField(
                    value = address.streetHint,
                    onValueChange = { onAddressUpdate(address.copy(streetHint = it)) },
                    label = { Text("Street / Landmark Hint") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = address.city,
                    onValueChange = { onAddressUpdate(address.copy(city = it)) },
                    label = { Text("City") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true
                )
                OutlinedTextField(
                    value = address.district,
                    onValueChange = { onAddressUpdate(address.copy(district = it)) },
                    label = { Text("District") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    singleLine = true
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Map placeholder — real implementation uses OsmDroid
        SwiftCard(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Map, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text("Tap to select location on map",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
    onPhoneChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
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
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
        }
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
