package com.swiftshop.domain.commerce

import com.swiftshop.core.model.UserTier

class InitiateSubscriptionUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(targetTier: UserTier): Result<String> {
        return repository.initiateSubscription(targetTier)
    }
}
