package com.swiftshop.feature.shop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.swiftshop.core.ui.components.LoadingState
import com.swiftshop.core.ui.components.SwiftPrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditListingScreen(
    onBack: () -> Unit,
    onUpdated: () -> Unit,
    viewModel: EditListingViewModel = hiltViewModel()
) {
    val uiState          by viewModel.uiState.collectAsState()
    val title            by viewModel.title.collectAsState()
    val description      by viewModel.description.collectAsState()
    val category         by viewModel.category.collectAsState()
    val price            by viewModel.priceMajor.collectAsState()
    val totalQuantity    by viewModel.totalQuantity.collectAsState()
    val existingUrls     by viewModel.existingImageUrls.collectAsState()
    val newUris          by viewModel.newImageUris.collectAsState()
    val isAvailable      by viewModel.isAvailable.collectAsState()
    val deliveryDays     by viewModel.deliveryEstimateDays.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.addImages(uris) }

    LaunchedEffect(uiState) {
        if (uiState is EditListingUiState.Success) onUpdated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Listing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (uiState) {
            is EditListingUiState.Loading -> LoadingState(modifier = Modifier.padding(padding))
            is EditListingUiState.Error -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text((uiState as EditListingUiState.Error).message, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Images Section
                    Text("Images", style = MaterialTheme.typography.titleMedium)
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
                        // Existing (Network)
                        items(existingUrls) { url ->
                            Box(modifier = Modifier.size(100.dp)) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { viewModel.removeExistingImage(url) },
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        // New (Uris)
                        items(newUris) { uri ->
                            Box(modifier = Modifier.size(100.dp)) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { viewModel.removeNewImage(uri) },
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    // Fields
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

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = price,
                            onValueChange = viewModel::onPriceChange,
                            label = { Text("Price (LSL)") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = totalQuantity,
                            onValueChange = viewModel::onStockChange,
                            label = { Text("Total Stock") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }

                    OutlinedTextField(
                        value = deliveryDays,
                        onValueChange = viewModel::onDeliveryEstimateChange,
                        label = { Text("Delivery Days") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = isAvailable, onCheckedChange = viewModel::onAvailableChange)
                        Spacer(Modifier.width(8.dp))
                        Text("Available for sale")
                    }

                    Spacer(Modifier.height(16.dp))

                    SwiftPrimaryButton(
                        text = "Save Changes",
                        isLoading = uiState is EditListingUiState.Loading,
                        onClick = { viewModel.submit() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
