package com.swiftshop.domain.delivery

import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.core.model.GeoPoint
import kotlinx.coroutines.flow.Flow

class ObserveDeliveryRouteUseCase(private val repository: DeliveryRepository) {
    operator fun invoke(routeId: String): Flow<DeliveryRoute> = repository.observeDeliveryRoute(routeId)
}

class RequestDeliveryUseCase(private val repository: DeliveryRepository) {
    suspend operator fun invoke(orderId: String, pickup: GeoPoint, dropoff: GeoPoint): Result<String> =
        repository.requestDelivery(orderId, pickup, dropoff)
}
