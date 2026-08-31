package com.swiftshop.core.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.swiftshop.core.model.UserTier
import com.swiftshop.core.ui.theme.SwiftShopColors
import com.swiftshop.core.ui.theme.swiftColors

// ─── Tier Badge ───────────────────────────────────────────────────────────────

@Composable
fun TierBadge(tier: UserTier, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.swiftColors
    val (label, gradient) = when (tier) {
        UserTier.BASIC -> "B" to listOf(colors.brandBlue, colors.brandBlue)
        UserTier.PREMIUM -> "P" to listOf(colors.premiumGradientStart, colors.premiumGradientEnd)
        UserTier.ELITE -> "E" to listOf(colors.eliteObsidian, colors.eliteGold)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "badge_shimmer")
    val shimmerOffset by if (tier != UserTier.BASIC) {
        infiniteTransition.animateFloat(
            initialValue = -1f, targetValue = 2f,
            animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
            label = "shimmer"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(gradient))
            .border(
                width = if (tier == UserTier.ELITE) 1.5.dp else 0.dp,
                brush = Brush.linearGradient(listOf(colors.eliteGold, colors.eliteGoldLight)),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (tier == UserTier.ELITE) colors.eliteGold else Color.White
        )
    }
}

// ─── Avatar ───────────────────────────────────────────────────────────────────

@Composable
fun SwiftAvatar(
    url: String,
    size: Dp = 40.dp,
    tier: UserTier = UserTier.BASIC,
    modifier: Modifier = Modifier
) {
    val borderBrush = when (tier) {
        UserTier.BASIC -> null
        UserTier.PREMIUM -> Brush.linearGradient(
            listOf(SwiftShopColors.BrandBlue, SwiftShopColors.ElectricBlue)
        )
        UserTier.ELITE -> Brush.linearGradient(
            listOf(SwiftShopColors.EliteObsidian, SwiftShopColors.EliteGold)
        )
    }

    Box(modifier = modifier.size(size + if (tier != UserTier.BASIC) 4.dp else 0.dp)) {
        val placeholder = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.AccountCircle)
        AsyncImage(
            model = url,
            contentDescription = "Avatar",
            contentScale = ContentScale.Crop,
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .let { m ->
                    if (borderBrush != null) m.border(2.dp, borderBrush, CircleShape)
                    else m
                }
                .align(Alignment.Center)
        )
        if (tier != UserTier.BASIC) {
            TierBadge(
                tier = tier,
                size = 16.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
            )
        }
    }
}

// ─── Primary Button ───────────────────────────────────────────────────────────

@Composable
fun SwiftPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.height(52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

// ─── Gradient Button ─────────────────────────────────────────────────────────

@Composable
fun SwiftGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val colors = MaterialTheme.swiftColors
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                Brush.horizontalGradient(
                    listOf(colors.premiumGradientStart, colors.premiumGradientEnd)
                )
            )
            .clickable(enabled = enabled && !isLoading) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

// ─── Card ─────────────────────────────────────────────────────────────────────

@Composable
fun SwiftCard(
    modifier: Modifier = Modifier,
    elevation: Dp = 2.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        content = content
    )
}

// ─── Loading / Empty / Error States ──────────────────────────────────────────

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Loading…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String = "",
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground)
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                action()
            }
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            SwiftPrimaryButton("Try again", onClick = onRetry)
        }
    }
}

@Composable
fun OfflineState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.WifiOff, contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(16.dp))
            Text("You're offline", style = MaterialTheme.typography.titleMedium)
            Text("Showing cached content", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─── Shimmer Placeholder ──────────────────────────────────────────────────────

@Composable
fun ShimmerBox(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.medium) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -300f, targetValue = 300f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmer_x"
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFE2E8F0), Color(0xFFF1F5F9), Color(0xFFE2E8F0)
                    ),
                    start = Offset(translateX, 0f),
                    end = Offset(translateX + 300f, 300f)
                )
            )
    )
}

// ─── Search Bar ───────────────────────────────────────────────────────────────

@Composable
fun SwiftSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search Swift Shop…",
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {}
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, "Clear", modifier = Modifier.size(18.dp))
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = modifier.fillMaxWidth().height(52.dp),
        textStyle = MaterialTheme.typography.bodyMedium
    )
}

// ─── Sync Status Indicator ────────────────────────────────────────────────────

enum class SyncStatus { LOCAL, SYNCING, SYNCED, FAILED }

@Composable
fun SyncStatusDot(status: SyncStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        SyncStatus.LOCAL -> SwiftShopColors.Warning
        SyncStatus.SYNCING -> SwiftShopColors.BrandBlue
        SyncStatus.SYNCED -> SwiftShopColors.Success
        SyncStatus.FAILED -> SwiftShopColors.Error
    }
    Box(modifier = modifier.size(8.dp).clip(CircleShape).background(color))
}
