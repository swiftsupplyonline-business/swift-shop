package com.swiftshop.domain.commerce

import com.swiftshop.core.model.OrderInitiation
import com.swiftshop.core.model.PaymentMethod
import com.swiftshop.core.model.User
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import kotlinx.coroutines.flow.first

/**
 * Executes the atomic reservation of a slot and the creation of a service booking.
 *
 * Policy (Contract 13D):
 * - Must be an atomic transaction.
 * - Must validate slot availability.
 * - Must enforce authoritative pricing.
 * - Must handle duplicate requests (idempotency).
 */
class InitiateBookingUseCase(
    private val repository: CommerceRepository,
    private val observeCurrentUser: ObserveCurrentUserUseCase
) {
    suspend operator fun invoke(
        listingId: String,
        slotId: String,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String
    ): Result<OrderInitiation> {
        val user: User = observeCurrentUser().first() ?: return Result.failure(IllegalStateException("User not signed in"))
        val buyerId = user.uid

        if (listingId.isBlank()) return Result.failure(IllegalArgumentException("Listing ID required"))
        if (slotId.isBlank()) return Result.failure(IllegalArgumentException("Slot ID required"))
        if (idempotencyKey.isBlank()) return Result.failure(IllegalArgumentException("Idempotency key required"))

        return repository.initiateBooking(
            buyerId = buyerId,
            listingId = listingId,
            slotId = slotId,
            paymentMethod = paymentMethod,
            provider = provider,
            phoneNumber = phoneNumber,
            idempotencyKey = idempotencyKey
        )
    }
}
