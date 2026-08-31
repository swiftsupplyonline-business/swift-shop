package com.swiftshop.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── Brand Colors ────────────────────────────────────────────────────────────

object SwiftShopColors {
    val BrandBlue = Color(0xFF1D6BF3)
    val DeepNavy = Color(0xFF0D1B3E)
    val Ice = Color(0xFFEAEFEF)
    val MutedGrey = Color(0xFFF2F4F8)
    val ElectricBlue = Color(0xFF00C2FF)

    // Premium gradient endpoints
    val PremiumGradientStart = Color(0xFF1D6BF3)
    val PremiumGradientEnd = Color(0xFF00C2FF)

    // Elite
    val EliteObsidian = Color(0xFF0A0A0F)
    val EliteGold = Color(0xFFD4A843)
    val EliteGoldLight = Color(0xFFF5D78E)
    val EliteGlass = Color(0x1AFFFFFF)

    // Semantic
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
    val OnlineGreen = Color(0xFF10B981)

    // Surface
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF0F172A)
    val CardLight = Color(0xFFF8FAFC)
    val CardDark = Color(0xFF1E293B)
}

private val LightColorScheme = lightColorScheme(
    primary = SwiftShopColors.BrandBlue,
    onPrimary = Color.White,
    primaryContainer = SwiftShopColors.Ice,
    onPrimaryContainer = SwiftShopColors.DeepNavy,
    secondary = SwiftShopColors.ElectricBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F0FF),
    onSecondaryContainer = SwiftShopColors.DeepNavy,
    background = SwiftShopColors.MutedGrey,
    onBackground = SwiftShopColors.DeepNavy,
    surface = SwiftShopColors.SurfaceLight,
    onSurface = SwiftShopColors.DeepNavy,
    surfaceVariant = SwiftShopColors.CardLight,
    onSurfaceVariant = Color(0xFF64748B),
    error = SwiftShopColors.Error,
    onError = Color.White,
    outline = Color(0xFFCBD5E1),
    scrim = Color(0x80000000)
)

private val DarkColorScheme = darkColorScheme(
    primary = SwiftShopColors.BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3A7A),
    onPrimaryContainer = SwiftShopColors.Ice,
    secondary = SwiftShopColors.ElectricBlue,
    onSecondary = SwiftShopColors.DeepNavy,
    secondaryContainer = Color(0xFF003366),
    onSecondaryContainer = SwiftShopColors.Ice,
    background = SwiftShopColors.SurfaceDark,
    onBackground = SwiftShopColors.Ice,
    surface = Color(0xFF0F172A),
    onSurface = SwiftShopColors.Ice,
    surfaceVariant = SwiftShopColors.CardDark,
    onSurfaceVariant = Color(0xFF94A3B8),
    error = SwiftShopColors.Error,
    onError = Color.White,
    outline = Color(0xFF334155),
    scrim = Color(0x99000000)
)

// ─── Extended Theme ───────────────────────────────────────────────────────────

data class SwiftShopExtendedColors(
    val brandBlue: Color,
    val electricBlue: Color,
    val deepNavy: Color,
    val ice: Color,
    val success: Color,
    val warning: Color,
    val eliteGold: Color,
    val eliteGoldLight: Color,
    val eliteObsidian: Color,
    val eliteGlass: Color,
    val premiumGradientStart: Color,
    val premiumGradientEnd: Color
)

val LocalSwiftShopColors = staticCompositionLocalOf {
    SwiftShopExtendedColors(
        brandBlue = SwiftShopColors.BrandBlue,
        electricBlue = SwiftShopColors.ElectricBlue,
        deepNavy = SwiftShopColors.DeepNavy,
        ice = SwiftShopColors.Ice,
        success = SwiftShopColors.Success,
        warning = SwiftShopColors.Warning,
        eliteGold = SwiftShopColors.EliteGold,
        eliteGoldLight = SwiftShopColors.EliteGoldLight,
        eliteObsidian = SwiftShopColors.EliteObsidian,
        eliteGlass = SwiftShopColors.EliteGlass,
        premiumGradientStart = SwiftShopColors.PremiumGradientStart,
        premiumGradientEnd = SwiftShopColors.PremiumGradientEnd
    )
}

@Composable
fun SwiftShopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(
        LocalSwiftShopColors provides LocalSwiftShopColors.current
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SwiftShopTypography,
            shapes = SwiftShopShapes,
            content = content
        )
    }
}

// Convenience accessor
val MaterialTheme.swiftColors: SwiftShopExtendedColors
    @Composable get() = LocalSwiftShopColors.current
