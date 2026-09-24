package com.swiftshop.data.firebase

import java.util.Date
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.swiftshop.core.model.*
import com.swiftshop.domain.commerce.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class FirebaseCommerceRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : CommerceRepository {

    private fun tsToLong(v: Any?): Long = when (v) {
        is com.google.firebase.Timestamp -> v.toDate().time
        is Date -> v.time
        is Long -> v
        is Number -> v.toLong()
        else -> 0L
    }

    override fun getUserShops(userId: String): Flow<List<Shop>> = callbackFlow {
        val subscription = firestore.collection("shops")
            .whereEqualTo("ownerId", userId)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreShop::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun getShop(shopId: String): Result<Shop> = runCatching {
        firestore.collection("shops").document(shopId).get().await()
            .toObject(FirestoreShop::class.java)?.toDomain() ?: throw NoSuchElementException("Shop not found")
    }

    override suspend fun createShop(shop: Shop): Result<String> = runCatching {
        val data = shop.toFirestore()
        val result = functions.getHttpsCallable("createShop").call(data).await()
        result.data as String
    }

    override suspend fun updateShop(shop: Shop): Result<Unit> = runCatching {
        val data = mapOf(
            "shopId" to shop.id,
            "updates" to shop.toUpdateMap()
        )
        functions.getHttpsCallable("updateShop").call(data).await()
        Unit
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Listings Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    override fun getShopListings(shopId: String, page: Int, pageSize: Int): Flow<List<Listing>> = callbackFlow {
        val currentUserId = auth.currentUser?.uid
        // Basic pagination via limit (cursor-based omitted for brevity in repository restoration)
        val subscription = firestore.collection("listings")
            .whereEqualTo("shopId", shopId)
            .limit(pageSize.toLong()) 
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreListing::class.java) ?: emptyList()
                this@callbackFlow.launch {
                    val hydratedList = items.map { item ->
                        val isLiked = if (currentUserId != null) {
                            runCatching {
                                firestore.collection("listings").document(item.id)
                                    .collection("likes").document(currentUserId).get().await().exists()
                            }.getOrDefault(false)
                        } else false

                        val isBookmarked = if (currentUserId != null) {
                            runCatching {
                                firestore.collection("users").document(currentUserId)
                                    .collection("bookmarks").document(item.id).get().await().exists()
                            }.getOrDefault(false)
                        } else false

                        item.toDomain(isLiked = isLiked, isBookmarked = isBookmarked)
                    }
                    trySend(hydratedList)
                }
            }
        awaitClose { subscription.remove() }
    }

    // shopId parameter is kept for API compatibility but is no longer used as a filter.
    // Delivery providers may belong to any shop — cross-shop delivery is valid.
    // The Firestore index required: listingType ASC, isAvailable ASC, createdAt DESC.
    override fun getDeliveryListings(shopId: String): Flow<List<Listing>> = callbackFlow {
        val subscription = firestore.collection("listings")
            .whereEqualTo("listingType", "DELIVER")
            .whereEqualTo("isAvailable", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("SwiftShopDelivery", "getDeliveryListings failed", error)
                }
                val list = snapshot?.toObjects(FirestoreListing::class.java)?.map { it.toDomain() } ?: emptyList()
                android.util.Log.d("SwiftShopDelivery", "getDeliveryListings returned ${list.size} results")
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun getListing(listingId: String): Result<Listing> = runCatching {
        val doc = firestore.collection("listings").document(listingId).get().await()
        val firestoreListing = doc.toObject(FirestoreListing::class.java) ?: throw NoSuchElementException("Listing not found")
        
        val currentUserId = auth.currentUser?.uid
        val isLiked = if (currentUserId != null) {
            runCatching {
                firestore.collection("listings").document(listingId)
                    .collection("likes").document(currentUserId).get().await().exists()
            }.getOrDefault(false)
        } else false

        val isBookmarked = if (currentUserId != null) {
            runCatching {
                firestore.collection("users").document(currentUserId)
                    .collection("bookmarks").document(listingId).get().await().exists()
            }.getOrDefault(false)
        } else false

        firestoreListing.toDomain(isLiked = isLiked, isBookmarked = isBookmarked)
    }

    override suspend fun getUserListings(userId: String): Result<List<Listing>> = runCatching {
        val currentUserId = auth.currentUser?.uid
        val snapshot = firestore.collection("listings")
            .whereEqualTo("sellerId", userId)
            .get().await()
        
        snapshot.toObjects(FirestoreListing::class.java).map { item ->
            val isLiked = if (currentUserId != null) {
                runCatching {
                    firestore.collection("listings").document(item.id)
                        .collection("likes").document(currentUserId).get().await().exists()
                }.getOrDefault(false)
            } else false

            val isBookmarked = if (currentUserId != null) {
                runCatching {
                    firestore.collection("users").document(currentUserId)
                        .collection("bookmarks").document(item.id).get().await().exists()
                }.getOrDefault(false)
            } else false

            item.toDomain(isLiked = isLiked, isBookmarked = isBookmarked)
        }
    }

    override suspend fun searchListings(query: String): Result<List<Listing>> = runCatching {
        val normalized = query.trim().lowercase()
        // Basic title prefix search using lowercase shadow field.
        firestore.collection("listings")
            .whereEqualTo("isAvailable", true)
            .whereGreaterThanOrEqualTo("title_lowercase", normalized)
            .whereLessThanOrEqualTo("title_lowercase", normalized + "\uf8ff")
            .get().await()
            .toObjects(FirestoreListing::class.java).map { it.toDomain() }
    }

    override suspend fun searchShops(query: String): Result<List<Shop>> = runCatching {
        val normalized = query.trim().lowercase()
        firestore.collection("shops")
            .whereEqualTo("isActive", true)
            .whereGreaterThanOrEqualTo("name_lowercase", normalized)
            .whereLessThanOrEqualTo("name_lowercase", normalized + "\uf8ff")
            .get().await()
            .toObjects(FirestoreShop::class.java).map { it.toDomain() }
    }

    override suspend fun createListing(listing: Listing): Result<String> = runCatching {
        val data = listing.toFirestore()
        val result = functions.getHttpsCallable("createListing").call(data).await()
        result.data as String
    }

    override suspend fun updateListing(listing: Listing): Result<Unit> = runCatching {
        val data = mapOf(
            "listingId" to listing.id,
            "updates" to listing.toFirestore()
        )
        functions.getHttpsCallable("updateListing").call(data).await()
        Unit
    }

    override suspend fun deleteListing(listingId: String): Result<Unit> = runCatching {
        val data = mapOf("listingId" to listingId)
        functions.getHttpsCallable("deleteListing").call(data).await()
        Unit
    }

    override suspend fun likeListing(listingId: String): Result<Unit> = runCatching {
        val data = mapOf("listingId" to listingId)
        functions.getHttpsCallable("likeListing").call(data).await()
        Unit
    }

    override suspend fun unlikeListing(listingId: String): Result<Unit> = runCatching {
        val data = mapOf("listingId" to listingId)
        functions.getHttpsCallable("unlikeListing").call(data).await()
        Unit
    }

    override suspend fun isListingLikedByUser(listingId: String, userId: String): Result<Boolean> = runCatching {
        val doc = firestore.collection("listings").document(listingId)
            .collection("likes").document(userId).get().await()
        doc.exists()
    }

    override suspend fun bookmarkListing(listingId: String): Result<Unit> = runCatching {
        val data = mapOf("listingId" to listingId)
        functions.getHttpsCallable("bookmarkListing").call(data).await()
        Unit
    }

    override suspend fun unbookmarkListing(listingId: String): Result<Unit> = runCatching {
        val data = mapOf("listingId" to listingId)
        functions.getHttpsCallable("unbookmarkListing").call(data).await()
        Unit
    }

    override suspend fun getSimilarListings(category: String, excludeListingId: String, limit: Int): Result<List<Listing>> = runCatching {
        firestore.collection("listings")
            .whereEqualTo("category", category)
            .whereEqualTo("isAvailable", true)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit((limit + 1).toLong())
            .get().await()
            .toObjects(FirestoreListing::class.java).map { it.toDomain() }
            .filter { it.id != excludeListingId }
            .take(limit)
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Cart Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    override fun observeCart(userId: String): Flow<List<CartItem>> = callbackFlow {
        val subscription = firestore.collection("users").document(userId)
            .collection("cart")
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreCartItem::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun addToCart(userId: String, item: CartItem): Result<Unit> = runCatching {
        firestore.collection("users").document(userId)
            .collection("cart").document(item.listingId)
            .set(item.toFirestore())
            .await()
        Unit
    }

    override suspend fun removeFromCart(userId: String, listingId: String): Result<Unit> = runCatching {
        firestore.collection("users").document(userId)
            .collection("cart").document(listingId)
            .delete()
            .await()
        Unit
    }

    override suspend fun clearCart(userId: String): Result<Unit> = runCatching {
        val cart = firestore.collection("users").document(userId).collection("cart").get().await()
        val batch = firestore.batch()
        cart.documents.forEach { batch.delete(it.reference) }
        batch.commit().await()
        Unit
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Subscriptions Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    override suspend fun initiateSubscription(targetTier: UserTier): Result<String> = runCatching {
        val data = mapOf("targetTier" to targetTier.name)
        val result = functions.getHttpsCallable("initiateSubscription").call(data).await()
        result.data as String
    }

    // Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ Orders Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

    override suspend fun calculateOrderFees(
        items: List<OrderItem>,
        requiresDelivery: Boolean,
        address: DeliveryAddress?,
        selectedDeliveryListingId: String?
    ): Result<OrderSummary> = runCatching {
        val data = mapOf(
            "items" to items.map { it.toFirestore() },
            "requiresDelivery" to requiresDelivery,
            "deliveryAddress" to address?.toFirestore(),
            "selectedDeliveryListingId" to selectedDeliveryListingId
        )
        val result = functions.getHttpsCallable("calculateOrderFees").call(data).await()
        val resMap = result.data as Map<String, Any>
        
        val currency = resMap["currency"] as? String ?: "LSL"
        OrderSummary(
            subtotal = MoneyAmount(currency, (resMap["subtotalMinorUnits"] as Number).toLong()),
            deliveryFee = MoneyAmount(currency, (resMap["deliveryFeeMinorUnits"] as Number).toLong()),
            platformFee = MoneyAmount(currency, (resMap["platformFeeMinorUnits"] as Number).toLong()),
            total = MoneyAmount(currency, (resMap["totalMinorUnits"] as Number).toLong())
        )
    }

    override suspend fun placeOrder(
        items: List<OrderItem>,
        requiresDelivery: Boolean,
        address: DeliveryAddress?,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String,
        selectedDeliveryListingId: String?,
        deliveryRequestId: String?
    ): Result<OrderInitiation> = runCatching {
        val data = mapOf(
            "items" to items.map { it.toFirestore() },
            "requiresDelivery" to requiresDelivery,
            "deliveryAddress" to address?.toFirestore(),
            "paymentMethod" to paymentMethod.name,
            "provider" to provider,
            "phoneNumber" to phoneNumber,
            "idempotencyKey" to idempotencyKey,
            "selectedDeliveryListingId" to selectedDeliveryListingId,
            "deliveryRequestId" to deliveryRequestId
        )
        val result = functions.getHttpsCallable("createOrder").call(data).await()
        val resMap = result.data as Map<String, Any>
        
        OrderInitiation(
            orderId = resMap["orderId"] as String,
            paymentUrl = resMap["paymentUrl"] as? String,
            mopaySessionId = resMap["mopaySessionId"] as? String
        )
    }

    override suspend fun verifyMopayPayment(sessionId: String): Result<Unit> = runCatching {
        val data = mapOf("sessionId" to sessionId)
        functions.getHttpsCallable("verifyMopayPayment").call(data).await()
        Unit
    }

    override suspend fun confirmDelivery(orderId: String): Result<Unit> = runCatching {
        val data = mapOf("orderId" to orderId)
        functions.getHttpsCallable("confirmDelivery").call(data).await()
        Unit
    }

    override fun observeUserOrders(userId: String): Flow<List<Order>> = callbackFlow {
        val subscription = firestore.collection("orders")
            .whereEqualTo("buyerId", userId)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreOrder::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override fun observeSellerOrders(sellerId: String): Flow<List<Order>> = callbackFlow {
        val subscription = firestore.collection("orders")
            .whereEqualTo("sellerId", sellerId)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreOrder::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun getOrder(orderId: String): Result<Order> = runCatching {
        firestore.collection("orders").document(orderId).get().await()
            .toObject(FirestoreOrder::class.java)?.toDomain() ?: throw NoSuchElementException("Order not found")
    }

    override suspend fun updateOrderStatus(orderId: String, status: OrderStatus): Result<Unit> = runCatching {
        // Authoritative status updates must go through Backend.
        // This is a request intent.
        val data = mapOf("orderId" to orderId, "status" to status.name)
        functions.getHttpsCallable("updateOrderStatus").call(data).await()
        Unit
    }

    override suspend fun cancelOrder(orderId: String, reason: String): Result<Unit> = runCatching {
        val data = mapOf("orderId" to orderId, "reason" to reason)
        functions.getHttpsCallable("cancelOrder").call(data).await()
        Unit
    }
}

// Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬ DTOs & Mappers Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬

data class FirestoreShop(
    val id: String = "",
    val ownerId: String = "",
    val name: String = "",
    val description: String = "",
    val logoUrl: String = "",
    val coverUrl: String = "",
    val category: String = "",
    val locationLat: Double = 0.0,
    val locationLng: Double = 0.0,
    val locationAddress: String = "",
    @get:PropertyName("isVerified") @set:PropertyName("isVerified") var isVerified: Boolean = false,
    @get:PropertyName("isActive") @set:PropertyName("isActive") var isActive: Boolean = true,
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val followerCount: Int = 0,
    val listingCount: Int = 0,
    val createdAt: Any? = null
) {
    fun toDomain() = Shop(id, ownerId, name, description, logoUrl, coverUrl, category, GeoPoint(locationLat, locationLng), locationAddress, isVerified, isActive, rating, reviewCount, followerCount, listingCount, tsToLong(createdAt))
}

fun Shop.toFirestore() = mapOf(
    "id" to id, "ownerId" to ownerId, "name" to name, "description" to description,
    "logoUrl" to logoUrl, "coverUrl" to coverUrl, "category" to category,
    "locationLat" to location.lat, "locationLng" to location.lng, "locationAddress" to locationAddress,
    "isVerified" to isVerified, "isActive" to isActive, "rating" to rating,
    "reviewCount" to reviewCount, "followerCount" to followerCount, "listingCount" to listingCount
)

fun Shop.toUpdateMap() = mapOf(
    "name" to name,
    "description" to description,
    "logoUrl" to logoUrl,
    "coverUrl" to coverUrl,
    "category" to category,
    "locationLat" to location.lat,
    "locationLng" to location.lng,
    "locationAddress" to locationAddress,
    "isActive" to isActive
)

data class FirestoreListing(
    val id: String = "",
    val shopId: String = "",
    val sellerId: String = "",
    val title: String = "",
    val description: String = "",
    val priceMinorUnits: Long = 0L,
    val priceCurrency: String = "LSL",
    val imageUrls: List<String> = emptyList(),
    val videoUrl: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val listingType: String = "BUY",
    @get:PropertyName("isAvailable") @set:PropertyName("isAvailable") var isAvailable: Boolean = true,
    @get:PropertyName("isSponsored") @set:PropertyName("isSponsored") var isSponsored: Boolean = false,
    val stockQuantity: Int = 1,
    val commitmentCount: Int = 0,
    val bookmarkCount: Int = 0,
    val deliveryEstimateDays: Int = 0,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    fun toDomain(isLiked: Boolean = false, isBookmarked: Boolean = false) = Listing(id, shopId, sellerId, title, description, MoneyAmount(priceCurrency, priceMinorUnits), imageUrls, videoUrl, category, tags, runCatching { ListingType.valueOf(listingType) }.getOrDefault(ListingType.BUY), isAvailable, isSponsored, stockQuantity, commitmentCount, deliveryEstimateDays, emptyList(), tsToLong(createdAt), tsToLong(updatedAt), commentCount, bookmarkCount, isLiked, isBookmarked, likeCount)
}

fun Listing.toFirestore() = mapOf(
    "id" to id, "shopId" to shopId, "sellerId" to sellerId, "title" to title, "description" to description,
    "priceMinorUnits" to price.minorUnits, "priceCurrency" to price.currency,
    "imageUrls" to imageUrls, "videoUrl" to videoUrl, "category" to category, "tags" to tags,
    "listingType" to listingType.name, "isAvailable" to isAvailable, "isSponsored" to isSponsored,
    "stockQuantity" to stockQuantity,
    "commitmentCount" to commitmentCount, "deliveryEstimateDays" to deliveryEstimateDays,
    "createdAt" to createdAt, "updatedAt" to updatedAt
)

data class FirestoreCartItem(
    val listingId: String = "",
    val shopId: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val quantity: Int = 1,
    val unitPriceMinorUnits: Long = 0L,
    val unitPriceCurrency: String = "LSL"
) {
    fun toDomain() = CartItem(listingId, shopId, title, imageUrl, quantity, MoneyAmount(unitPriceCurrency, unitPriceMinorUnits), emptyMap())
}

fun CartItem.toFirestore() = mapOf(
    "listingId" to listingId, "shopId" to shopId, "title" to title, "imageUrl" to imageUrl,
    "quantity" to quantity, "unitPriceMinorUnits" to unitPrice.minorUnits, "unitPriceCurrency" to unitPrice.currency
)

data class FirestoreOrder(
    val id: String = "",
    val buyerId: String = "",
    val sellerId: String = "",
    val shopId: String = "",
    val items: List<FirestoreOrderItem> = emptyList(),
    val subtotalMinorUnits: Long = 0L,
    val deliveryFeeMinorUnits: Long = 0L,
    val platformFeeMinorUnits: Long = 0L,
    val totalMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val status: String = "PENDING",
    val inventoryStatus: String = "PENDING",
    val settlementStatus: String = "PENDING",
    val deliveryAddress: FirestoreDeliveryAddress? = null,
    @get:PropertyName("requiresDelivery") @set:PropertyName("requiresDelivery") var requiresDelivery: Boolean = false,
    val selectedDeliveryListingId: String = "",
    val deliveryRequestId: String = "",
    val paymentId: String = "",
    val notes: String = "",
    val reservationExpiresAt: Any? = null,
    val createdAt: Any? = null
) {
    fun toDomain() = Order(
        id, buyerId, sellerId, shopId,
        items.map { it.toDomain() },
        MoneyAmount(currency, subtotalMinorUnits),
        MoneyAmount(currency, deliveryFeeMinorUnits),
        MoneyAmount(currency, platformFeeMinorUnits),
        MoneyAmount(currency, totalMinorUnits),
        runCatching { OrderStatus.valueOf(status) }.getOrDefault(OrderStatus.PENDING),
        runCatching { InventoryStatus.valueOf(inventoryStatus) }.getOrDefault(InventoryStatus.PENDING),
        runCatching { SettlementStatus.valueOf(settlementStatus) }.getOrDefault(SettlementStatus.PENDING),
        deliveryAddress?.toDomain() ?: DeliveryAddress(),
        requiresDelivery, selectedDeliveryListingId, deliveryRequestId, paymentId, notes,
        tsToLong(reservationExpiresAt),
        tsToLong(createdAt), 0L
    )
}

data class FirestoreOrderItem(
    val listingId: String = "",
    val title: String = "",
    val quantity: Int = 1,
    val unitPriceMinorUnits: Long = 0L,
    val unitPriceCurrency: String = "LSL"
) {
    fun toDomain() = OrderItem(listingId, title, quantity, MoneyAmount(unitPriceCurrency, unitPriceMinorUnits), emptyMap())
}

data class FirestoreDeliveryAddress(
    val label: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val streetHint: String = "",
    val city: String = "Maseru",
    val district: String = "",
    val country: String = "Lesotho"
) {
    fun toDomain() = DeliveryAddress(label, lat, lng, streetHint, city, district, country)
}

fun Order.toFirestore() = mapOf(
    "id" to id, "buyerId" to buyerId, "sellerId" to sellerId, "shopId" to shopId,
    "items" to items.map { it.toFirestore() },
    "subtotalMinorUnits" to subtotal.minorUnits, "deliveryFeeMinorUnits" to deliveryFee.minorUnits,
    "platformFeeMinorUnits" to platformFee.minorUnits, "totalMinorUnits" to total.minorUnits,
    "currency" to total.currency, "status" to status.name, "paymentId" to paymentId,
    "requiresDelivery" to requiresDelivery,
    "selectedDeliveryListingId" to selectedDeliveryListingId,
    "deliveryRequestId" to deliveryRequestId, "notes" to notes,
    "deliveryAddress" to deliveryAddress.toFirestore(),
    "createdAt" to createdAt
)

fun OrderItem.toFirestore() = mapOf(
    "listingId" to listingId, "title" to title, "quantity" to quantity,
    "unitPriceMinorUnits" to unitPrice.minorUnits, "unitPriceCurrency" to unitPrice.currency,
    "selectedOptions" to selectedOptions
)

fun DeliveryAddress.toFirestore() = mapOf(
    "label" to label, "lat" to lat, "lng" to lng, "streetHint" to streetHint,
    "city" to city, "district" to district, "country" to country
)

fun PaymentRequest.toFirestore() = mapOf(
    "orderId" to orderId, "amount" to amount.toFirestore(), "method" to method.name,
    "phoneNumber" to phoneNumber, "idempotencyKey" to idempotencyKey
)


