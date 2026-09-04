package com.swiftshop.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.swiftshop.core.model.GeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay

/**
 * State for [SwiftMapView]. Preserves camera position across recompositions.
 */
@Stable
class MapState(
    initialCenter: GeoPoint,
    initialZoom: Double = 14.0
) {
    var center by mutableStateOf(initialCenter)
    var zoom by mutableStateOf(initialZoom)

    companion object {
        val Saver: Saver<MapState, *> = Saver(
            save = { listOf(it.center.lat, it.center.lng, it.zoom) },
            restore = {
                MapState(
                    initialCenter = GeoPoint(it[0] as Double, it[1] as Double),
                    initialZoom = it[2] as Double
                )
            }
        )
    }
}

@Composable
fun rememberMapState(
    initialCenter: GeoPoint = GeoPoint(-29.3167, 27.4833), // Maseru default
    initialZoom: Double = 14.0
): MapState {
    return rememberSaveable(saver = MapState.Saver) {
        MapState(initialCenter, initialZoom)
    }
}

/**
 * A lifecycle-safe Compose wrapper for osmdroid MapView.
 * Supports a fixed-center pin pattern where the map moves under the pin.
 */
@Composable
fun SwiftMapView(
    state: MapState,
    modifier: Modifier = Modifier,
    onMapMoved: (GeoPoint) -> Unit = {}
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    val lifecycleObserver = rememberMapLifecycleObserver(mapView)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            mapView.onDetach()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                Configuration.getInstance().userAgentValue = context.packageName
                mapView.apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(state.zoom)
                    controller.setCenter(OsmGeoPoint(state.center.lat, state.center.lng))

                    // Center detection via MapEventsOverlay (simplified center track)
                    val overlay = MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: OsmGeoPoint?): Boolean = false
                        override fun longPressHelper(p: OsmGeoPoint?): Boolean = false
                    })
                    overlays.add(overlay)

                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                            val newCenter = mapCenter
                            val gp = GeoPoint(newCenter.latitude, newCenter.longitude)
                            if (gp.isValid()) {
                                state.center = gp
                                onMapMoved(gp)
                            }
                            return true
                        }
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                            state.zoom = zoomLevelDouble
                            return true
                        }
                    })
                }
            },
            update = { view ->
                val currentCenter = view.mapCenter
                if (currentCenter.latitude != state.center.lat || currentCenter.longitude != state.center.lng) {
                    view.controller.animateTo(OsmGeoPoint(state.center.lat, state.center.lng))
                }
                if (view.zoomLevelDouble != state.zoom) {
                    view.controller.setZoom(state.zoom)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Fixed Center Pin (visual only, map moves underneath)
        SwiftEntityIcon(
            entity = SwiftEntity.PIN,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 24.dp) // Offset to make pin tip point to center
                .size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun rememberMapLifecycleObserver(mapView: MapView): LifecycleEventObserver =
    remember(mapView) {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
    }

// ─── Drop Your Pin Component ──────────────────────────────────────────────────

/**
 * A high-level component for precise location selection.
 * Features a fixed-center pin and explicit GPS "Use Current Location" action.
 */
@Composable
fun DropYourPinComponent(
    modifier: Modifier = Modifier,
    initialLocation: GeoPoint? = null,
    onLocationConfirmed: (GeoPoint) -> Unit
) {
    val mapState = rememberMapState(initialCenter = initialLocation ?: GeoPoint(-29.3167, 27.4833))
    var currentSelection by remember { mutableStateOf(mapState.center) }

    Column(modifier = modifier) {
        Box(modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            SwiftMapView(
                state = mapState,
                modifier = Modifier.fillMaxSize(),
                onMapMoved = { currentSelection = it }
            )

            // GPS Action Button
            FloatingActionButton(
                onClick = { 
                    // Explicit GPS action: move map to current location (Maseru simulated)
                    mapState.center = GeoPoint(-29.3167, 27.4833)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.MyLocation, "Use current location")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Selection Feedback & Confirmation
        SwiftCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SwiftEntityIcon(SwiftEntity.PIN, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Selected Location", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "Lat: ${"%.5f".format(currentSelection.lat)}, Lng: ${"%.5f".format(currentSelection.lng)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                SwiftPrimaryButton(
                    text = "Confirm Location",
                    onClick = { onLocationConfirmed(currentSelection) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
