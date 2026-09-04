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
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.swiftshop.core.model.GeoPoint
import com.swiftshop.core.model.MapMarker
import com.swiftshop.core.model.MapPolyline
import com.swiftshop.core.model.SwiftEntity
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

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
    markers: List<MapMarker> = emptyList(),
    polylines: List<MapPolyline> = emptyList(),
    showCenterPin: Boolean = false,
    onMapMoved: (GeoPoint) -> Unit = {},
    onMarkerClick: (MapMarker) -> Unit = {}
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

                    // Center detection via MapEventsOverlay
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
                // Update camera
                val currentCenter = view.mapCenter
                if (Math.abs(currentCenter.latitude - state.center.lat) > 0.00001 || 
                    Math.abs(currentCenter.longitude - state.center.lng) > 0.00001) {
                    view.controller.animateTo(OsmGeoPoint(state.center.lat, state.center.lng))
                }
                if (view.zoomLevelDouble != state.zoom) {
                    view.controller.setZoom(state.zoom)
                }

                // Update markers and polylines
                // Simplified sync: clear all but the first (MapEventsOverlay)
                while (view.overlays.size > 1) {
                    view.overlays.removeAt(1)
                }

                markers.forEach { m ->
                    val marker = Marker(view).apply {
                        position = OsmGeoPoint(m.position.lat, m.position.lng)
                        title = m.title
                        snippet = m.snippet
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            onMarkerClick(m)
                            true
                        }
                        // Default icon logic
                        m.entityType?.let { type ->
                            icon = context.getDrawable(when(type) {
                                SwiftEntity.SHOP -> android.R.drawable.ic_menu_myplaces
                                SwiftEntity.PROVIDER -> android.R.drawable.ic_menu_directions
                                SwiftEntity.BUYER -> android.R.drawable.ic_menu_view
                                else -> android.R.drawable.ic_dialog_map
                            })
                        }
                    }
                    view.overlays.add(marker)
                }

                polylines.forEach { p ->
                    val poly = Polyline(view).apply {
                        setPoints(p.points.map { OsmGeoPoint(it.lat, it.lng) })
                        outlinePaint.color = Color.parseColor(p.color)
                        outlinePaint.strokeWidth = p.width
                    }
                    view.overlays.add(poly)
                }
                
                view.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Map controls overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = { state.center = GeoPoint(-29.3167, 27.4833) },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Place, "Center on Maseru")
            }
        }

        // Fixed Center Pin (visual only, map moves underneath)
        if (showCenterPin) {
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
}

private const val DEFAULT_PLACE_SNAP_RADIUS_METERS = 50.0

private fun calculateDistanceMeters(p1: GeoPoint, p2: GeoPoint): Double {
    val r = 6371000.0 // Earth radius in meters
    val dLat = Math.toRadians(p2.lat - p1.lat)
    val dLng = Math.toRadians(p2.lng - p1.lng)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(p1.lat)) * Math.cos(Math.toRadians(p2.lat)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return r * c
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
    markers: List<MapMarker> = emptyList(),
    onLocationConfirmed: (GeoPoint) -> Unit
) {
    val mapState = rememberMapState(initialCenter = initialLocation ?: GeoPoint(-29.3167, 27.4833))
    var currentSelection by remember { mutableStateOf(mapState.center) }
    var snappedMarker by remember { mutableStateOf<MapMarker?>(null) }

    LaunchedEffect(mapState.center) {
        val near = markers.find { 
            calculateDistanceMeters(it.position, mapState.center) <= DEFAULT_PLACE_SNAP_RADIUS_METERS 
        }
        snappedMarker = near
        currentSelection = near?.position ?: mapState.center
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            SwiftMapView(
                state = mapState,
                markers = markers,
                showCenterPin = true,
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
                    SwiftEntityIcon(
                        entity = snappedMarker?.entityType ?: SwiftEntity.PIN, 
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = snappedMarker?.title ?: "Selected Location", 
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Text(
                    text = snappedMarker?.snippet ?: "Lat: ${"%.5f".format(currentSelection.lat)}, Lng: ${"%.5f".format(currentSelection.lng)}",
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
