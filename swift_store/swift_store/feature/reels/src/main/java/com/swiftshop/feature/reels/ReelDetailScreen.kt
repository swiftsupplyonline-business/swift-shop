package com.swiftshop.feature.reels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.domain.feed.GetPostUseCase
import com.swiftshop.domain.feed.LikePostUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReelDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getPost: GetPostUseCase,
    private val deleteContent: com.swiftshop.domain.feed.DeleteContentUseCase,
    private val likePost: LikePostUseCase,
    private val toggleBookmark: com.swiftshop.domain.feed.ToggleBookmarkUseCase,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase,
    private val getComments: com.swiftshop.domain.feed.GetCommentsUseCase,
    private val getReplies: com.swiftshop.domain.feed.GetRepliesUseCase,
    private val addComment: com.swiftshop.domain.feed.AddCommentUseCase,
    private val deleteComment: com.swiftshop.domain.feed.DeleteCommentUseCase
) : ViewModel() {
    private val reelId: String = checkNotNull(savedStateHandle["reelId"])

    private val _uiState = MutableStateFlow<UiState<FeedPost>>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments = _comments.asStateFlow()

    private val _replies = MutableStateFlow<Map<String, List<Comment>>>(emptyMap())
    val replies = _replies.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId = _currentUserId.asStateFlow()

    init { 
        load() 
        loadComments()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            combine(
                flow { emit(getPost(reelId)) },
                observeCurrentUser()
            ) { postResult, user ->
                _currentUserId.value = user?.uid
                if (postResult.isSuccess) {
                    val post = postResult.getOrThrow()
                    if (post.type != PostType.REEL) {
                        UiState.Error("Invalid content type")
                    } else {
                        UiState.Success(post)
                    }
                } else {
                    UiState.Error(postResult.exceptionOrNull()?.message ?: "Failed to load reel")
                }
            }.collect { _uiState.value = it }
        }
    }

    private fun loadComments() {
        viewModelScope.launch {
            getComments(reelId).collect { rootComments ->
                _comments.value = rootComments
                rootComments.forEach { root ->
                    observeReplies(root.id)
                }
            }
        }
    }

    private fun observeReplies(parentId: String) {
        viewModelScope.launch {
            getReplies(reelId, parentId).collect { replyList ->
                _replies.value = _replies.value + (parentId to replyList)
            }
        }
    }

    fun postComment(text: String, parentId: String? = null) {
        val uid = _currentUserId.value ?: return
        viewModelScope.launch {
            val comment = Comment(
                postId = reelId,
                authorId = uid,
                text = text,
                parentCommentId = parentId ?: "",
                createdAt = System.currentTimeMillis()
            )
            addComment(comment)
        }
    }

    fun removeComment(comment: Comment) {
        viewModelScope.launch {
            deleteComment(comment.id, reelId, comment.parentCommentId.ifBlank { null })
        }
    }

    fun deleteReel(onDeleted: () -> Unit) {
        val reel = (_uiState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            if (reel.authorId == _currentUserId.value) {
                deleteContent(reel).fold(
                    onSuccess = { onDeleted() },
                    onFailure = { /* simple error handling omitted as per rules */ }
                )
            }
        }
    }

    fun toggleLike() {
        val reel = (_uiState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            likePost(reel.id, !reel.isLikedByMe).onSuccess {
                _uiState.value = UiState.Success(reel.copy(
                    isLikedByMe = !reel.isLikedByMe,
                    likeCount = if (reel.isLikedByMe) reel.likeCount - 1 else reel.likeCount + 1
                ))
            }
        }
    }

    fun toggleBookmark() {
        val reel = (_uiState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            val user = observeCurrentUser().first() ?: return@launch
            toggleBookmark(user.uid, reel.id, reel.type.name).onSuccess {
                _uiState.value = UiState.Success(reel.copy(
                    isBookmarkedByMe = !reel.isBookmarkedByMe,
                    bookmarkCount = if (reel.isBookmarkedByMe) reel.bookmarkCount - 1 else reel.bookmarkCount + 1
                ))
            }
        }
    }
}

@Composable
fun ReelDetailScreen(
    onBack: () -> Unit,
    viewModel: ReelDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Reel?") },
            text = { Text("This will permanently delete this reel and its video. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteReel { onBack() }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val state = uiState) {
            is UiState.Loading -> LoadingState(modifier = Modifier.align(Alignment.Center))
            is UiState.Error -> ErrorState(state.message, onRetry = viewModel::load)
            is UiState.Success -> {
                val reel = state.data
                var showComments by remember { mutableStateOf(false) }
                // Use the existing ReelItem component
                ReelItem(
                    reel = reel, 
                    isActive = true,
                    onBookmarkClick = viewModel::toggleBookmark,
                    onCommentClick = { showComments = true }
                )
                
                if (showComments) {
                    val comments by viewModel.comments.collectAsState()
                    val replies by viewModel.replies.collectAsState()
                    val currentUserId by viewModel.currentUserId.collectAsState()
                    CommentBottomSheet(
                        postId = reel.id,
                        onDismissRequest = { showComments = false },
                        comments = comments,
                        replies = replies,
                        onSendComment = viewModel::postComment,
                        onDeleteComment = viewModel::removeComment,
                        currentUserId = currentUserId
                    )
                }
                
                // Override/Add Detail-specific controls if needed
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }

                if (reel.authorId == currentUserId) {
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(16.dp)
                            .align(Alignment.TopEnd)
                    ) {
                        Icon(Icons.Default.Delete, "Delete", tint = Color.White)
                    }
                }
            }
            else -> Unit
        }
    }
}
