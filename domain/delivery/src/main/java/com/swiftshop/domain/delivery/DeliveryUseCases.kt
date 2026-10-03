package com.swiftshop.domain.delivery

import com.swiftshop.core.model.DeliveryRequest
import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.core.model.GeoPoint
import kotlinx.coroutines.flow.Flow

class GetDeliveryOptionsUseCase(private val repository: DeliveryRepository) {
    suspend operator fun invoke(): Result<List<DeliveryOption>> = repository.getDeliveryOptions()
}

class CreatePostPurchaseDeliveryRequestUseCase(private val repository: DeliveryRepository) {
    suspend operator fun invoke(orderId: String, listingId: String, dropoff: GeoPoint): Result<String> =
        repository.createPostPurchaseDeliveryRequest(orderId, listingId, dropoff)
}

class CreateDeliveryJobUseCase(private val repository: DeliveryRepository) {
    suspend operator fun invoke(requestId: String): Result<String> = repository.createDeliveryJob(requestId)
}

class ObserveDeliveryRouteUseCase(private val repository: DeliveryRepository) {
    operator fun invoke(routeId: String): Flow<DeliveryRoute> = repository.observeDeliveryRoute(routeId)
}

class ObserveDeliveryRequestUseCase(private val repository: DeliveryRepository) {
    operator fun invoke(requestId: String): Flow<DeliveryRequest> = repository.observeDeliveryRequest(requestId)
}

class ObservePendingDeliveryRequestsForMerchantUseCase(private val repository: DeliveryRepository) {
    operator fun invoke(merchantId: String): Flow<List<DeliveryRequest>> =
        repository.observePendingDeliveryRequestsForMerchant(merchantId)
}

class RespondToDeliveryRequestUseCase(private val repository: DeliveryRepository) {
    suspend operator fun invoke(requestId: String, accept: Boolean): Result<Unit> =
        repository.respondToDeliveryRequest(requestId, accept)
}
