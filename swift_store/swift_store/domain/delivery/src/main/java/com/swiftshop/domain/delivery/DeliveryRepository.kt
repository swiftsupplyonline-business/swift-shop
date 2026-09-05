package com.swiftshop.domain.delivery

import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.core.model.DeliveryStatus
import com.swiftshop.core.model.GeoPoint
import kotlinx.coroutines.flow.Flow

interface DeliveryRepository {
    fun observeDeliveryRoute(routeId: String): Flow<DeliveryRoute>
    fun observeDeliveryRoutesByOrder(orderId: String, userId: String, role: DeliveryRole): Flow<List<DeliveryRoute>>
    suspend fun requestDelivery(orderId: String, pickup: GeoPoint, dropoff: GeoPoint): Result<String>
    suspend fun updateDriverLocation(routeId: String, location: GeoPoint): Result<Unit>
    suspend fun updateDeliveryStatus(routeId: String, status: DeliveryStatus): Result<Unit>
    fun getActiveDeliveriesForDriver(driverId: String): Flow<List<DeliveryRoute>>
}

enum class DeliveryRole { BUYER, SELLER, DRIVER }
