package com.swiftshop.core.ui.components

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import io.github.sceneview.Scene
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberMainLightNode
import androidx.compose.ui.graphics.Color
import io.github.sceneview.rememberCollisionSystem

@Composable
fun SwiftIcon3D(
    modifier: Modifier = Modifier,
    autoRotateSpeed: Float = 15f
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = io.github.sceneview.math.Position(z = 3.3f)
    }
    val mainLightNode = rememberMainLightNode(engine)

    var yRotation by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val modelNode = rememberNode {
        ModelNode(
            modelInstance = modelLoader.createModelInstance("swift_icon.glb"),
            scaleToUnits = 1.0f
        )
    }

    // Auto-rotate
    val infiniteTransition = rememberInfiniteTransition(label = "rotate")
    val autoAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (360f / autoRotateSpeed * 1000f).toInt(), easing = LinearEasing)
        ),
        label = "auto_rotate"
    )

    LaunchedEffect(autoAngle, isDragging) {
        if (!isDragging) {
            yRotation = autoAngle
            modelNode?.rotation = Rotation(0f, yRotation, 0f)
        }
    }

    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        isOpaque = false,
        environmentLoader = environmentLoader,
        cameraNode = cameraNode,
        mainLightNode = mainLightNode,
        childNodes = listOfNotNull(modelNode),
        onGestureListener = rememberOnGestureListener(
            onScroll = { _, _, _, delta ->
                isDragging = true
                yRotation += delta.x * 0.5f
                modelNode?.rotation = Rotation(0f, yRotation, 0f)
            },
            onDown = { _, _ -> isDragging = true; false },
            onFling = { _, _, _ , _-> isDragging = false; false }
        )
    )
}




