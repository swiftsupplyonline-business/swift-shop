package com.swiftshop.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swiftshop.core.model.Comment
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CommentItem(
    comment: Comment,
    replies: List<Comment> = emptyList(),
    onReplyClick: (Comment) -> Unit,
    onDeleteClick: (Comment) -> Unit,
    isOwner: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            SwiftAvatar(url = comment.authorAvatarUrl, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(comment.authorName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    val dateStr = try {
                        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(comment.createdAt))
                    } catch (e: Exception) { "" }
                    
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "Reply",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onReplyClick(comment) }
                    )
                    if (isOwner) {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = "Delete",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { onDeleteClick(comment) }
                        )
                    }
                }
            }
        }
        
        if (replies.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 44.dp, top = 8.dp)) {
                replies.forEach { reply ->
                    ReplyItem(reply = reply, onDeleteClick = { onDeleteClick(reply) }, isOwner = isOwner)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun ReplyItem(
    reply: Comment,
    onDeleteClick: () -> Unit,
    isOwner: Boolean = false
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        SwiftAvatar(url = reply.authorAvatarUrl, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(reply.authorName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(reply.text, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val timeStr = try {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reply.createdAt))
                } catch (e: Exception) { "" }
                
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isOwner) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { onDeleteClick() }
                    )
                }
            }
        }
    }
}

@Composable
fun CommentComposer(
    onSend: (String) -> Unit,
    replyingTo: String? = null,
    onCancelReply: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    
    Surface(tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp).navigationBarsPadding()) {
            if (replyingTo != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                ) {
                    Text(
                        "Replying to $replyingTo",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCancelReply, modifier = Modifier.size(16.dp)) {
                        Icon(Icons.Default.Close, null)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Add a comment...") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                IconButton(
                    onClick = { if (text.isNotBlank()) { onSend(text); text = "" } },
                    enabled = text.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentBottomSheet(
    postId: String,
    onDismissRequest: () -> Unit,
    comments: List<Comment>,
    replies: Map<String, List<Comment>>,
    onSendComment: (String, String?) -> Unit,
    onDeleteComment: (Comment) -> Unit,
    currentUserId: String? = null
) {
    var replyingTo by remember { mutableStateOf<Comment?>(null) }
    
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Comments",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
            
            Box(modifier = Modifier.weight(1f)) {
                if (comments.isEmpty()) {
                    EmptyState(title = "No comments yet", subtitle = "Be the first to share your thoughts!")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(comments, key = { it.id }) { comment ->
                            CommentItem(
                                comment = comment,
                                replies = replies[comment.id] ?: emptyList(),
                                onReplyClick = { replyingTo = it },
                                onDeleteClick = onDeleteComment,
                                isOwner = comment.authorId == currentUserId
                            )
                        }
                    }
                }
            }
            
            CommentComposer(
                onSend = { text -> 
                    onSendComment(text, replyingTo?.id)
                    replyingTo = null
                },
                replyingTo = replyingTo?.authorName,
                onCancelReply = { replyingTo = null }
            )
        }
    }
}
