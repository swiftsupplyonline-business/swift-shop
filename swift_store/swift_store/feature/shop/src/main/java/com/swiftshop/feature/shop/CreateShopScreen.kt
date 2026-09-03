package com.swiftshop.feature.shop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.swiftshop.core.ui.components.SwiftPrimaryButton

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
