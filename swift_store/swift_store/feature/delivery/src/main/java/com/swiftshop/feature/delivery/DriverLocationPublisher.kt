package com.swiftshop.feature.delivery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.swiftshop.core.model.GeoPoint
import com.swiftshop.domain.delivery.DeliveryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriverLocationPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deliveryRepository: DeliveryRepository
) {
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    private var activeRouteId: String? = null

    fun start(routeId: String, scope: CoroutineScope) {
        if (activeRouteId == routeId && locationCallback != null) return
        stop()
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Timber.w("ACCESS_FINE_LOCATION not granted. Cannot start location publisher.")
            return
        }

        activeRouteId = routeId
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                scope.launch {
                    deliveryRepository.updateDriverLocation(
                        routeId = routeId,
                        location = GeoPoint(location.latitude, location.longitude)
                    ).onFailure { Timber.e(it, "Failed to update driver location for route $routeId") }
                }
            }
        }

        locationCallback = callback
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        Timber.i("Started driver location publisher for route: $routeId")
    }

    fun stop() {
        locationCallback?.let {
            fusedClient.removeLocationUpdates(it)
            locationCallback = null
            activeRouteId = null
            Timber.i("Stopped driver location publisher")
        }
    }
}
