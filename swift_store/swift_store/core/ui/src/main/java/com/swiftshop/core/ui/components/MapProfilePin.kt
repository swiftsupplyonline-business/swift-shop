package com.swiftshop.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.swiftshop.core.ui.theme.glow

/**
 * Pure presentation composable for a map profile pin.
 * Includes a circular image frame, glow ring, and pointed tail.
 * Follows the Swift Glow visual language.
 */
@Composable
fun MapProfilePin(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    pinSize: Dp = 48.dp,
    isCurrentUser: Boolean = false,
    onImageLoaded: (() -> Unit)? = null
) {
    val ringColor = if (isCurrentUser) MaterialTheme.glow.ring else MaterialTheme.colorScheme.primary
    
    Column(
        modifier = modifier.size(width = pinSize, height = pinSize + 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(pinSize)
                .swiftGlowCircle(width = 3.dp) // Ring as requested in Phase 10
                .padding(3.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) {
                            onImageLoaded?.invoke()
                        } else if (state is AsyncImagePainter.State.Error) {
                            onImageLoaded?.invoke() // Fallback icon is ready
                        }
                    }
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(pinSize * 0.6f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // If no image URL, use SideEffect to notify ready
                LaunchedEffect(Unit) { onImageLoaded?.invoke() }
            }
        }
        
        // Pointed tail
        Box(
            modifier = Modifier
                .offset(y = (-4).dp)
                .size(width = 12.dp, height = 12.dp)
                .clip(GenericShape { size, _ ->
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(size.width / 2f, size.height)
                    close()
                })
                .background(ringColor)
        )
    }
}
