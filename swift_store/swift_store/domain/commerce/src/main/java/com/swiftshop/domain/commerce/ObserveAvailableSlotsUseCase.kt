package com.swiftshop.domain.commerce

import com.swiftshop.core.model.AvailabilitySlot
import kotlinx.coroutines.flow.Flow

/**
 * Provides a real-time stream of available appointment slots for a specific provider.
 * 
 * Policy (Contract 13C):
 * - Observation is read-only. No side effects.
 * - Filtering logic (e.g. removing past slots) should be applied here or in the repository.
 * - All timestamps are UTC.
 */
class ObserveAvailableSlotsUseCase constructor(
    private val repository: AvailabilityRepository
) {
    operator fun invoke(providerId: String): Flow<List<AvailabilitySlot>> {
        return repository.observeAvailableSlots(providerId)
    }
}
