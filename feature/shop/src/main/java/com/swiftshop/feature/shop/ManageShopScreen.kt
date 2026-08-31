package com.swiftshop.feature.shop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
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
                ManageShopContent(
                    shop = state.shop,
                    actionState = actionState,
                    padding = padding,
                    onUpdate = { n, d, c, a -> viewModel.update(n, d, c, a) }
                )
            }
        }
    }
}

@Composable
private fun ManageShopContent(
    shop: com.swiftshop.core.model.Shop,
    actionState: ManageActionState,
    padding: PaddingValues,
    onUpdate: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(shop.name) }
    var description by remember { mutableStateOf(shop.description) }
    var category by remember { mutableStateOf(shop.category) }
    var address by remember { mutableStateOf(shop.locationAddress) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
