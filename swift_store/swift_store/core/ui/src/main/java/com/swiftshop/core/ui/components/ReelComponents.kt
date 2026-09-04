package com.swiftshop.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.swiftshop.core.model.FeedPost

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VerticalReelsPager(
    reels: List<FeedPost>, 
    onLoadMore: () -> Unit,
    onBookmarkClick: (String) -> Unit = {}
) {
    val pagerState = rememberPagerState(pageCount = { reels.size })

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { index ->
        if (index >= reels.size - 2) {
            LaunchedEffect(Unit) { onLoadMore() }
        }
        ReelItem(
            reel = reels[index], 
            isActive = index == pagerState.currentPage,
            onBookmarkClick = { onBookmarkClick(reels[index].id) }
        )
    }
}

@Composable
fun ReelItem(
    reel: FeedPost, 
    isActive: Boolean,
    onBookmarkClick: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Thumbnail as background / loading state
        AsyncImage(
            model = reel.thumbnailUrl,
            contentDescription = "Reel thumbnail fallback",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Real video player
        if (reel.videoUrl.isNotBlank()) {
            SwiftVideoPlayer(
                url = reel.videoUrl,
                isActive = isActive,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Overlay: author + actions
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .fillMaxWidth(0.75f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SwiftAvatar(url = reel.authorAvatarUrl, tier = reel.authorTier)
                Spacer(Modifier.width(8.dp))
                Text(reel.authorName, style = MaterialTheme.typography.titleSmall,
                    color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Text(reel.caption, style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.9f), maxLines = 3)
        }

        // Right-side action column
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ReelAction(
                Icons.Default.Favorite, 
                reel.likeCount.toString(),
                tint = if (reel.isLikedByMe) Color.Red else Color.White
            )
            Spacer(Modifier.height(20.dp))
            ReelAction(Icons.Default.ChatBubble, reel.commentCount.toString())
            Spacer(Modifier.height(20.dp))
            ReelAction(Icons.Default.Share, "Share")
            Spacer(Modifier.height(20.dp))
            ReelAction(
                if (reel.isBookmarkedByMe) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, 
                "Save",
                onClick = onBookmarkClick,
                tint = if (reel.isBookmarkedByMe) MaterialTheme.colorScheme.primary else Color.White
            )
        }
    }
}

@Composable
fun ReelAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    label: String,
    onClick: () -> Unit = {},
    tint: Color = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
