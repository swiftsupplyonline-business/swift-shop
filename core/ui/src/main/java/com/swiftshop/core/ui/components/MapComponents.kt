package com.swiftshop.core.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.core.model.GeoPoint
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.MapEventsOverlay

private val MASERU_DEFAULT = GeoPoint(-29.3167, 27.4833)

/**
 * Read-only route display: pickup/dropoff/driver markers + connecting line.
 * Moved from feature:delivery — identical behavior to the original OsmMapView.
 */
@Composable
fun OsmRouteMap(route: DeliveryRoute, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(14.0)

                val center = route.pickupLocation.takeIf { it.lat != 0.0 } ?: MASERU_DEFAULT
                controller.setCenter(OsmGeoPoint(center.lat, center.lng))

                if (route.pickupLocation.lat != 0.0) {
                    overlays.add(Marker(this).apply {
                        position = OsmGeoPoint(route.pickupLocation.lat, route.pickupLocation.lng)
                        title = "Pickup"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }

                if (route.dropoffLocation.lat != 0.0) {
                    overlays.add(Marker(this).apply {
                        position = OsmGeoPoint(route.dropoffLocation.lat, route.dropoffLocation.lng)
                        title = "Destination"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }

                route.driverCurrentLocation?.let { driverLoc ->
                    overlays.add(Marker(this).apply {
                        position = OsmGeoPoint(driverLoc.lat, driverLoc.lng)
                        title = "Driver"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }

                if (route.pickupLocation.lat != 0.0 && route.dropoffLocation.lat != 0.0) {
                    overlays.add(Polyline().apply {
                        addPoint(OsmGeoPoint(route.pickupLocation.lat, route.pickupLocation.lng))
                        addPoint(OsmGeoPoint(route.dropoffLocation.lat, route.dropoffLocation.lng))
                        outlinePaint.color = android.graphics.Color.BLUE
                        outlinePaint.strokeWidth = 5f
                    })
                }
            }
        },
        update = { mapView ->
            route.driverCurrentLocation?.let { loc ->
                mapView.controller.animateTo(OsmGeoPoint(loc.lat, loc.lng))
            }
        },
        modifier = modifier
    )
}

/**
 * Interactive tap-to-place map for picking a single location (e.g. pickup or
 * dropoff point for a delivery request). Shows one marker at [initialLocation]
 * if provided; tapping the map moves the marker and calls [onLocationSelected]
 * with the tapped coordinates. Caller owns the selected-location state — this
 * composable is a controlled input, not a source of truth.
 */
@Composable
fun OsmPinDropMap(
    initialLocation: GeoPoint? = null,
    modifier: Modifier = Modifier,
    onLocationSelected: (GeoPoint) -> Unit
) {
    val context = LocalContext.current
    var currentMarker by remember { mutableStateOf<Marker?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)

                val center = initialLocation?.takeIf { it.lat != 0.0 } ?: MASERU_DEFAULT
                controller.setCenter(OsmGeoPoint(center.lat, center.lng))

                if (initialLocation != null && initialLocation.lat != 0.0) {
                    val marker = Marker(this).apply {
                        position = OsmGeoPoint(initialLocation.lat, initialLocation.lng)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    overlays.add(marker)
                    currentMarker = marker
                }

                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                        currentMarker?.let { overlays.remove(it) }
                        val newMarker = Marker(this@apply).apply {
                            position = p
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        overlays.add(newMarker)
                        currentMarker = newMarker
                        invalidate()
                        onLocationSelected(GeoPoint(p.latitude, p.longitude))
                        return true
                    }

                    override fun longPressHelper(p: OsmGeoPoint): Boolean = false
                }
                overlays.add(MapEventsOverlay(receiver))

                mapViewRef = this
            }
        },
        modifier = modifier
    )
}
