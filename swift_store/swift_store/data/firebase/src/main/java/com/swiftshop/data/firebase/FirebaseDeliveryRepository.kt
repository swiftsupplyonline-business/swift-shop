package com.swiftshop.data.firebase

import java.util.Date
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.swiftshop.core.model.*
import com.swiftshop.domain.delivery.DeliveryRepository
import com.swiftshop.domain.delivery.DeliveryRole
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseDeliveryRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions
) : DeliveryRepository {

    override fun observeDeliveryRoute(routeId: String): Flow<DeliveryRoute> = callbackFlow {
        val subscription = firestore.collection("deliveryRoutes").document(routeId)
            .addSnapshotListener { snapshot, _ ->
                val route = snapshot?.toObject(FirestoreDeliveryRoute::class.java)?.toDomain(routeId)
                if (route != null) trySend(route)
            }
        awaitClose { subscription.remove() }
    }

    override fun observeDeliveryRoutesByOrder(orderId: String, userId: String, role: DeliveryRole): Flow<List<DeliveryRoute>> = callbackFlow {
        val field = when (role) {
            DeliveryRole.BUYER -> "buyerId"
            DeliveryRole.SELLER -> "sellerId"
            DeliveryRole.DRIVER -> "driverId"
        }
        val subscription = firestore.collection("deliveryRoutes")
            .whereEqualTo("orderId", orderId)
            .whereEqualTo(field, userId)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.documents?.mapNotNull { it.toObject(FirestoreDeliveryRoute::class.java)?.toDomain(it.id) } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun requestDelivery(orderId: String, pickup: GeoPoint, dropoff: GeoPoint): Result<String> = runCatching {
        val data = mapOf(
            "orderId" to orderId,
            "pickup" to mapOf("lat" to pickup.lat, "lng" to pickup.lng),
            "dropoff" to mapOf("lat" to dropoff.lat, "lng" to dropoff.lng)
        )
        val result = functions.getHttpsCallable("requestDelivery").call(data).await()
        result.data as String
    }

    override suspend fun updateDriverLocation(routeId: String, location: GeoPoint): Result<Unit> = runCatching {
        // Direct write allowed for drivers to update their own location for real-time tracking
        firestore.collection("deliveryRoutes").document(routeId)
            .update(mapOf(
                "driverCurrentLocationLat" to location.lat,
                "driverCurrentLocationLng" to location.lng
            ))
            .await()
    }

    override suspend fun updateDeliveryStatus(routeId: String, status: DeliveryStatus): Result<Unit> = runCatching {
        val data = mapOf("routeId" to routeId, "status" to status.name)
        functions.getHttpsCallable("updateDeliveryStatus").call(data).await()
    }

    override fun getActiveDeliveriesForDriver(driverId: String): Flow<List<DeliveryRoute>> = callbackFlow {
        val subscription = firestore.collection("deliveryRoutes")
            .whereEqualTo("driverId", driverId)
            .whereIn("status", listOf("ASSIGNED", "PICKUP", "IN_TRANSIT"))
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.documents?.mapNotNull { it.toObject(FirestoreDeliveryRoute::class.java)?.toDomain(it.id) } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }
}

data class FirestoreDeliveryRoute(
    val orderId: String = "",
    val buyerId: String = "",
    val sellerId: String = "",
    val driverId: String = "",
    val pickupLat: Double = 0.0,
    val pickupLng: Double = 0.0,
    val dropoffLat: Double = 0.0,
    val dropoffLng: Double = 0.0,
    val status: String = "REQUESTED",
    val distanceMeters: Double = 0.0,
    val estimatedMinutes: Int = 0,
    val driverCurrentLocationLat: Double? = null,
    val driverCurrentLocationLng: Double? = null,
    val conversationId: String = "",
    val createdAt: Date = Date(0)
) {
    fun toDomain(id: String) = DeliveryRoute(
        id = id,
        orderId = orderId,
        driverId = driverId,
        pickupLocation = GeoPoint(pickupLat, pickupLng),
        dropoffLocation = GeoPoint(dropoffLat, dropoffLng),
        status = runCatching { DeliveryStatus.valueOf(status) }.getOrDefault(DeliveryStatus.REQUESTED),
        distanceMeters = distanceMeters,
        estimatedMinutes = estimatedMinutes,
        driverCurrentLocation = if (driverCurrentLocationLat != null && driverCurrentLocationLng != null) 
            GeoPoint(driverCurrentLocationLat, driverCurrentLocationLng) else null,
        conversationId = conversationId,
        createdAt = createdAt.time
    )
}
