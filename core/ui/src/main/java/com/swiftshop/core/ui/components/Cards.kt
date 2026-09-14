package com.swiftshop.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil.compose.AsyncImage
import com.swiftshop.core.model.FeedPost
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.PostType

private fun formatRelativeTime(createdAt: Long): String {
    val diff = System.currentTimeMillis() - createdAt
    val diffSecs = diff / 1000
    if (diffSecs < 60) return "just now"
    val diffMins = diffSecs / 60
    if (diffMins < 60) return "${diffMins}m"
    val diffHours = diffMins / 60
    if (diffHours < 24) return "${diffHours}h"
    val diffDays = diffHours / 24
    if (diffDays < 7) return "${diffDays}d"
    val diffWeeks = diffDays / 7
    return "${diffWeeks}w"
}

@Composable
fun ListingCard(listing: Listing, onClick: () -> Unit) {
    SwiftCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            AsyncImage(
                model = listing.imageUrls.firstOrNull(),
                contentDescription = listing.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            )
            Column(modifier = Modifier.padding(10.dp)) {
                if (listing.isSponsored) {
                    Text(
                        "Sponsored", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    listing.title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listing.price.toDisplayString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (listing.commitmentCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${listing.commitmentCount} buyers",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PostCard(
    post: FeedPost,
    onClick: () -> Unit,
    onUserClick: () -> Unit,
    onLikeClick: () -> Unit
) {
    SwiftCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column {
            // Author row
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SwiftAvatar(
                    url = post.authorAvatarUrl,
                    tier = post.authorTier,
                    modifier = Modifier.clickable { onUserClick() }
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(post.authorName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        formatRelativeTime(post.createdAt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (post.isSponsored) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Ad", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (post.type == PostType.REEL) {
                var hasStarted by remember { mutableStateOf(false) }
                var isPlaying by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.Black)
                        .clickable(enabled = hasStarted) { isPlaying = !isPlaying },
                    contentAlignment = Alignment.Center
                ) {
                    if (hasStarted) {
                        SwiftVideoPlayer(
                            url = post.videoUrl,
                            isActive = isPlaying,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = post.thumbnailUrl,
                            contentDescription = "Reel thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clickable {
                                hasStarted = true
                                isPlaying = true
                            }
                        )
                        IconButton(
                            onClick = {
                                hasStarted = true
                                isPlaying = true
                            },
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play video",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                }
            } else if (post.mediaUrls.isNotEmpty()) {
                AsyncImage(
                    model = post.mediaUrls.first(),
                    contentDescription = "Post image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onClick() }
                )
            }

            if (post.caption.isNotEmpty()) {
                Text(
                    post.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLikeClick) {
                    Icon(
                        if (post.isLikedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        "Like",
                        tint = if (post.isLikedByMe) Color.Red else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(post.likeCount.toString(), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClick) {
                    Icon(Icons.Default.ChatBubbleOutline, "Comment")
                }
                Text(post.commentCount.toString(), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {}) {
                    Icon(Icons.Default.BookmarkBorder, "Bookmark")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {}) {
                    Icon(Icons.Default.Share, "Share")
                }
            }
        }
    }
}
