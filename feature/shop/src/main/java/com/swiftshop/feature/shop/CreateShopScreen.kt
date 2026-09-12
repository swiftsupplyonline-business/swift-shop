package com.swiftshop.feature.shop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import com.swiftshop.core.ui.components.SwiftPrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateShopScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: CreateShopViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val canCreate by viewModel.canCreate.collectAsState()
    val usageText by viewModel.usageText.collectAsState()
    val name by viewModel.name.collectAsState()
    val description by viewModel.description.collectAsState()
    val category by viewModel.category.collectAsState()
    val locationAddress by viewModel.locationAddress.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is CreateShopUiState.Success) onCreated()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Shop") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Launch your business in Swift City.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Shop Name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = category,
                onValueChange = viewModel::onCategoryChange,
                label = { Text("Category") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = locationAddress,
                onValueChange = viewModel::onLocationAddressChange,
                label = { Text("Business Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = description,
                onValueChange = viewModel::onDescriptionChange,
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Text("Select Shop Location on Map *", style = MaterialTheme.typography.titleSmall)
            val selectedLocation by viewModel.selectedLocation.collectAsState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                AndroidView(
                    factory = { ctx ->
                        Configuration.getInstance().userAgentValue = ctx.packageName
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(15.0)
                            controller.setCenter(OsmGeoPoint(selectedLocation.lat, selectedLocation.lng))
                            
                            val pinMarker = Marker(this).apply {
                                position = OsmGeoPoint(selectedLocation.lat, selectedLocation.lng)
                                title = "Shop Location"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                            overlays.add(pinMarker)

                            val touchOverlay = object : Overlay() {
                                override fun onSingleTapUp(e: android.view.MotionEvent, mapView: MapView): Boolean {
                                    val proj = mapView.projection
                                    val geoPoint = proj.fromPixels(e.x.toInt(), e.y.toInt()) as OsmGeoPoint
                                    viewModel.onLocationChange(geoPoint.latitude, geoPoint.longitude)
                                    pinMarker.position = geoPoint
                                    mapView.invalidate()
                                    return true
                                }
                            }
                            overlays.add(touchOverlay)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (uiState is CreateShopUiState.Error) {
                Text(
                    text = (uiState as CreateShopUiState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.weight(1f))

            Column {
                SwiftPrimaryButton(
                    text = if (canCreate) "Create Shop" else "Limit Reached",
                    isLoading = uiState is CreateShopUiState.Loading,
                    enabled = canCreate && name.isNotBlank() && uiState !is CreateShopUiState.Loading,
                    onClick = { viewModel.submit() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (usageText.isNotEmpty()) {
                    Text(
                        text = usageText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp).align(androidx.compose.ui.Alignment.CenterHorizontally),
                        color = if (canCreate) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
