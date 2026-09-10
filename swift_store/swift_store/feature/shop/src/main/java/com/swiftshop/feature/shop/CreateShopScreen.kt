package com.swiftshop.feature.shop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.swiftshop.core.ui.components.SwiftPrimaryButton
import com.swiftshop.core.ui.components.DropYourPinComponent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateShopScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: CreateShopViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val canCreate by viewModel.canCreate.collectAsState()
    val usageText by viewModel.usageText.collectAsState()
    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val category by viewModel.category.collectAsState()
    val locationAddress by viewModel.locationAddress.collectAsState()
    val logoUrl by viewModel.logoUrl.collectAsState()
    val coverUrl by viewModel.coverUrl.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val selectedLocation by viewModel.selectedLocation.collectAsState()

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onLogoSelected(it) }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onCoverSelected(it) }
    }

    LaunchedEffect(uiState) {
        if (uiState is CreateShopUiState.Success) onCreated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Shop") },
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
            Text(
                "Launch your business in Swift City.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Brand Section
            Text("Shop Branding", style = MaterialTheme.typography.titleMedium)
            
            // Cover Photo Picker
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { coverPicker.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AddPhotoAlternate, null)
                        Text("Add Cover Photo", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (uploadProgress != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                }
            }

            // Logo Picker
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { logoPicker.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    if (logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = "Logo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Store, null)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Shop Logo", style = MaterialTheme.typography.titleSmall)
                    Text("Recommended: Square 1:1", style = MaterialTheme.typography.labelSmall)
                }
            }

            HorizontalDivider()

            OutlinedTextField(
                value = name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Shop Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = category,
                onValueChange = viewModel::onCategoryChange,
                label = { Text("Category") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text("Shop Location", style = MaterialTheme.typography.titleMedium)
            DropYourPinComponent(
                modifier = Modifier.fillMaxWidth(),
                initialLocation = selectedLocation,
                onLocationConfirmed = viewModel::onLocationSelect
            )

            OutlinedTextField(
                value = locationAddress,
                onValueChange = viewModel::onLocationAddressChange,
                label = { Text("Business Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            if (uiState is CreateShopUiState.Error) {
                Text(
                    text = (uiState as CreateShopUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.weight(1f))

            Column {
                SwiftPrimaryButton(
                    text = if (canCreate) "Create Shop" else "Limit Reached",
                    isLoading = uiState is CreateShopUiState.Loading,
                    enabled = canCreate && name.isNotBlank() && uiState !is CreateShopUiState.Loading,
                    onClick = { viewModel.submit() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (usageText.isNotEmpty()) {
                    Text(
                        text = usageText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp).align(androidx.compose.ui.Alignment.CenterHorizontally),
                        color = if (canCreate) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
