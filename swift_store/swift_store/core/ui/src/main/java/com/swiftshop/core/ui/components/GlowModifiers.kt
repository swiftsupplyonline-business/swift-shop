package com.swiftshop.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swiftshop.core.ui.theme.glow

/**
 * Reusable glow modifiers for Swift Shop UI enhancement.
 * Follows the visual language of the Swift Glow system.
 */

@Composable
fun Modifier.swiftGlowBorder(
    shape: Shape = RoundedCornerShape(12.dp),
    width: Dp = 2.dp
): Modifier = this.border(
    width = width,
    color = MaterialTheme.glow.active,
    shape = shape
)

@Composable
fun Modifier.swiftGlowShadow(
    elevation: Dp = 8.dp,
    shape: Shape = RoundedCornerShape(12.dp)
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    spotColor = MaterialTheme.glow.shadow,
    ambientColor = MaterialTheme.glow.shadow
)

@Composable
fun Modifier.swiftGlowCircle(
    width: Dp = 3.dp
): Modifier = this.border(
    width = width,
    color = MaterialTheme.glow.ring,
    shape = CircleShape
)
