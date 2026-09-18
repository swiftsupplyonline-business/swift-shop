package com.swiftshop.feature.shop

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.swiftshop.core.model.Listing
import com.swiftshop.core.ui.components.ErrorState
import com.swiftshop.core.ui.components.LoadingState
import com.swiftshop.core.ui.components.SwiftPrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageShopScreen(
    navController: NavController,
    viewModel: ManageShopViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val listings by viewModel.listings.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val logoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.onLogoSelected(it) }
    }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.onCoverSelected(it) }
    }

    LaunchedEffect(actionState) {
        when (actionState) {
            is ManageActionState.Success -> {
                snackbarHostState.showSnackbar("Saved successfully")
                viewModel.clearActionState()
            }
            is ManageActionState.Error -> {
                snackbarHostState.showSnackbar((actionState as ManageActionState.Error).message)
                viewModel.clearActionState()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Manage Shop") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is ManageShopUiState.Loading -> LoadingState(modifier = Modifier.padding(padding))
            is ManageShopUiState.Error -> ErrorState(state.message, onRetry = { viewModel.load() }, modifier = Modifier.padding(padding))
            is ManageShopUiState.Success -> {
                ManageShopContent(
                    shop = state.shop,
                    listings = listings,
                    actionState = actionState,
                    uploadProgress = uploadProgress,
                    padding = padding,
                    onUpdate = { n, d, c, a -> viewModel.update(n, d, c, a) },
                    onLogoClick = { logoLauncher.launch("image/*") },
                    onCoverClick = { coverLauncher.launch("image/*") },
                    onDeleteListing = { viewModel.deleteListing(it) }
                )
            }
        }
    }
}

@Composable
private fun ManageShopContent(
    shop: com.swiftshop.core.model.Shop,
    listings: List<Listing>,
    actionState: ManageActionState,
    uploadProgress: Int?,
    padding: PaddingValues,
    onUpdate: (String, String, String, String) -> Unit,
    onLogoClick: () -> Unit,
    onCoverClick: () -> Unit,
    onDeleteListing: (String) -> Unit
) {
    var name by remember { mutableStateOf(shop.name) }
    var description by remember { mutableStateOf(shop.description) }
    var category by remember { mutableStateOf(shop.category) }
    var address by remember { mutableStateOf(shop.locationAddress) }
    var listingToDelete by remember { mutableStateOf<Listing?>(null) }

    listingToDelete?.let { listing ->
        AlertDialog(
            onDismissRequest = { listingToDelete = null },
            title = { Text("Delete listing?") },
            text = { Text("\"${listing.title}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteListing(listing.id)
                    listingToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { listingToDelete = null }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // -- Cover photo ----------------------------------------------
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clickable { onCoverClick() }
            ) {
                if (shop.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = shop.coverUrl,
                        contentDescription = "Shop cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AddPhotoAlternate, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Tap to set cover photo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // Edit badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            MaterialTheme.shapes.small)
                        .padding(4.dp)
                ) {
                    Icon(Icons.Default.Edit, "Change cover",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // -- Logo -----------------------------------------------------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onLogoClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (shop.logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = shop.logoUrl,
                            contentDescription = "Shop logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Storefront, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Edit badge overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Edit, null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Shop Logo", style = MaterialTheme.typography.titleSmall)
                    Text("Tap to change", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // -- Upload progress -------------------------------------------
        if (uploadProgress != null) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Uploading… $uploadProgress%",
                        style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { uploadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }

        // -- Shop details form -----------------------------------------
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Shop Details", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Shop Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Business Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                SwiftPrimaryButton(
                    text = "Save Changes",
                    isLoading = actionState is ManageActionState.Loading,
                    enabled = name.isNotBlank() && actionState !is ManageActionState.Loading && uploadProgress == null,
                    onClick = { onUpdate(name, description, category, address) },
                    leadingIcon = { Icon(Icons.Default.Save, null) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // -- Listings section ------------------------------------------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Listings (${listings.size})",
                    style = MaterialTheme.typography.titleMedium)
            }
        }

        if (listings.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No listings yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(listings, key = { it.id }) { listing ->
                ManageListingRow(
                    listing = listing,
                    onDelete = { listingToDelete = listing }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ManageListingRow(
    listing: Listing,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = listing.imageUrls.firstOrNull(),
            contentDescription = listing.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(listing.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("M ${listing.price.minorUnits / 100}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Stock: ${listing.stockQuantity}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, "Delete",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}