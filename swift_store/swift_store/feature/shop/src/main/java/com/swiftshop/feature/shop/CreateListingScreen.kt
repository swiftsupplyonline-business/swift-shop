package com.swiftshop.feature.shop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.swiftColors
import com.swiftshop.core.ui.navigation.Screen
import com.swiftshop.core.ui.components.SwiftPrimaryButton

private fun ListingType.displayName(): String = when (this) {
    ListingType.PRODUCT         -> "Physical Product"
    ListingType.SERVICE         -> "Service"
    ListingType.BUY             -> "Buy / Purchase"
    ListingType.MAKE_PAYMENT    -> "Make a Payment"
    ListingType.SET_APPOINTMENT -> "Book Appointment"
    ListingType.PLACE_ORDER     -> "Place an Order (Custom Form)"
    ListingType.REGISTER        -> "Register / Sign Up"
    ListingType.DELIVER         -> "Request Delivery"
    ListingType.TAKE_ME_THERE   -> "Navigation / Directions"
    ListingType.PHYSICAL_ITEM    -> "Physical Item"
    ListingType.PREPARED_FOOD    -> "Prepared Food"
    ListingType.BOOKABLE_SERVICE -> "Bookable Service"
    ListingType.BULK_SUPPLY      -> "Bulk Supply"
    ListingType.DELIVERY_SERVICE -> "Delivery Service"
}


