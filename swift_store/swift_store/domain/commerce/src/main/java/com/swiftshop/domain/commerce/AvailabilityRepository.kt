package com.swiftshop.domain.commerce

import com.swiftshop.core.model.AvailabilitySlot
import kotlinx.coroutines.flow.Flow

interface AvailabilityRepository {
    /**
     * Observes available slots for a specific provider.
     */
    fun observeAvailableSlots(providerId: String): Flow<List<AvailabilitySlot>>

    /**
     * Attempts to atomically reserve a slot for a buyer.
     * Implements the "Soft Lock" pattern (RESERVED for 15 mins).
     * 
     * @param slotId Deterministic ID: ${providerId}_${startTime}
     * @param buyerId The uid of the user attempting to book.
     * @return Result.success(Unit) if reserved, Result.failure if slot is taken or unavailable.
     */
    suspend fun reserveSlot(providerId: String, slotId: String, buyerId: String): Result<Unit>

    /**
     * Releases a RESERVED slot back to AVAILABLE.
     */
    suspend fun releaseSlot(providerId: String, slotId: String): Result<Unit>

    /**
     * Hard-locks a slot to BOOKED. Called after payment verification.
     */
    suspend fun confirmSlot(providerId: String, slotId: String, orderId: String): Result<Unit>
}
