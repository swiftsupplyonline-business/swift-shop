package com.swiftshop.domain.commerce

import com.swiftshop.core.model.Shop
import kotlinx.coroutines.flow.Flow

class DiscoverShopsUseCase(private val repository: CommerceRepository) {
    operator fun invoke(): Flow<List<Shop>> = repository.getAllShops()
}