private fun ListingType.helpText(): String = when (this) {
    ListingType.PRODUCT         -> "Standard physical goods with inventory tracking."
    ListingType.SERVICE         -> "A service offering with a booking/contact form."
    ListingType.BUY             -> "Standard product purchase with quantity and cart."
    ListingType.MAKE_PAYMENT    -> "Buyer enters an amount and pays via Mopay."
    ListingType.SET_APPOINTMENT -> "Buyer selects a date/time slot to book."
    ListingType.PLACE_ORDER     -> "Buyer fills a custom form you define before ordering."
    ListingType.REGISTER        -> "Buyer submits a registration form (events, courses, etc.)."
    ListingType.DELIVER         -> "Buyer requests a delivery pickup/dropoff."
    ListingType.TAKE_ME_THERE   -> "Shows directions to your physical location."
    ListingType.PHYSICAL_ITEM    -> "Standard physical goods."
    ListingType.PREPARED_FOOD    -> "Food items for order."
    ListingType.BOOKABLE_SERVICE -> "Services that require booking."
    ListingType.BULK_SUPPLY      -> "Wholesale or bulk supplies."
    ListingType.DELIVERY_SERVICE -> "Courier or transport services."
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateListingScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: CreateListingViewModel = hiltViewModel()
) {
    val uiState          by viewModel.uiState.collectAsState()
    val title            by viewModel.title.collectAsState()
    val description      by viewModel.description.collectAsState()
    val category         by viewModel.category.collectAsState()
    val price            by viewModel.priceMajor.collectAsState()
    val stock            by viewModel.stockQuantity.collectAsState()
    val imageUris        by viewModel.imageUris.collectAsState()
    val canPublish       by viewModel.canPublish.collectAsState()
    val usageText        by viewModel.listingUsageText.collectAsState()
    val listingType      by viewModel.listingType.collectAsState()
    val customFields     by viewModel.customFields.collectAsState()
    val showFieldBuilder by viewModel.showCustomFieldBuilder.collectAsState()
    val showDelivery     by viewModel.showDeliveryEstimate.collectAsState()
    val deliveryDays     by viewModel.deliveryEstimateDays.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.addImages(uris) }

    LaunchedEffect(uiState) {
        if (uiState is CreateListingUiState.Success) onCreated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create ${listingType.displayName()}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Images
            Text("Product Images", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { launcher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.AddAPhoto, null) }
                }
                items(imageUris) { uri ->
                    Box(modifier = Modifier.size(100.dp)) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = { viewModel.removeImage(uri) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Shop Selector
            val shopsState   by viewModel.userShopsState.collectAsState()
            val selectedShop by viewModel.selectedShop.collectAsState()
            
            when (val state = shopsState) {
                is UserShopsState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                is UserShopsState.Error -> {
                    ErrorState(state.message, onRetry = { viewModel.onListingTypeChange(listingType) })
                }
                is UserShopsState.Empty -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No Shops Found", style = MaterialTheme.typography.titleMedium)
                            Text("You need to create a shop before you can add a listing.", 
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 8.dp))
                            SwiftPrimaryButton(
                                text = "Create Shop First", 
                                onClick = { /* Navigate to Create Shop - parent should handle this or use internal nav */ }
                            )
                        }
                    }
                }
                is UserShopsState.Success -> {
                    var expanded by remember { mutableStateOf(false) }
                    Text("Select Shop", style = MaterialTheme.typography.titleMedium)
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = selectedShop?.name ?: "Select Shop",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Shop") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = MaterialTheme.shapes.medium,
                            leadingIcon = {
                                if (selectedShop?.logoUrl?.isNotBlank() == true) {
                                    com.swiftshop.core.ui.components.SwiftAvatar(url = selectedShop!!.logoUrl, size = 24.dp)
                                } else {
                                    Icon(Icons.Default.Store, null)
                                }
                            }
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            state.shops.forEach { shop ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (shop.logoUrl.isNotBlank()) {
                                                com.swiftshop.core.ui.components.SwiftAvatar(url = shop.logoUrl, size = 32.dp)
                                                Spacer(Modifier.width(12.dp))
                                            }
                                            Column {
                                                Text(shop.name, style = MaterialTheme.typography.bodyMedium)
                                                Text(shop.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    },
                                    onClick = { viewModel.onShopSelected(shop); expanded = false }
                                )
                            }
                        }
                    }
                }
            }

            // Core fields
            OutlinedTextField(
                value = title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            OutlinedTextField(
                value = category,
                onValueChange = viewModel::onCategoryChange,
                label = { Text("Category") },
                modifier = Modifier.fillMaxWidth()
            )

            // Listing Type
            HorizontalDivider()
            Text("Listing Type", style = MaterialTheme.typography.titleMedium)
            
            // If the type is one of the gateway categories, we lock it to prevent confusion
            val isLocked = listingType in listOf(
                ListingType.PRODUCT, ListingType.SERVICE, ListingType.SET_APPOINTMENT, 
                ListingType.PLACE_ORDER, ListingType.DELIVER
            )

            if (!isLocked) {
                Text(
                    "Choose how buyers interact with this listing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var typeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = listingType.displayName(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = MaterialTheme.shapes.medium
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        ListingType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName(), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            type.helpText(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = { viewModel.onListingTypeChange(type); typeExpanded = false }
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = listingType.displayName(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Selected Type") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).align(Alignment.CenterVertically)
                    )
                    Text(
                        listingType.helpText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Price & Stock
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = viewModel::onPriceChange,
                    label = { Text("Price (LSL)") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = stock,
                    onValueChange = viewModel::onStockChange,
                    label = { Text("Stock") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            // Delivery estimate
            if (showDelivery) {
                OutlinedTextField(
                    value = deliveryDays,
                    onValueChange = viewModel::onDeliveryEstimateChange,
                    label = { Text("Estimated Delivery (days)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.LocalShipping, null) }
                )
            }

            // Custom Field Builder
            if (showFieldBuilder) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Form Fields", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${customFields.size} field${if (customFields.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "Add fields buyers must fill in before submitting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                customFields.forEach { field ->
                    CustomFieldEditor(
                        field = field,
                        onLabelChange    = { viewModel.updateCustomFieldLabel(field.id, it) },
                        onTypeChange     = { viewModel.updateCustomFieldType(field.id, it) },
                        onRequiredChange = { viewModel.updateCustomFieldRequired(field.id, it) },
                        onAddOption      = { viewModel.addSelectOption(field.id) },
                        onOptionChange   = { idx, v -> viewModel.updateSelectOption(field.id, idx, v) },
                        onRemove         = { viewModel.removeCustomField(field.id) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("text" to "Text", "select" to "Dropdown", "date" to "Date", "phone" to "Phone").forEach { (type, label) ->
                        OutlinedButton(
                            onClick = { viewModel.addCustomField(type) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Errors & Submit
            if (uiState is CreateListingUiState.Error) {
                Text(
                    text = (uiState as CreateListingUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Column {
                SwiftPrimaryButton(
                    text = if (canPublish) "Publish Listing" else "Limit Reached",
                    isLoading = uiState is CreateListingUiState.Loading,
                    enabled = canPublish,
                    onClick = { viewModel.submit() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (usageText.isNotEmpty()) {
                    Text(
                        text = usageText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canPublish) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomFieldEditor(
    field: com.swiftshop.core.model.CustomField,
    onLabelChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onRequiredChange: (Boolean) -> Unit,
    onAddOption: () -> Unit,
    onOptionChange: (Int, String) -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Field", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                var typeExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    AssistChip(
                        onClick = { typeExpanded = true },
                        label = { Text(field.type) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        listOf("text", "select", "date", "phone").forEach { t ->
                            DropdownMenuItem(text = { Text(t) }, onClick = { onTypeChange(t); typeExpanded = false })
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline, "Remove",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            OutlinedTextField(
                value = field.label,
                onValueChange = onLabelChange,
                label = { Text("Field label") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                singleLine = true
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = field.isRequired, onCheckedChange = onRequiredChange)
                Text("Required field", style = MaterialTheme.typography.bodySmall)
            }
            if (field.type == "select") {
                Text("Options:", style = MaterialTheme.typography.labelSmall)
                field.options.forEachIndexed { idx, option ->
                    OutlinedTextField(
                        value = option,
                        onValueChange = { onOptionChange(idx, it) },
                        label = { Text("Option ${idx + 1}") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        singleLine = true
                    )
                }
                TextButton(onClick = onAddOption) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add option", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
