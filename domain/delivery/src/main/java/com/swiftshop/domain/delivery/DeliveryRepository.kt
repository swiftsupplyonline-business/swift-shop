package com.swiftshop.domain.delivery

import com.swiftshop.core.model.DeliveryRequest
import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.core.model.DeliveryStatus
import com.swiftshop.core.model.GeoPoint
import kotlinx.coroutines.flow.Flow

data class DeliveryOption(
    val listingId: String,
    val shopId: String,
    val sellerId: String,
    val title: String,
    val price: com.swiftshop.core.model.MoneyAmount,
    val deliveryEstimateDays: Int = 0
)

interface DeliveryRepository {
    suspend fun getDeliveryOptions(): Result<List<DeliveryOption>>
    suspend fun createPostPurchaseDeliveryRequest(orderId: String, listingId: String, dropoff: GeoPoint): Result<String>
    suspend fun createDeliveryJob(requestId: String): Result<String>
    fun observeDeliveryRoute(routeId: String): Flow<DeliveryRoute>
    suspend fun updateDriverLocation(routeId: String, location: GeoPoint): Result<Unit>
    suspend fun updateDeliveryStatus(routeId: String, status: DeliveryStatus): Result<Unit>
    fun getActiveDeliveriesForDriver(driverId: String): Flow<List<DeliveryRoute>>
    fun observeDeliveryRequest(requestId: String): Flow<DeliveryRequest>
    fun observePendingDeliveryRequestsForMerchant(merchantId: String): Flow<List<DeliveryRequest>>
    suspend fun respondToDeliveryRequest(requestId: String, accept: Boolean): Result<Unit>
}
