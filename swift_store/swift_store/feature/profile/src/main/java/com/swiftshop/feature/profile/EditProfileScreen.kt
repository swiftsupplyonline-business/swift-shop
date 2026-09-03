package com.swiftshop.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.swiftColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(
    navController: NavController,
    viewModel: EditProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()

    val displayName by viewModel.displayName.collectAsState()
    val bio by viewModel.bio.collectAsState()
    val location by viewModel.location.collectAsState()
    val avatarUrl by viewModel.avatarUrl.collectAsState()
    val coverUrl by viewModel.coverUrl.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saveState) {
        when (saveState) {
            is EditProfileSaveState.Success -> {
                navController.popBackStack()
            }
            is EditProfileSaveState.Error -> {
                snackbarHostState.showSnackbar((saveState as EditProfileSaveState.Error).message)
                viewModel.clearSaveState()
            }
            else -> {}
        }
    }

    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onAvatarSelected(it) } }

    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.onCoverSelected(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Edit Profile") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = saveState !is EditProfileSaveState.Saving && uploadProgress == null
                    ) {
                        if (saveState is EditProfileSaveState.Saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Save", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is EditProfileUiState.Loading -> LoadingState()
            is EditProfileUiState.Error -> ErrorState(state.message, onRetry = { /* Re-load handled by init */ })
            is EditProfileUiState.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                ) {
                    // ── Cover Photo ──────────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { coverLauncher.launch("image/*") }
                    ) {
                        if (coverUrl.isNotEmpty()) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = "Cover",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // Overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PhotoCamera, null, tint = Color.White)
                                Text("Change Cover", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // ── Avatar ───────────────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .offset(y = (-40).dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape)
                                    .clickable { avatarLauncher.launch("image/*") }
                            ) {
                                SwiftAvatar(
                                    url = avatarUrl,
                                    size = 82.dp,
                                    tier = state.profile.tier
                                )
                                // Camera icon overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    // ── Upload Progress ──────────────────────────────────────
                    if (uploadProgress != null) {
                        LinearProgressIndicator(
                            progress = { uploadProgress!! / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .offset(y = (-30).dp),
                        )
                    }

                    // ── Form Fields ──────────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .offset(y = (-20).dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = displayName,
                            onValueChange = viewModel::onDisplayNameChange,
                            label = { Text("Display Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = location,
                            onValueChange = viewModel::onLocationChange,
                            label = { Text("Location") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            leadingIcon = { Icon(Icons.Default.LocationOn, null) }
                        )

                        OutlinedTextField(
                            value = bio,
                            onValueChange = viewModel::onBioChange,
                            label = { Text("Bio") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                        )

                        Spacer(Modifier.height(24.dp))

                        SwiftPrimaryButton(
                            text = "Save Changes",
                            onClick = { viewModel.save() },
                            isLoading = saveState is EditProfileSaveState.Saving,
                            enabled = uploadProgress == null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}
