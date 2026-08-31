package com.swiftshop.feature.posts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.swiftshop.core.ui.components.SwiftPrimaryButton
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CreatePostState {
    data object Idle : CreatePostState
    data object Loading : CreatePostState
    data object Success : CreatePostState
    data class Error(val message: String) : CreatePostState
}

@HiltViewModel
class CreatePostViewModel @Inject constructor(
    private val createPost: com.swiftshop.domain.feed.CreatePostUseCase,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase
) : ViewModel() {
    private val _state = MutableStateFlow<CreatePostState>(CreatePostState.Idle)
    val state = _state.asStateFlow()

    private val _imageUris = MutableStateFlow<List<Uri>>(emptyList())
    val imageUris = _imageUris.asStateFlow()

    private val _caption = MutableStateFlow("")
    val caption = _caption.asStateFlow()

    private val _tagInput = MutableStateFlow("")
    val tagInput = _tagInput.asStateFlow()

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags = _tags.asStateFlow()

    fun addImages(uris: List<Uri>) { _imageUris.value = (_imageUris.value + uris).take(10) }
    fun removeImage(uri: Uri) { _imageUris.value = _imageUris.value - uri }
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
            if (_imageUris.value.isEmpty()) {
                _state.value = CreatePostState.Error("Add at least one image")
                return@launch
            }
            _state.value = CreatePostState.Loading
            
            val user = observeCurrentUser().first()
            if (user == null || user.uid.isBlank()) {
                _state.value = CreatePostState.Error("User not authenticated")
                return@launch
            }

            createPost(
                authorId = user.uid,
                authorName = user.displayName,
                authorAvatarUrl = user.photoUrl,
                authorTier = user.tier,
                shopId = "", 
                type = com.swiftshop.core.model.PostType.IMAGE,
                caption = _caption.value,
                mediaUris = _imageUris.value,
                hashtags = _tags.value
            ).onSuccess {
                _state.value = CreatePostState.Success
                onSuccess()
            }.onFailure {
                _state.value = CreatePostState.Error(it.message ?: "Failed to publish post")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePostScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: CreatePostViewModel = hiltViewModel()
) {
    val state     by viewModel.state.collectAsState()
    val imageUris by viewModel.imageUris.collectAsState()
    val caption   by viewModel.caption.collectAsState()
    val tagInput  by viewModel.tagInput.collectAsState()
    val tags      by viewModel.tags.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> viewModel.addImages(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Post") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.publish(onCreated) },
                        enabled = state !is CreatePostState.Loading && imageUris.isNotEmpty()
                    ) {
                        if (state is CreatePostState.Loading)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .clickable { launcher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.AddPhotoAlternate, null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text("Add Photos",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                items(imageUris) { uri ->
                    Box(modifier = Modifier.size(110.dp)) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                        )
                        IconButton(
                            onClick = { viewModel.removeImage(uri) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(28.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                        ) {
                            Icon(Icons.Default.Close, null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = caption,
                onValueChange = viewModel::onCaptionChange,
                label = { Text("Write a caption…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                minLines = 4, maxLines = 8,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )
            Text("${caption.length} / 2200",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End).padding(end = 16.dp))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Hashtags", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = viewModel::onTagInputChange,
                        label = { Text("Add tag") },
                        leadingIcon = {
                            Text("#", style = MaterialTheme.typography.bodyLarge,
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
                        Icon(Icons.Default.Add, "Add tag",
                            tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
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
                Text("${tags.size}/10 tags",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }

            if (state is CreatePostState.Error) {
                Text((state as CreatePostState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }

            SwiftPrimaryButton(
                text = "Share Post",
                isLoading = state is CreatePostState.Loading,
                enabled = imageUris.isNotEmpty() && state !is CreatePostState.Loading,
                onClick = { viewModel.publish(onCreated) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(navController: androidx.navigation.NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Post") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text("Post Detail", modifier = Modifier.padding(16.dp))
        }
    }
}
