package com.swiftshop.core.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryOwner
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Utility for rendering Compose content into a Bitmap/Drawable.
 * Used for custom map markers in osmdroid.
 * Follows the visual requirement of bridging Compose -> legacy Drawables.
 */
object ComposeBitmapUtil {

    /**
     * Converts a [Composable] to an Android [Drawable].
     */
    fun composableToDrawable(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        savedStateRegistryOwner: SavedStateRegistryOwner,
        widthPx: Int,
        heightPx: Int,
        content: @Composable () -> Unit
    ): Drawable {
        val bitmap = composableToBitmap(context, lifecycleOwner, savedStateRegistryOwner, widthPx, heightPx, content)
        return BitmapDrawable(context.resources, bitmap)
    }

    /**
     * Converts a [Composable] to an Android [Bitmap].
     */
    fun composableToBitmap(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        savedStateRegistryOwner: SavedStateRegistryOwner,
        widthPx: Int,
        heightPx: Int,
        content: @Composable () -> Unit
    ): Bitmap {
        val composeView = ComposeView(context).apply {
            // Important for Compose components that rely on Lifecycle or SavedState (like Coil)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
            setContent(content)
        }

        // Measurement and Layout
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        composeView.layout(0, 0, widthPx, heightPx)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        composeView.draw(canvas)
        return bitmap
    }
}
