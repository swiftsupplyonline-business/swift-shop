package com.swiftshop.feature.profile.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
//  WalletCard
//  Drop-in replacement for the blue wallet card in ProfileScreen.
//
//  Fix applied: balance is now rendered via a proper MoneyAmount formatter
//  (formatWalletBalance) instead of relying on a String that can be
//  mis-encoded. The caller passes Long minorUnits; this composable owns
//  the display logic completely.
//
//  Usage:
//      WalletCard(
//          tierLabel    = "BASIC WALLET",
//          avatarLetter = "B",
//          balanceMinorUnits = walletState.balanceMinorUnits,   // Long, e.g. 125050L = M 1,250.50
//          currencyCode = "LSL",
//          onDeposit    = { /* nav */ },
//          onWithdraw   = { /* nav */ },
//          onTransfer   = { /* nav */ },
//      )
// ─────────────────────────────────────────────────────────────────────────────

private val CardGradient = Brush.linearGradient(
    colorStops = arrayOf(
        0.00f to Color(0xFF1A3A8F),   // deep royal blue
        0.45f to Color(0xFF1565C0),   // mid blue
        0.80f to Color(0xFF0D47A1),   // dark navy-blue
        1.00f to Color(0xFF0A2472),   // near-midnight
    ),
    start = Offset(0f, 0f),
    end   = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

private val AccentGlow = Color(0xFF5B9BFF)
private val ButtonSurface = Color(0x33FFFFFF)  // 20 % white
private val ButtonBorder  = Color(0x55FFFFFF)  // 33 % white

/** Format Long minorUnits (×100) as "M 1,250.50" */
private fun formatWalletBalance(minorUnits: Long, currencyCode: String): String {
    val prefix = when (currencyCode.uppercase()) {
        "LSL", "ZAR" -> "M"
        "USD"        -> "$"
        "EUR"        -> "€"
        "GBP"        -> "£"
        else         -> currencyCode
    }
    val whole   = minorUnits / 100L
    val cents   = Math.abs(minorUnits % 100L)
    val formatted = buildString {
        val s = whole.toString()
        var count = 0
        for (i in s.indices.reversed()) {
            if (count > 0 && count % 3 == 0 && s[i] != '-') append(',')
            append(s[i])
            count++
        }
        reverse()
    }
    return "$prefix $formatted.${cents.toString().padStart(2, '0')}"
}

@Composable
fun WalletCard(
    tierLabel        : String = "BASIC WALLET",
    avatarLetter     : String = "B",
    balanceMinorUnits: Long   = 0L,
    currencyCode     : String = "LSL",
    onDeposit        : () -> Unit = {},
    onWithdraw       : () -> Unit = {},
    onTransfer       : () -> Unit = {},
    modifier         : Modifier = Modifier,
) {
    val balanceText = formatWalletBalance(balanceMinorUnits, currencyCode)

    // Subtle shimmer across the card on first compose
    val shimmerAnim = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by shimmerAnim.animateFloat(
        initialValue   = -600f,
        targetValue    = 1200f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerX",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(CardGradient)
            .drawBehind {
                // Orb glow — top-right
                drawCircle(
                    brush  = Brush.radialGradient(
                        colors = listOf(AccentGlow.copy(alpha = 0.25f), Color.Transparent),
                        center = Offset(size.width * 0.82f, size.height * 0.18f),
                        radius = size.width * 0.55f,
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width * 0.82f, size.height * 0.18f),
                )
                // Orb glow — bottom-left
                drawCircle(
                    brush  = Brush.radialGradient(
                        colors = listOf(Color(0xFF3F51B5).copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.88f),
                        radius = size.width * 0.40f,
                    ),
                    radius = size.width * 0.40f,
                    center = Offset(size.width * 0.12f, size.height * 0.88f),
                )
                // Shimmer sweep
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        start = Offset(shimmerX, 0f),
                        end   = Offset(shimmerX + 300f, size.height),
                    ),
                )
            }
            .padding(horizontal = 24.dp, vertical = 22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {

            // ── Row 1: tier label + avatar ────────────────────────────────
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment   = Alignment.CenterVertically,
            ) {
                val labelText = if (tierLabel.isBlank()) "WALLET" else tierLabel
                Text(
                    text  = labelText,
                    style = TextStyle(
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        color      = Color.White.copy(alpha = 0.65f),
                    ),
                )
                Box(
                    modifier        = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AccentGlow.copy(alpha = 0.30f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = avatarLetter,
                        style = TextStyle(
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Row 2: "Available balance" label ─────────────────────────
            Text(
                text  = "Available balance",
                style = TextStyle(
                    fontSize  = 12.sp,
                    color     = Color.White.copy(alpha = 0.55f),
                    fontWeight = FontWeight.Normal,
                ),
            )

            Spacer(Modifier.height(6.dp))

            // ── Row 3: balance amount ─────────────────────────────────────
            Text(
                text  = balanceText,
                style = TextStyle(
                    fontSize   = 36.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color      = Color.White,
                    letterSpacing = (-0.5).sp,
                ),
                maxLines = 1,
            )

            Spacer(Modifier.height(28.dp))

            // ── Row 4: action buttons ─────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WalletActionButton(
                    label    = "Deposit",
                    icon     = Icons.Outlined.Add,
                    onClick  = onDeposit,
                    modifier = Modifier.weight(1f),
                )
                WalletActionButton(
                    label    = "Withdraw",
                    icon     = Icons.Outlined.KeyboardArrowUp,
                    onClick  = onWithdraw,
                    modifier = Modifier.weight(1f),
                )
                WalletActionButton(
                    label    = "Transfer",
                    icon     = Icons.Outlined.SwapHoriz,
                    onClick  = onTransfer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WalletActionButton(
    label   : String,
    icon    : androidx.compose.ui.graphics.vector.ImageVector,
    onClick : () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick  = onClick,
        modifier = modifier.height(44.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = ButtonDefaults.outlinedButtonColors(
            containerColor = ButtonSurface,
            contentColor   = Color.White,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = ButtonBorder,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            modifier           = Modifier.size(16.dp),
            tint               = Color.White,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text  = label,
            style = TextStyle(
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White,
            ),
        )
    }
}

// ─── Preview ─────────────────────────────────────────────────────────────────
@Preview(showBackground = true, backgroundColor = 0xFFECF0F8.toLong())
@Composable
private fun WalletCardPreview() {
    MaterialTheme {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            WalletCard(
                tierLabel         = "BASIC WALLET",
                avatarLetter      = "B",
                balanceMinorUnits = 125050L,  // M 1,250.50
                currencyCode      = "LSL",
            )
        }
    }
}
