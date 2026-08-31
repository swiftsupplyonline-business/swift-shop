package com.swiftshop.feature.reels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.swiftshop.core.ui.components.SwiftPrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CreateReelState {
    data object Idle : CreateReelState
    data object Loading : CreateReelState
    data object Success : CreateReelState
    data class Error(val message: String) : CreateReelState
}

@HiltViewModel
class CreateReelViewModel @Inject constructor(
    private val createPost: com.swiftshop.domain.feed.CreatePostUseCase,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase
) : ViewModel() {
    private val _state = MutableStateFlow<CreateReelState>(CreateReelState.Idle)
    val state = _state.asStateFlow()

    private val _videoUri = MutableStateFlow<Uri?>(null)
    val videoUri = _videoUri.asStateFlow()

    private val _caption = MutableStateFlow("")
    val caption = _caption.asStateFlow()

    private val _tagInput = MutableStateFlow("")
    val tagInput = _tagInput.asStateFlow()

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags = _tags.asStateFlow()

    fun onVideoSelected(uri: Uri) { _videoUri.value = uri }
    fun clearVideo() { _videoUri.value = null }
    fun onCaptionChange(v: String) { _caption.value = v }
    fun onTagInputChange(v: String) { _tagInput.value = v.removePrefix("#") }

    fun addTag() {
        val tag = _tagInput.value.trim().lowercase().replace(" ", "_")
        if (tag.isNotEmpty() && tag !in _tags.value && _tags.value.size < 10) {
            _tags.value = _tags.value + tag
            _tagInput.value = ""
        }
    }

    fun removeTag(tag: String) { _tags.value = _tags.value - tag }

    fun publish(onSuccess: () -> Unit) {
        viewModelScope.launch {
            if (_videoUri.value == null) {
                _state.value = CreateReelState.Error("Select a video first")
                return@launch
            }
            _state.value = CreateReelState.Loading
            
            val user = observeCurrentUser().first()
            if (user == null || user.uid.isBlank()) {
                _state.value = CreateReelState.Error("User not authenticated")
                return@launch
            }

            createPost(
                authorId = user.uid,
                authorName = user.displayName,
                authorAvatarUrl = user.photoUrl,
                authorTier = user.tier,
                shopId = "", 
                type = com.swiftshop.core.model.PostType.REEL,
                caption = _caption.value,
                mediaUris = listOfNotNull(_videoUri.value),
                hashtags = _tags.value
            ).onSuccess {
                _state.value = CreateReelState.Success
                onSuccess()
            }.onFailure {
                _state.value = CreateReelState.Error(it.message ?: "Failed to publish reel")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateReelScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: CreateReelViewModel = hiltViewModel()
) {
    val state    by viewModel.state.collectAsState()
    val videoUri by viewModel.videoUri.collectAsState()
    val caption  by viewModel.caption.collectAsState()
    val tagInput by viewModel.tagInput.collectAsState()
    val tags     by viewModel.tags.collectAsState()

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onVideoSelected(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Reel") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.publish(onCreated) },
                        enabled = state !is CreateReelState.Loading && videoUri != null
                    ) {
                        if (state is CreateReelState.Loading)
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        else
                            Text("Share", style = MaterialTheme.typography.titleMedium)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Video picker
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        if (videoUri != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(16.dp)
                    )
                    .clickable { videoLauncher.launch("video/*") },
                contentAlignment = Alignment.Center
            ) {
                if (videoUri != null) {
                    // Show thumbnail via Coil (handles video frames on Android)
                    AsyncImage(
                        model = videoUri,
                        contentDescription = "Video preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PlayCircle, null,
                                tint = Color.White,
                                modifier = Modifier.size(56.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Tap to change video",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White)
                        }
                    }
                    // Remove button
                    IconButton(
                        onClick = { viewModel.clearVideo() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Close, null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp))
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.VideoLibrary, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text("Tap to select a video",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text("MP4, MOV · Max 60 seconds recommended",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Info banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp))
                    Text("Videos will be compressed and optimised before upload.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }

            // Caption
            OutlinedTextField(
                value = caption,
                onValueChange = viewModel::onCaptionChange,
                label = { Text("Write a caption…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                minLines = 3,
                maxLines = 6,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Hashtag input
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Hashtags", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = viewModel::onTagInputChange,
                        label = { Text("Add tag") },
                        leadingIcon = {
                            Text("#",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp))
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { viewModel.addTag() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Add, "Add tag", tint = Color.White)
                    }
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { viewModel.removeTag(tag) },
                                label = { Text("#$tag") },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, null,
                                        modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }
            }

            if (state is CreateReelState.Error) {
                Text(
                    (state as CreateReelState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            SwiftPrimaryButton(
                text = "Share Reel",
                isLoading = state is CreateReelState.Loading,
                enabled = videoUri != null && state !is CreateReelState.Loading,
                onClick = { viewModel.publish(onCreated) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
