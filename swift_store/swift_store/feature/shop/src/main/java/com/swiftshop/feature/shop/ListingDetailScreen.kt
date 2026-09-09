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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// â”€â”€â”€ CTA Configuration Engine â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class CtaConfig(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@Composable
fun ctaForListingType(type: ListingType, brandColor: Color): CtaConfig = when (type) {
    ListingType.PRODUCT -> CtaConfig("Buy Now", Icons.Default.ShoppingCart, brandColor)
    ListingType.SERVICE -> CtaConfig("Book Service", Icons.Default.Assignment, brandColor)
    ListingType.BUY -> CtaConfig("Buy Now", Icons.Default.ShoppingCart, brandColor)
    ListingType.MAKE_PAYMENT -> CtaConfig("Make Payment", Icons.Default.Payment, brandColor)
    ListingType.SET_APPOINTMENT -> CtaConfig("Book Appointment", Icons.Default.CalendarMonth, brandColor)
    ListingType.PLACE_ORDER -> CtaConfig("Place Order", Icons.Default.LocalShipping, brandColor)
    ListingType.REGISTER -> CtaConfig("Register", Icons.Default.HowToReg, brandColor)
    ListingType.DELIVER -> CtaConfig("Request Delivery", Icons.Default.DeliveryDining, brandColor)
    ListingType.TAKE_ME_THERE -> CtaConfig("Take Me There", Icons.Default.Navigation, brandColor)
    ListingType.PHYSICAL_ITEM -> CtaConfig("Buy Now", Icons.Default.ShoppingCart, brandColor)
    ListingType.PREPARED_FOOD -> CtaConfig("Order Now", Icons.Default.Restaurant, brandColor)
    ListingType.BOOKABLE_SERVICE -> CtaConfig("Book Now", Icons.Default.CalendarMonth, brandColor)
    ListingType.BULK_SUPPLY -> CtaConfig("Request Quote", Icons.Default.Inventory, brandColor)
    ListingType.DELIVERY_SERVICE -> CtaConfig("Request Delivery", Icons.Default.DeliveryDining, brandColor)
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
    val availableSlots by viewModel.availableSlots.collectAsState()
    val selectedSlot by viewModel.selectedSlot.collectAsState()
    val isAdding by viewModel.isAddingToCart.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val isBookmarked by viewModel.isBookmarked.collectAsState()
    val colors = MaterialTheme.swiftColors
    val snackbarHostState = remember { SnackbarHostState() }
    var activeSheet by remember { mutableStateOf<ListingType?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

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
                    navController.navigate(Screen.Checkout.createRoute(event.orderId))
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
            val currentUser by viewModel.currentUser.collectAsState()
            val isOwner = currentUser?.uid == listing.sellerId

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("Delete Listing?") },
                    text = { Text("This will permanently delete this listing. This cannot be undone.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteDialog = false
                                viewModel.deleteListing { navController.popBackStack() }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (activeSheet == ListingType.SET_APPOINTMENT || activeSheet == ListingType.BOOKABLE_SERVICE) {
                ModalBottomSheet(onDismissRequest = { activeSheet = null }) {
                    AppointmentSheet(
                        listing = listing,
                        availableSlots = availableSlots,
                        selectedSlot = selectedSlot,
                        onSlotSelected = viewModel::onSlotSelected,
                        onConfirm = { phone ->
                            viewModel.onConfirmBooking(phone)
                            activeSheet = null
                        },
                        onDismiss = { activeSheet = null }
                    )
                }
            } else if (activeSheet == ListingType.MAKE_PAYMENT) {

                ModalBottomSheet(onDismissRequest = { activeSheet = null }) {
                    MakePaymentSheet(listing = listing, onDismiss = { activeSheet = null }, onConfirm = {})
                }
            } else if (activeSheet != null) {
                ModalBottomSheet(onDismissRequest = { activeSheet = null }) {
                    FormSubmitSheet(
                        listing = listing, 
                        onDismiss = { activeSheet = null }, 
                        onSubmit = { viewModel.submitCustomOrder(it); activeSheet = null }
                    )
                }
            }


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
                            if (isOwner) {
                                IconButton(onClick = { navController.navigate(Screen.EditListing.createRoute(listing.id)) }) {
                                    Icon(Icons.Default.Edit, "Edit")
                                }
                                IconButton(onClick = { showDeleteDialog = true }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            IconButton(onClick = { /* share */ }) {
                                Icon(Icons.Default.Share, "Share")
                            }
                            IconButton(onClick = viewModel::toggleBookmark) {
                                Icon(
                                    if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "Save",
                                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current
                                )
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
                                        ListingType.PRODUCT, ListingType.BUY, ListingType.PHYSICAL_ITEM,
                                        ListingType.PREPARED_FOOD, ListingType.BULK_SUPPLY -> {
                                            viewModel.buyNow()
                                        }
                                        ListingType.DELIVER, ListingType.DELIVERY_SERVICE ->
                                            navController.navigate(Screen.DeliveryTracking.createRoute(listing.id))
                                        ListingType.TAKE_ME_THERE -> {
                                            state.shop?.let { shop ->
                                                if (shop.locationAddress.isNotBlank()) {
                                                    val uri = android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(shop.locationAddress)}")
                                                    val mapIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                                    navController.context.startActivity(mapIntent)
                                                }
                                            }
                                        }

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
                            if (state.shop?.logoUrl?.isNotBlank() == true) {
                                SwiftAvatar(url = state.shop.logoUrl, size = 40.dp)
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    SwiftEntityIcon(
                                        entity = SwiftEntity.SHOP,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sold by", style = MaterialTheme.typography.labelSmall)
                                Text(state.shop?.name ?: "Official Shop", style = MaterialTheme.typography.titleSmall)
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
                                SwiftEntityIcon(
                                    entity = SwiftEntity.DELIVERY,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
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
private fun AppointmentSheet(
    listing: Listing,
    availableSlots: List<AvailabilitySlot>,
    selectedSlot: AvailabilitySlot?,
    onSlotSelected: (AvailabilitySlot?) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf("") }
    val timeScrollState = rememberScrollState()

    val groupedSlots = remember(availableSlots) {
        availableSlots.groupBy {
            SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(it.startTime))
        }
    }

    var selectedDate by remember(groupedSlots) {
        mutableStateOf(groupedSlots.keys.firstOrNull() ?: "")
    }

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

        if (availableSlots.isEmpty()) {
            EmptyState(
                title = "No slots available",
                subtitle = "This provider hasn't listed any availability yet.",
                modifier = Modifier.height(200.dp)
            )
        } else {
            Text("Select a Date", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedSlots.keys.forEach { date ->
                    FilterChip(
                        selected = selectedDate == date,
                        onClick = { selectedDate = date; onSlotSelected(null) },
                        label = { Text(date) }
                    )
                }
            }

            Text("Select a Time", style = MaterialTheme.typography.titleSmall)
            val slotsForDate = groupedSlots[selectedDate] ?: emptyList()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(timeScrollState)
                    .heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                slotsForDate.chunked(3).forEach { rowSlots ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowSlots.forEach { slot ->
                            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(slot.startTime))
                            FilterChip(
                                selected = selectedSlot?.id == slot.id,
                                onClick = { onSlotSelected(slot) },
                                label = { Text(timeStr, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowSlots.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            HorizontalDivider()
            Text("Contact Information", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Phone Number") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                ),
                leadingIcon = { Icon(Icons.Default.Phone, null) }
            )
        }

        Spacer(Modifier.height(8.dp))
        SwiftPrimaryButton(
            text = if (selectedSlot != null) "Reserve & Book" else "Select a time slot",
            enabled = selectedSlot != null && phoneNumber.isNotBlank(),
            onClick = { onConfirm(phoneNumber) },
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
    onSubmit: (Map<String, String>) -> Unit
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
            when (listing.listingType) {
                ListingType.REGISTER -> "Registration Form"
                ListingType.SERVICE -> "Service Details"
                else -> "Order Details"
            },
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
            text = when (listing.listingType) {
                ListingType.REGISTER -> "Submit Registration"
                ListingType.SERVICE, ListingType.BOOKABLE_SERVICE -> "Book Service"
                else -> "Place Order"
            },
            enabled = allRequiredFilled,
            onClick = { onSubmit(fieldValues.toMap()) },
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


