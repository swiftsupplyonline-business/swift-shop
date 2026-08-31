package com.swiftshop.feature.wallet.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.swiftshop.core.model.UserTier
import com.swiftshop.core.model.Wallet
import com.swiftshop.core.ui.components.SwiftCard
import com.swiftshop.core.ui.theme.swiftColors

@Composable
fun BalanceCard(
    wallet: Wallet,
    tier: UserTier,
    balanceVisible: Boolean,
    onToggleBalance: () -> Unit,
    onDeposit: () -> Unit,
    onWithdraw: () -> Unit,
    onTransfer: () -> Unit
) {
    val colors = MaterialTheme.swiftColors
    val gradient = when (tier) {
        UserTier.BASIC -> listOf(colors.brandBlue, colors.deepNavy)
        UserTier.PREMIUM -> listOf(colors.premiumGradientStart, colors.premiumGradientEnd)
        UserTier.ELITE -> listOf(colors.eliteObsidian, Color(0xFF1A1A2E))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(gradient))
            .padding(24.dp)
            .clickable(onClick = onToggleBalance)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Available Balance", style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f))
                Icon(
                    if (balanceVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (balanceVisible) wallet.availableBalance.toDisplayString() else "•••• ••••",
                style = MaterialTheme.typography.headlineLarge,
                color = if (tier == UserTier.ELITE) colors.eliteGold else Color.White
            )
            Spacer(Modifier.height(4.dp))
            Text("${wallet.currency} · Swift Shop Wallet",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun QuickActionCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SwiftCard(
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
