package com.swiftshop.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.core.model.AvailabilitySlot
import com.swiftshop.core.model.SlotStatus
import com.swiftshop.domain.commerce.AvailabilityRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAvailabilityRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : AvailabilityRepository {

    override fun observeAvailableSlots(providerId: String): Flow<List<AvailabilitySlot>> = callbackFlow {
        val subscription = firestore.collection("availability")
            .document(providerId)
            .collection("slots")
            .whereIn("status", listOf("AVAILABLE", "RESERVED"))
            .addSnapshotListener { snapshot, _ ->
                val now = System.currentTimeMillis()
                val slots = snapshot?.toObjects(FirestoreSlot::class.java)
                    ?.map { it.toDomain() }
                    ?.filter { slot ->
                        slot.status == SlotStatus.AVAILABLE || 
                        (slot.status == SlotStatus.RESERVED && (slot.expiresAt ?: 0L) < now)
                    } ?: emptyList()
                trySend(slots)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun reserveSlot(providerId: String, slotId: String, buyerId: String): Result<Unit> = runCatching {
        firestore.runTransaction { transaction ->
            val slotRef = firestore.collection("availability")
                .document(providerId)
                .collection("slots")
                .document(slotId)
            
            val snapshot = transaction.get(slotRef)
            val now = System.currentTimeMillis()
            
            if (snapshot.exists()) {
                val status = snapshot.getString("status")
                val expiresAt = snapshot.getLong("expiresAt") ?: 0L
                
                val isAvailable = status == "AVAILABLE" || (status == "RESERVED" && expiresAt < now)
                
                if (!isAvailable) {
                    throw IllegalStateException("Slot is no longer available")
                }
            }

            // Atomic update to RESERVED (Soft Lock)
            transaction.set(slotRef, mapOf(
                "id" to slotId,
                "providerId" to providerId,
                "status" to "RESERVED",
                "reservedBy" to buyerId,
                "expiresAt" to now + TimeUnit.MINUTES.toMillis(15),
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ), com.google.firebase.firestore.SetOptions.merge())
        }.await()
    }

    override suspend fun releaseSlot(providerId: String, slotId: String): Result<Unit> = runCatching {
        firestore.collection("availability")
            .document(providerId)
            .collection("slots")
            .document(slotId)
            .update("status", "AVAILABLE", "reservedBy", null, "expiresAt", null)
            .await()
    }

    override suspend fun confirmSlot(providerId: String, slotId: String, orderId: String): Result<Unit> = runCatching {
        firestore.collection("availability")
            .document(providerId)
            .collection("slots")
            .document(slotId)
            .update("status", "BOOKED", "orderId", orderId)
            .await()
    }
}

data class FirestoreSlot(
    val id: String = "",
    val providerId: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val status: String = "AVAILABLE",
    val orderId: String? = null,
    val reservedBy: String? = null,
    val expiresAt: Long? = null
) {
    fun toDomain() = AvailabilitySlot(
        id = id,
        providerId = providerId,
        startTime = startTime,
        endTime = endTime,
        status = runCatching { SlotStatus.valueOf(status) }.getOrDefault(SlotStatus.AVAILABLE),
        orderId = orderId,
        reservedBy = reservedBy,
        expiresAt = expiresAt
    )
}
