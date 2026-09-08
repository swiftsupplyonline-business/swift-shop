package com.swiftshop.feature.shop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftshop.core.model.ListingType
import com.swiftshop.core.ui.theme.SwiftShopColors

// ─── Data ────────────────────────────────────────────────────────────────────

private data class GatewayOption(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientEnd: Color,
    val listingType: ListingType
)

private val gatewayOptions = listOf(
    GatewayOption(
        title = "Product",
        description = "Sell physical goods to buyers",
        icon = Icons.Default.ShoppingBag,
        gradientStart = Color(0xFF1D6BF3),
        gradientEnd = Color(0xFF00C2FF),
        listingType = ListingType.PRODUCT
    ),
    GatewayOption(
        title = "Service",
        description = "Offer a professional service",
        icon = Icons.Default.Build,
        gradientStart = Color(0xFF8B5CF6),
        gradientEnd = Color(0xFFEC4899),
        listingType = ListingType.SERVICE
    ),
    GatewayOption(
        title = "Appointment",
        description = "Let clients book time with you",
        icon = Icons.Default.CalendarMonth,
        gradientStart = Color(0xFF10B981),
        gradientEnd = Color(0xFF06B6D4),
        listingType = ListingType.SET_APPOINTMENT
    ),
    GatewayOption(
        title = "Form / Application",
        description = "Collect applications or submissions",
        icon = Icons.Default.Assignment,
        gradientStart = Color(0xFFF59E0B),
        gradientEnd = Color(0xFFEF4444),
        listingType = ListingType.PLACE_ORDER
    ),
    GatewayOption(
        title = "Delivery",
        description = "Offer delivery services",
        icon = Icons.Default.LocalShipping,
        gradientStart = Color(0xFF0D1B3E),
        gradientEnd = Color(0xFF1D6BF3),
        listingType = ListingType.DELIVER
    )
)

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateListingGatewayScreen(
    onBack: () -> Unit,
    onNavigateToForm: (ListingType) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(
                    "What would you\nlike to offer?",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Choose a listing type to get started",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Cards ────────────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                gatewayOptions.forEach { option ->
                    GatewayCard(
                        option = option,
                        onClick = { onNavigateToForm(option.listingType) }
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ─── Card ─────────────────────────────────────────────────────────────────────

@Composable
private fun GatewayCard(
    option: GatewayOption,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(100),
        label = "card_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .shadow(
                elevation = if (isPressed) 2.dp else 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = option.gradientStart.copy(alpha = 0.15f),
                spotColor = option.gradientStart.copy(alpha = 0.2f)
            )
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gradient icon box
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(option.gradientStart, option.gradientEnd)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = option.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Chevron with gradient tint
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(option.gradientStart.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = option.gradientStart,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
