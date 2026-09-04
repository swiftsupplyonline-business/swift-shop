package com.swiftshop.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage
import com.swiftshop.core.model.FeedPost
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.SwiftEntity

@Composable
fun ListingCard(
    listing: Listing, 
    onClick: () -> Unit,
    onBookmarkClick: () -> Unit = {}
) {
    SwiftCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box {
            Column {
                AsyncImage(
                    model = listing.imageUrls.firstOrNull(),
                    contentDescription = listing.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                Column(modifier = Modifier.padding(10.dp)) {
                    if (listing.isSponsored) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SwiftEntityIcon(
                                entity = SwiftEntity.PRODUCT,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Sponsored", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
            // Bookmark overlay
            IconButton(
                onClick = onBookmarkClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            ) {
                Icon(
                    if (listing.isBookmarkedByMe) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = "Bookmark",
                    modifier = Modifier.size(18.dp),
                    tint = if (listing.isBookmarkedByMe) MaterialTheme.colorScheme.primary else LocalContentColor.current
                )
            }
        }
    }
}

@Composable
fun PostCard(
    post: FeedPost,
    onClick: () -> Unit,
    onUserClick: () -> Unit,
    onLikeClick: () -> Unit,
    onBookmarkClick: () -> Unit = {}
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
                        "just now", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (post.isSponsored) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Ad", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            SwiftEntityIcon(
                                entity = SwiftEntity.PRODUCT,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            if (post.mediaUrls.isNotEmpty()) {
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
                IconButton(onClick = onBookmarkClick) {
                    Icon(
                        if (post.isBookmarkedByMe) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        "Bookmark",
                        tint = if (post.isBookmarkedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {}) {
                    Icon(Icons.Default.Share, "Share")
                }
            }
        }
    }
}
