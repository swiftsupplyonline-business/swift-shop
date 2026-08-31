package com.swiftshop.feature.shop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.swiftColors
import com.swiftshop.core.ui.navigation.Screen

// â”€â”€â”€ CTA Configuration Engine â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class CtaConfig(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@Composable
fun ctaForListingType(type: ListingType, brandColor: Color): CtaConfig = when (type) {
    ListingType.BUY -> CtaConfig("Buy Now", Icons.Default.ShoppingCart, brandColor)
    ListingType.MAKE_PAYMENT -> CtaConfig("Make Payment", Icons.Default.Payment, brandColor)
    ListingType.SET_APPOINTMENT -> CtaConfig("Book Appointment", Icons.Default.CalendarMonth, brandColor)
    ListingType.PLACE_ORDER -> CtaConfig("Place Order", Icons.Default.LocalShipping, brandColor)
    ListingType.REGISTER -> CtaConfig("Register", Icons.Default.HowToReg, brandColor)
    ListingType.DELIVER -> CtaConfig("Request Delivery", Icons.Default.DeliveryDining, brandColor)
    ListingType.TAKE_ME_THERE -> CtaConfig("Take Me There", Icons.Default.Navigation, brandColor)
}

// â”€â”€â”€ Listing Detail Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ListingDetailScreen(
    navController: NavController,
    viewModel: ListingDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val quantity by viewModel.quantity.collectAsState()
    val isAdding by viewModel.isAddingToCart.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val colors = MaterialTheme.swiftColors
    val snackbarHostState = remember { SnackbarHostState() }
    var activeSheet by remember { mutableStateOf<ListingType?>(null) }

    LaunchedEffect(actionState) {
        when (val state = actionState) {
            is ActionState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearActionState()
            }
            is ActionState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearActionState()
            }
            else -> {}
        }
    }

    LaunchedEffect(viewModel.navigationEvents) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is ListingDetailNavigation.GoToCheckout -> {
                    navController.navigate(Screen.Checkout.route)
                }
            }
        }
    }

    when (val state = uiState) {
        is ListingDetailState.Loading -> LoadingState()
        is ListingDetailState.Error -> ErrorState(state.message, onRetry = { viewModel.load() })
        is ListingDetailState.Loaded -> {
            val listing = state.listing
            val cta = ctaForListingType(listing.listingType, colors.brandBlue)

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {},
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Default.ArrowBack, "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { /* share */ }) {
                                Icon(Icons.Default.Share, "Share")
                            }
                            IconButton(onClick = { /* bookmark */ }) {
                                Icon(Icons.Default.BookmarkBorder, "Save")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 8.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Quantity selector
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                IconButton(onClick = { viewModel.updateQuantity(quantity - 1) }) {
                                    Icon(Icons.Default.Remove, null)
                                }
                                Text(quantity.toString(), style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { viewModel.updateQuantity(quantity + 1) }) {
                                    Icon(Icons.Default.Add, null)
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.addToCart() },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium,
                                enabled = !isAdding
                            ) {
                                Text("Add to Cart")
                            }
                            SwiftPrimaryButton(
                                text = cta.label,
                                onClick = {
                                    when (listing.listingType) {
                                        ListingType.BUY, ListingType.PLACE_ORDER -> {
                                            viewModel.buyNow()
                                        }
                                        ListingType.DELIVER, ListingType.TAKE_ME_THERE ->
                                            navController.navigate(Screen.DeliveryTracking.createRoute(listing.id))
                                        else -> { activeSheet = listing.listingType }
                                    }
                                },
                                leadingIcon = { Icon(cta.icon, null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.weight(1.5f)
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ... same ...
                    if (listing.imageUrls.isNotEmpty()) {
                        val pagerState = rememberPagerState(pageCount = { listing.imageUrls.size })
                        Box {
                            HorizontalPager(state = pagerState) { index ->
                                AsyncImage(
                                    model = listing.imageUrls[index],
                                    contentDescription = "Product image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                )
                            }
                            // Dot indicator
                            if (listing.imageUrls.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    repeat(listing.imageUrls.size) { i ->
                                        Box(
                                            modifier = Modifier
                                                .size(if (i == pagerState.currentPage) 8.dp else 6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (i == pagerState.currentPage) Color.White
                                                    else Color.White.copy(alpha = 0.5f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        // Stock info
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(listing.price.toDisplayString(),
                                style = MaterialTheme.typography.headlineMedium,
                                color = colors.brandBlue,
                                modifier = Modifier.weight(1f))
                            
                            val stockColor = if (listing.stockQuantity > 0) colors.success else MaterialTheme.colorScheme.error
                            Text(
                                if (listing.stockQuantity > 0) "${listing.stockQuantity} in stock" else "Out of stock",
                                style = MaterialTheme.typography.labelLarge,
                                color = stockColor
                            )
                        }

                        Spacer(Modifier.height(4.dp))
                        Text(listing.title, style = MaterialTheme.typography.titleLarge)

                        // Shop Info
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { navController.navigate(Screen.ShopDetail.createRoute(listing.shopId)) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Store, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sold by", style = MaterialTheme.typography.labelSmall)
                                Text("Official Shop", style = MaterialTheme.typography.titleSmall) // Replace with real name if available in DTO
                            }
                            Text("Visit Shop", 
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Icon(Icons.Default.ChevronRight, null, 
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp))
                        }


                        // Commitment counter
                        if (listing.commitmentCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.People, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("${listing.commitmentCount} verified buyers",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }

                        // Delivery estimate
                        if (listing.deliveryEstimateDays > 0) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalShipping, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Estimated delivery: ${listing.deliveryEstimateDays} days",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                        // Description
                        Text("Description", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(listing.description, style = MaterialTheme.typography.bodyMedium)

                        // Custom fields (for PLACE_ORDER, REGISTER types)
                        if (listing.customFields.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                            Text("Options", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            listing.customFields.forEach { field ->
                                CustomFieldRenderer(field = field)
                                Spacer(Modifier.height(8.dp))
                            }
                        }

                        // Tags
                        if (listing.tags.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                listing.tags.forEach { tag ->
                                    SuggestionChip(onClick = {}, label = { Text("#$tag") })
                                }
                            }
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}



// ─── Action Sheets ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppointmentSheet(listing: Listing, onDismiss: () -> Unit) {
    val timeSlots = listOf("08:00","09:00","10:00","11:00","12:00","13:00","14:00","15:00","16:00","17:00")
    var selectedSlot by remember { mutableStateOf("") }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Book Appointment", style = MaterialTheme.typography.titleLarge)
        Text(listing.title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()

        Text("Select a Date", style = MaterialTheme.typography.titleSmall)
        DatePicker(state = dateState, modifier = Modifier.fillMaxWidth())
        HorizontalDivider()

        Text("Select a Time", style = MaterialTheme.typography.titleSmall)
        timeSlots.chunked(4).forEach { rowSlots ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowSlots.forEach { slot ->
                    FilterChip(
                        selected = selectedSlot == slot,
                        onClick = { selectedSlot = slot },
                        label = { Text(slot, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(4 - rowSlots.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(8.dp))
        SwiftPrimaryButton(
            text = if (selectedSlot.isNotEmpty()) "Confirm $selectedSlot" else "Select a time slot",
            enabled = selectedSlot.isNotEmpty(),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun MakePaymentSheet(
    listing: Listing,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var amount by remember { mutableStateOf(listing.price.toDisplayString()) }
    var note   by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Make a Payment", style = MaterialTheme.typography.titleLarge)
        Text(listing.title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("Amount (LSL)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
            ),
            leadingIcon = { Icon(Icons.Default.Payment, null) }
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note / Reference (optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        SwiftPrimaryButton(
            text = "Pay via Mopay",
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormSubmitSheet(
    listing: Listing,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val fieldValues = remember { mutableStateMapOf<String, String>() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            if (listing.listingType == ListingType.REGISTER) "Registration Form" else "Order Details",
            style = MaterialTheme.typography.titleLarge
        )
        Text(listing.title, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()

        if (listing.customFields.isEmpty()) {
            Text("No additional information required.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            listing.customFields.forEach { field ->
                when (field.type) {
                    "select" -> {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = fieldValues[field.id] ?: "",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(field.label + if (field.isRequired) " *" else "") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                shape = MaterialTheme.shapes.medium
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                field.options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = { fieldValues[field.id] = option; expanded = false }
                                    )
                                }
                            }
                        }
                    }
                    "date" -> OutlinedTextField(
                        value = fieldValues[field.id] ?: "",
                        onValueChange = { fieldValues[field.id] = it },
                        label = { Text(field.label + if (field.isRequired) " *" else "") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("YYYY-MM-DD") },
                        leadingIcon = { Icon(Icons.Default.CalendarMonth, null) },
                        shape = MaterialTheme.shapes.medium
                    )
                    "phone" -> OutlinedTextField(
                        value = fieldValues[field.id] ?: "",
                        onValueChange = { fieldValues[field.id] = it },
                        label = { Text(field.label + if (field.isRequired) " *" else "") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                        ),
                        leadingIcon = { Icon(Icons.Default.Phone, null) },
                        shape = MaterialTheme.shapes.medium
                    )
                    else -> OutlinedTextField(
                        value = fieldValues[field.id] ?: "",
                        onValueChange = { fieldValues[field.id] = it },
                        label = { Text(field.label + if (field.isRequired) " *" else "") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    )
                }
            }
        }

        val allRequiredFilled = listing.customFields
            .filter { it.isRequired }
            .all { (fieldValues[it.id] ?: "").isNotBlank() }

        SwiftPrimaryButton(
            text = if (listing.listingType == ListingType.REGISTER) "Submit Registration" else "Place Order",
            enabled = allRequiredFilled,
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomFieldRenderer(field: CustomField) {
    when (field.type) {
        "select" -> {
            var expanded by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf("") }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected.ifEmpty { "Select ${field.label}" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(field.label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = MaterialTheme.shapes.medium
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { option ->
                        DropdownMenuItem(text = { Text(option) },
                            onClick = { selected = option; expanded = false })
                    }
                }
            }
        }
        "date" -> OutlinedTextField(
            value = "", onValueChange = {},
            label = { Text(field.label) },
            placeholder = { Text("YYYY-MM-DD") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            leadingIcon = { Icon(Icons.Default.CalendarMonth, null) }
        )
        "phone" -> OutlinedTextField(
            value = "", onValueChange = {},
            label = { Text(field.label) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
            ),
            leadingIcon = { Icon(Icons.Default.Phone, null) }
        )
        else -> OutlinedTextField(
            value = "", onValueChange = {},
            label = { Text(field.label) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium
        )
    }
}


