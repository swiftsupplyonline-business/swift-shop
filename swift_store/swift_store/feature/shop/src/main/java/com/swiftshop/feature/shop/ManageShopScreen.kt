package com.swiftshop.feature.shop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionState) {
        if (actionState is ManageActionState.Success) {
            snackbarHostState.showSnackbar("Shop updated successfully")
            viewModel.clearActionState()
        } else if (actionState is ManageActionState.Error) {
            snackbarHostState.showSnackbar((actionState as ManageActionState.Error).message)
            viewModel.clearActionState()
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
                val logoUrl by viewModel.logoUrl.collectAsState()
                val coverUrl by viewModel.coverUrl.collectAsState()
                val uploadProgress by viewModel.uploadProgress.collectAsState()
                
                ManageShopContent(
                    shop = state.shop,
                    actionState = actionState,
                    logoUrl = logoUrl,
                    coverUrl = coverUrl,
                    uploadProgress = uploadProgress,
                    padding = padding,
                    onUpdate = { n, d, c, a -> viewModel.update(n, d, c, a) },
                    onLogoSelect = viewModel::onLogoSelected,
                    onCoverSelect = viewModel::onCoverSelected
                )
            }
        }
    }
}

@Composable
private fun ManageShopContent(
    shop: com.swiftshop.core.model.Shop,
    actionState: ManageActionState,
    logoUrl: String,
    coverUrl: String,
    uploadProgress: Int?,
    padding: PaddingValues,
    onUpdate: (String, String, String, String) -> Unit,
    onLogoSelect: (android.net.Uri) -> Unit,
    onCoverSelect: (android.net.Uri) -> Unit
) {
    var name by remember { mutableStateOf(shop.name) }
    var description by remember { mutableStateOf(shop.description) }
    var category by remember { mutableStateOf(shop.category) }
    var address by remember { mutableStateOf(shop.locationAddress) }

    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onLogoSelect(it) }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onCoverSelect(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Media Section
        Text("Shop Branding", style = MaterialTheme.typography.titleMedium)
        
        // Cover Photo Picker
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { coverPicker.launch("image/*") }
        ) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "Cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.PhotoCamera,
                    null,
                    tint = Color.White,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
            if (uploadProgress != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                )
            }
        }

        // Logo Picker
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(80.dp)
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
                    Icon(Icons.Default.Store, null, modifier = Modifier.size(40.dp))
                }
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Edit, null, tint = Color.White, modifier = Modifier.padding(24.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Shop Logo", style = MaterialTheme.typography.titleSmall)
                Text("Tap to change", style = MaterialTheme.typography.labelSmall)
            }
        }

        HorizontalDivider()

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
            minLines = 4
        )

        Spacer(Modifier.weight(1f))

        SwiftPrimaryButton(
            text = "Save Changes",
            isLoading = actionState is ManageActionState.Loading,
            enabled = name.isNotBlank() && actionState !is ManageActionState.Loading,
            onClick = { onUpdate(name, description, category, address) },
            leadingIcon = { Icon(Icons.Default.Save, null) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
