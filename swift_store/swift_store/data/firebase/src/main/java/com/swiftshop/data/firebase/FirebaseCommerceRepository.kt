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
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

import javax.inject.Singleton


@Singleton
class FirebaseCommerceRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions
) : CommerceRepository {

    override fun generateShopId(): String = firestore.collection("shops").document().id

    override fun getUserShops(userId: String): Flow<List<Shop>> = callbackFlow {
        val subscription = firestore.collection("shops")
            .whereEqualTo("ownerId", userId)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreShop::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override fun getAllShops(): Flow<List<Shop>> = callbackFlow {
        val subscription = firestore.collection("shops")
            .whereEqualTo("isActive", true)
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
        val data = shop.toCreateRequest()
        val result = functions.getHttpsCallable("createShop").call(data).await()
        result.data as String
    }

    override suspend fun updateShop(shop: Shop): Result<Unit> = runCatching {
        firestore.collection("shops").document(shop.id).update(shop.toUpdateMap()).await()
        Unit
    }

    override suspend fun deleteShop(shopId: String): Result<Unit> = runCatching {
        val data = mapOf("shopId" to shopId)
        functions.getHttpsCallable("deleteShop").call(data).await()
        Unit
    }

    // --- Listings -----------------------------------------------------------

    override fun getShopListings(shopId: String, page: Int, pageSize: Int): Flow<List<Listing>> = callbackFlow {
        val subscription = firestore.collection("listings")
            .whereEqualTo("shopId", shopId)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreListing::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun getListing(listingId: String): Result<Listing> = runCatching {
        firestore.collection("listings").document(listingId).get().await()
            .toObject(FirestoreListing::class.java)?.toDomain() ?: throw NoSuchElementException("Listing not found")
    }

    override suspend fun getUserListings(userId: String): Result<List<Listing>> = runCatching {
        firestore.collection("listings")
            .whereEqualTo("sellerId", userId)
            .get().await()
            .toObjects(FirestoreListing::class.java).map { it.toDomain() }
    }

    override fun observeUserListings(userId: String): Flow<List<Listing>> = callbackFlow {
        val subscription = firestore.collection("listings")
            .whereEqualTo("sellerId", userId)
            .addSnapshotListener { snapshot, _ ->
                val list = snapshot?.toObjects(FirestoreListing::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(list)
            }
        awaitClose { subscription.remove() }
    }


    override suspend fun searchListings(query: String): Result<List<Listing>> = runCatching {
        firestore.collection("listings")
            .whereGreaterThanOrEqualTo("title", query)
            .whereLessThanOrEqualTo("title", query + "\uf8ff")
            .get().await()
            .toObjects(FirestoreListing::class.java).map { it.toDomain() }
    }

    override suspend fun searchShops(query: String): Result<List<Shop>> = runCatching {
        firestore.collection("shops")
            .whereGreaterThanOrEqualTo("name", query)
            .whereLessThanOrEqualTo("name", query + "\uf8ff")
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

    // --- Delivery Listings --------------------------------------------------

    override suspend fun getDeliveryListings(): Result<List<DeliveryListing>> = runCatching {
        firestore.collection("listings")
            .whereIn("listingType", listOf("DELIVER", "DELIVERY_SERVICE"))
            .whereEqualTo("isAvailable", true)
            .get().await()
            .toObjects(FirestoreDeliveryListingDto::class.java)
            .map { it.toDomain() }
    }


    // --- Cart ---------------------------------------------------------------

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

    // --- Subscriptions ------------------------------------------------------

    override suspend fun initiateSubscription(targetTier: UserTier): Result<String> = runCatching {
        val data = mapOf("targetTier" to targetTier.name)
        val result = functions.getHttpsCallable("initiateSubscription").call(data).await()
        result.data as String
    }

    // --- Orders -------------------------------------------------------------

    override suspend fun calculateOrderFees(
        items: List<OrderItem>,
        address: DeliveryAddress,
        deliveryListingId: String
    ): Result<OrderSummary> = runCatching {
        val data = mapOf(
            "items" to items.map { it.toFirestore() },
            "deliveryAddress" to address.toFirestore(),
            "deliveryListingId" to deliveryListingId
        )
        val result = functions.getHttpsCallable("calculateOrderFees").call(data).await()
        val resMap = result.data as Map<String, Any>

        val currency = resMap["currency"] as? String ?: "LSL"
        OrderSummary(
            subtotal = MoneyAmount(currency, (resMap["subtotalMinorUnits"] as Number).toLong()),
            deliveryFee = MoneyAmount(currency, (resMap["deliveryFeeMinorUnits"] as Number).toLong()),
            platformFee = MoneyAmount(currency, (resMap["platformFeeMinorUnits"] as Number).toLong()),
            total = MoneyAmount(currency, (resMap["totalMinorUnits"] as Number).toLong()),
            selectedDeliveryListingId = deliveryListingId,
            orderType = runCatching { OrderType.valueOf(resMap["orderType"] as? String ?: "") }.getOrDefault(OrderType.PRODUCT_PURCHASE)
        )
    }

    override suspend fun placeOrder(
        items: List<OrderItem>,
        address: DeliveryAddress,
        deliveryListingId: String,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String,
        recipientUid: String?,
        payload: OrderPayload?,
        customerResponses: List<CustomerFieldResponse>,
        notes: String
    ): Result<OrderInitiation> = runCatching {
        val data = buildMap {
            put("items", items.map { it.toFirestore() })
            put("deliveryAddress", address.toFirestore())
            put("deliveryListingId", deliveryListingId)
            put("paymentMethod", paymentMethod.name)
            put("provider", provider)
            put("phoneNumber", phoneNumber)
            put("idempotencyKey", idempotencyKey)
            recipientUid?.let { put("recipientUid", it) }
            payload?.let { put("payload", it.toFirestore()) }
            put("customerResponses", customerResponses.map { it.toFirestore() })
            put("notes", notes)
        }

        val result = functions.getHttpsCallable("createOrder").call(data).await()
        val resMap = result.data as Map<String, Any>

        val error = resMap["error"] as? String
        if (error != null) throw Exception(error)

        OrderInitiation(
            orderId = resMap["orderId"] as String,
            paymentUrl = resMap["paymentUrl"] as? String,
            mopaySessionId = resMap["mopaySessionId"] as? String
        )
    }


    override suspend fun initiateBooking(
        buyerId: String,
        listingId: String,
        slotId: String,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String,
        locationType: FulfillmentType
    ): Result<OrderInitiation> = runCatching {
        val data = mapOf(
            "listingId" to listingId,
            "slotId" to slotId,
            "paymentMethod" to paymentMethod.name,
            "provider" to provider,
            "phoneNumber" to phoneNumber,
            "idempotencyKey" to idempotencyKey,
            "payload" to mapOf("locationType" to locationType.name)
        )
        val result = functions.getHttpsCallable("initiateServiceBooking").call(data).await()
        val resMap = result.data as Map<String, Any>
        OrderInitiation(
            orderId = resMap["orderId"] as String,
            paymentUrl = resMap["paymentUrl"] as? String,
            mopaySessionId = resMap["mopaySessionId"] as? String
        )
    }

    override suspend fun verifyMopayPayment(sessionId: String): Result<Unit> = runCatching {
        val data = mapOf("sessionId" to sessionId)
        val result = functions.getHttpsCallable("verifyMopayPayment").call(data).await()
        val resMap = result.data as? Map<String, Any>
        val status = resMap?.get("status") as? String
        if (status != "SUCCESS") {
            throw Exception(status ?: "Verification failed")
        }
        Unit
    }

    override fun observeUserOrders(userId: String): Flow<List<Order>> = observeOrdersByRole(userId, OrderRole.REQUESTER)

    override fun observeOrdersByRole(userId: String, role: OrderRole): Flow<List<Order>> = callbackFlow {
        val subscription = firestore.collection("orders")
            .whereEqualTo("participants.${role.name}", userId)
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
        val data = mapOf("orderId" to orderId, "status" to status.name)
        functions.getHttpsCallable("updateOrderStatus").call(data).await()
        Unit
    }

    override suspend fun cancelOrder(orderId: String, reason: String): Result<Unit> = runCatching {
        val data = mapOf("orderId" to orderId, "reason" to reason)
        functions.getHttpsCallable("cancelOrder").call(data).await()
        Unit
    }

    override fun observeMerchantUsage(userId: String): Flow<MerchantUsage?> = callbackFlow {
        val subscription = firestore.collection("merchantUsage").document(userId)
            .addSnapshotListener { snapshot, _ ->
                val usage = snapshot?.toObject(FirestoreMerchantUsage::class.java)?.toDomain()
                trySend(usage)
            }
        awaitClose { subscription.remove() }
    }

    override fun observeSubscription(userId: String): Flow<Subscription?> = callbackFlow {
        val subscription = firestore.collection("subscriptions")
            .whereEqualTo("userId", userId)
            .whereEqualTo("isActive", true)
            .limit(1)
            .addSnapshotListener { snapshot, _ ->
                val sub = snapshot?.toObjects(FirestoreSubscription::class.java)?.firstOrNull()?.toDomain()
                trySend(sub)
            }
        awaitClose { subscription.remove() }
    }
}

// --- DTOs & Mappers ---------------------------------------------------------

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
    "reviewCount" to reviewCount, "followerCount" to followerCount, "listingCount" to listingCount,
    "createdAt" to createdAt
)

/**
 * Creates a JSON-safe map for the createShop Cloud Function.
 * Excludes Firestore sentinels and server-authoritative fields.
 */
fun Shop.toCreateRequest() = mapOf(
    "id" to id,
    "name" to name,
    "description" to description,
    "category" to category,
    "locationLat" to location.lat,
    "locationLng" to location.lng,
    "locationAddress" to locationAddress,
    "logoUrl" to logoUrl,
    "coverUrl" to coverUrl,
    "isActive" to isActive
)

fun Shop.toUpdateMap() = mapOf(
    "name" to name, "description" to description, "logoUrl" to logoUrl, "coverUrl" to coverUrl,
    "category" to category, "locationLat" to location.lat, "locationLng" to location.lng,
    "locationAddress" to locationAddress, "isActive" to isActive
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
    val totalQuantity: Int = 1,
    val reservedQuantity: Int = 0,
    val availableQuantity: Int = 1,
    val commitmentCount: Int = 0,
    val bookmarkCount: Int = 0,
    val deliveryEstimateDays: Int = 0,
    val durationMinutes: Int = 0,
    val fulfillmentOptions: List<String> = emptyList(),
    val customFields: List<FirestoreCustomField> = emptyList(),
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    fun toDomain() = Listing(
        id = id,
        shopId = shopId,
        sellerId = sellerId,
        title = title,
        description = description,
        price = MoneyAmount(priceCurrency, priceMinorUnits),
        imageUrls = imageUrls,
        videoUrl = videoUrl,
        category = category,
        tags = tags,
        listingType = runCatching { ListingType.valueOf(listingType) }.getOrDefault(ListingType.BUY),
        isAvailable = isAvailable,
        isSponsored = isSponsored,
        stockQuantity = stockQuantity,
        totalQuantity = totalQuantity,
        reservedQuantity = reservedQuantity,
        availableQuantity = availableQuantity,
        commitmentCount = commitmentCount,
        bookmarkCount = bookmarkCount,
        isBookmarkedByMe = false, // derived at read-time if needed
        deliveryEstimateDays = deliveryEstimateDays,
        durationMinutes = durationMinutes,
        fulfillmentOptions = fulfillmentOptions.mapNotNull { safeEnumValueOf<FulfillmentType>(it) },
        customFields = customFields.map { it.toDomain() },
        createdAt = tsToLong(createdAt),
        updatedAt = tsToLong(updatedAt)
    )
}

data class FirestoreCustomField(
    val id: String = "",
    val label: String = "",
    val type: String = "text",
    val options: List<String> = emptyList(),
    @get:PropertyName("isRequired") @set:PropertyName("isRequired") var isRequired: Boolean = false
) {
    fun toDomain() = CustomField(id, label, type, options, isRequired)
}

fun CustomField.toFirestore() = mapOf(
    "id" to id, "label" to label, "type" to type, "options" to options, "isRequired" to isRequired
)


fun Listing.toFirestore() = mapOf(
    "id" to id, "shopId" to shopId, "sellerId" to sellerId, "title" to title, "description" to description,
    "priceMinorUnits" to price.minorUnits, "priceCurrency" to price.currency,
    "imageUrls" to imageUrls, "videoUrl" to videoUrl, "category" to category, "tags" to tags,
    "listingType" to listingType.name, "isAvailable" to isAvailable, "isSponsored" to isSponsored,
    "stockQuantity" to stockQuantity,
    "totalQuantity" to totalQuantity,
    "reservedQuantity" to reservedQuantity,
    "availableQuantity" to availableQuantity,
    "commitmentCount" to commitmentCount,
    "bookmarkCount" to bookmarkCount,
    "deliveryEstimateDays" to deliveryEstimateDays,
    "durationMinutes" to durationMinutes,
    "fulfillmentOptions" to fulfillmentOptions.map { it.name },
    "customFields" to customFields.map { it.toFirestore() },
    "createdAt" to createdAt, "updatedAt" to updatedAt
)


data class FirestoreDeliveryListingDto(
    val id: String = "",
    val shopId: String = "",
    val sellerId: String = "",
    val title: String = "",
    val description: String = "",
    val priceMinorUnits: Long = 0L,
    val priceCurrency: String = "LSL",
    val imageUrls: List<String> = emptyList(),
    val coverageArea: String = "",
    val estimatedMinutes: Int = 0,
    @get:PropertyName("isAvailable") @set:PropertyName("isAvailable") var isAvailable: Boolean = true,
    val rating: Float = 0f,
    val completedDeliveries: Int = 0,
    val providerName: String = "",
    val providerAvatarUrl: String = "",
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    fun toDomain() = DeliveryListing(
        id = id,
        shopId = shopId,
        providerId = sellerId,
        providerName = providerName,
        providerAvatarUrl = providerAvatarUrl,
        title = title,
        description = description,
        price = MoneyAmount(priceCurrency, priceMinorUnits),
        estimatedMinutes = estimatedMinutes,
        coverageArea = coverageArea,
        isAvailable = isAvailable,
        rating = rating,
        completedDeliveries = completedDeliveries,
        imageUrl = imageUrls.firstOrNull() ?: "",
        createdAt = tsToLong(createdAt),
        updatedAt = tsToLong(updatedAt)
    )
}

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
    val type: String? = null,
    val sourceListingId: String? = null,
    val listingSnapshot: FirestoreListingSnapshot? = null,
    val participants: Map<String, String>? = null,
    val payload: Map<String, Any>? = null,
    val items: List<FirestoreOrderItem> = emptyList(),
    val subtotalMinorUnits: Long = 0L,
    val deliveryFeeMinorUnits: Long = 0L,
    val platformFeeMinorUnits: Long = 0L,
    val totalMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val status: String = "PENDING",
    val paymentStatus: String? = null,
    val fulfillmentStatus: String? = null,
    val settlementStatus: String? = null,
    val inventoryStatus: String? = null,
    val deliveryAddress: FirestoreDeliveryAddress? = null,
    val selectedDeliveryListingId: String = "",
    val deliveryListingSnapshot: FirestoreDeliveryListingSnapshot? = null,
    val recipientUid: String? = null,
    val paymentId: String = "",
    val customerResponses: List<FirestoreCustomerFieldResponse> = emptyList(),
    val notes: String = "",

    val slotId: String? = null,
    val fulfillmentType: String? = null,
    val appointmentStartTime: Long? = null,
    val originLocationSnapshot: FirestoreLocationSnapshot? = null,
    val destinationLocationSnapshot: FirestoreLocationSnapshot? = null,
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    fun toDomain(): Order {
        val domainStatus = runCatching { OrderStatus.valueOf(status) }.getOrDefault(OrderStatus.PENDING)
        return Order(
            id = id,
            buyerId = buyerId,
            sellerId = sellerId,
            shopId = shopId,
            type = runCatching { OrderType.valueOf(type ?: "LEGACY") }.getOrDefault(OrderType.LEGACY),
            sourceListingId = sourceListingId ?: "",
            listingSnapshot = listingSnapshot?.toDomain(),
            participants = participants ?: buildMap {
                // Legacy mapping: ensure canonical roles are populated for older documents
                if (buyerId.isNotBlank()) put(OrderRole.REQUESTER.name, buyerId)
                if (sellerId.isNotBlank()) put(OrderRole.SELLER.name, sellerId)
                // If it's a service booking, listing author is the provider
                if (type == "SERVICE_BOOKING" && sellerId.isNotBlank()) {
                    put(OrderRole.SERVICE_PROVIDER.name, sellerId)
                    put(OrderRole.LISTING_AUTHOR.name, sellerId)
                }
            },
            payload = mapPayload(type, payload),
            items = items.map { it.toDomain() },
            subtotal = MoneyAmount(currency, subtotalMinorUnits),
            deliveryFee = MoneyAmount(currency, deliveryFeeMinorUnits),
            platformFee = MoneyAmount(currency, platformFeeMinorUnits),
            total = MoneyAmount(currency, totalMinorUnits),
            status = domainStatus,
            paymentStatus = runCatching { PaymentStatus.valueOf(paymentStatus ?: "") }.getOrElse {
                // Derive from legacy status
                when (domainStatus) {
                    OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.READY,
                    OrderStatus.DISPATCHED, OrderStatus.DELIVERED -> PaymentStatus.PAID
                    OrderStatus.REFUNDED -> PaymentStatus.REFUNDED
                    else -> PaymentStatus.PENDING
                }
            },
            fulfillmentStatus = runCatching { FulfillmentStatus.valueOf(fulfillmentStatus ?: "") }.getOrElse {
                when (domainStatus) {
                    OrderStatus.DISPATCHED -> FulfillmentStatus.DISPATCHED
                    OrderStatus.DELIVERED -> FulfillmentStatus.DELIVERED
                    OrderStatus.READY -> FulfillmentStatus.READY
                    OrderStatus.PROCESSING -> FulfillmentStatus.PREPARING
                    else -> FulfillmentStatus.PENDING
                }
            },
            settlementStatus = runCatching { SettlementStatus.valueOf(settlementStatus ?: "") }.getOrElse {
                when (domainStatus) {
                    OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.READY,
                    OrderStatus.DISPATCHED, OrderStatus.DELIVERED -> SettlementStatus.ESCROW_HOLD
                    else -> SettlementStatus.PENDING
                }
            },
            inventoryStatus = runCatching { InventoryStatus.valueOf(inventoryStatus ?: "") }.getOrElse {
                when (domainStatus) {
                    OrderStatus.RESERVED -> InventoryStatus.RESERVED
                    OrderStatus.CONFIRMED, OrderStatus.PROCESSING, OrderStatus.READY,
                    OrderStatus.DISPATCHED, OrderStatus.DELIVERED -> InventoryStatus.COMMITTED
                    OrderStatus.CANCELLED -> InventoryStatus.RELEASED
                    else -> InventoryStatus.PENDING
                }
            },
            deliveryAddress = deliveryAddress?.toDomain() ?: DeliveryAddress(),
            selectedDeliveryListingId = selectedDeliveryListingId,
            deliveryListingSnapshot = deliveryListingSnapshot?.toDomain(),
            recipientUid = recipientUid,
            paymentId = paymentId,
            customerResponses = customerResponses.map { it.toDomain() },
            notes = notes,

            slotId = slotId,
            fulfillmentType = safeEnumValueOf<FulfillmentType>(fulfillmentType),
            appointmentStartTime = appointmentStartTime,
            originLocationSnapshot = originLocationSnapshot?.toDomain(),
            destinationLocationSnapshot = destinationLocationSnapshot?.toDomain(),
            createdAt = tsToLong(createdAt),
            updatedAt = tsToLong(updatedAt)
        )
    }
}


data class FirestoreListingSnapshot(
    val listingId: String = "",
    val shopId: String = "",
    val sellerId: String = "",
    val sellerName: String = "",
    val title: String = "",
    val description: String = "",
    val priceMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val listingType: String = "",
    val category: String = "",
    val variantId: String? = null,
    val fulfillmentOptions: List<String> = emptyList(),
    val durationMinutes: Int = 0,
    val snapshotAt: Long = 0L
) {
    fun toDomain() = ListingSnapshot(
        listingId = listingId,
        shopId = shopId,
        sellerId = sellerId,
        sellerName = sellerName,
        title = title,
        description = description,
        price = MoneyAmount(currency, priceMinorUnits),
        listingType = listingType,
        category = category,
        variantId = variantId,
        fulfillmentOptions = fulfillmentOptions.mapNotNull { safeEnumValueOf<FulfillmentType>(it) },
        durationMinutes = durationMinutes,
        snapshotAt = snapshotAt
    )
}

fun ListingSnapshot.toFirestore(): Map<String, Any?> = mapOf(
    "listingId" to listingId, "shopId" to shopId, "sellerId" to sellerId,
    "sellerName" to sellerName,
    "title" to title, "description" to description,
    "priceMinorUnits" to price.minorUnits, "currency" to price.currency,
    "listingType" to listingType, "category" to category,
    "variantId" to variantId,
    "fulfillmentOptions" to fulfillmentOptions.map { it.name },
    "durationMinutes" to durationMinutes,
    "snapshotAt" to snapshotAt
)

data class FirestoreDeliveryListingSnapshot(
    val listingId: String = "",
    val providerId: String = "",
    val providerName: String = "",
    val title: String = "",
    val priceMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val estimatedMinutes: Int = 0
) {
    fun toDomain() = DeliveryListingSnapshot(
        listingId = listingId,
        providerId = providerId,
        providerName = providerName,
        title = title,
        priceMinorUnits = priceMinorUnits,
        currency = currency,
        estimatedMinutes = estimatedMinutes
    )
}

fun DeliveryListingSnapshot.toFirestore(): Map<String, Any?> = mapOf(
    "listingId" to listingId,
    "providerId" to providerId,
    "providerName" to providerName,
    "title" to title,
    "priceMinorUnits" to priceMinorUnits,
    "currency" to currency,
    "estimatedMinutes" to estimatedMinutes
)


data class FirestoreOrderItem(
    val listingId: String = "",
    val title: String = "",
    val quantity: Int = 1,
    val unitPriceMinorUnits: Long = 0L,
    val unitPriceCurrency: String = "LSL"
) {
    fun toDomain() = OrderItem(listingId, title, quantity, MoneyAmount(unitPriceCurrency, unitPriceMinorUnits), emptyMap())
}

data class FirestoreCustomerFieldResponse(
    val fieldId: String = "",
    val label: String = "",
    val value: String = "",
    val displayValue: String? = null
) {
    fun toDomain() = CustomerFieldResponse(fieldId, label, value, displayValue)
}

fun CustomerFieldResponse.toFirestore() = mapOf(
    "fieldId" to fieldId, "label" to label, "value" to value, "displayValue" to displayValue
)

data class FirestoreLocationSnapshot(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val addressSnapshot: String = "",
    val instructions: String = ""
) {
    fun toDomain() = LocationSnapshot(lat, lng, addressSnapshot, instructions)
}

fun LocationSnapshot.toFirestore(): Map<String, Any?> = mapOf(

    "lat" to lat,
    "lng" to lng,
    "addressSnapshot" to addressSnapshot,
    "instructions" to instructions
)

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

fun Order.toFirestore(): Map<String, Any?> = buildMap {
    putAll(mapOf(
        "id" to id, "buyerId" to buyerId, "sellerId" to sellerId, "shopId" to shopId,
        "type" to type.name,
        "sourceListingId" to sourceListingId,
        "listingSnapshot" to listingSnapshot?.toFirestore(),
        "participants" to participants,
        "items" to items.map { it.toFirestore() },
        "subtotalMinorUnits" to subtotal.minorUnits, "deliveryFeeMinorUnits" to deliveryFee.minorUnits,
        "platformFeeMinorUnits" to platformFee.minorUnits, "totalMinorUnits" to total.minorUnits,
        "currency" to total.currency, "status" to status.name,
        "paymentStatus" to paymentStatus.name,
        "fulfillmentStatus" to fulfillmentStatus.name,
        "settlementStatus" to settlementStatus.name,
        "inventoryStatus" to inventoryStatus.name,
        "paymentId" to paymentId,
        "deliveryAddress" to deliveryAddress.toFirestore(),
        "selectedDeliveryListingId" to selectedDeliveryListingId,
        "deliveryListingSnapshot" to deliveryListingSnapshot?.toFirestore(),
        "recipientUid" to recipientUid,
        "customerResponses" to customerResponses.map { it.toFirestore() },
        "notes" to notes,
        "fulfillmentType" to fulfillmentType?.name,
        "originLocationSnapshot" to originLocationSnapshot?.toFirestore(),

        "destinationLocationSnapshot" to destinationLocationSnapshot?.toFirestore(),
        "createdAt" to createdAt,
        "updatedAt" to updatedAt
    ))
    payload?.let { put("payload", it.toFirestore()) }
}



private fun mapPayload(type: String?, data: Map<String, Any>?): OrderPayload? {
    if (data == null) return null
    return try {
        when (runCatching { OrderType.valueOf(type ?: "") }.getOrNull()) {
            OrderType.PRODUCT_PURCHASE -> OrderPayload.ProductPurchase(
                variantId = data["variantId"] as? String,
                quantity = (data["quantity"] as? Number)?.toInt() ?: 1,
                unitPrice = (data["unitPriceMinorUnits"] as? Number)?.let { 
                    MoneyAmount(data["unitPriceCurrency"] as? String ?: "LSL", it.toLong())
                } ?: MoneyAmount.ZERO,
                buyerNotes = data["buyerNotes"] as? String
            )
            OrderType.FOOD_ORDER -> OrderPayload.FoodOrder(
                items = (data["items"] as? List<Map<String, Any>>)?.map { 
                    FoodOrderItem(
                        id = it["id"] as? String ?: "",
                        title = it["title"] as? String ?: "",
                        quantity = (it["quantity"] as? Number)?.toInt() ?: 1,
                        addOns = (it["addOns"] as? List<String>) ?: emptyList()
                    )
                } ?: emptyList(),
                preparationNotes = data["preparationNotes"] as? String,
                requestedDeliveryTime = (data["requestedDeliveryTime"] as? Number)?.toLong()
            )
            OrderType.SERVICE_BOOKING -> OrderPayload.ServiceBooking(
                serviceId = data["serviceId"] as? String ?: "",
                requestedDate = data["requestedDate"] as? String ?: "",
                requestedTime = data["requestedTime"] as? String ?: "",
                durationMinutes = (data["durationMinutes"] as? Number)?.toInt() ?: 0,
                locationType = data["locationType"] as? String ?: "ON_SITE"
            )
            OrderType.BULK_PURCHASE -> OrderPayload.BulkPurchase(
                quantity = (data["quantity"] as? Number)?.toDouble() ?: 0.0,
                unitOfMeasure = data["unitOfMeasure"] as? String ?: "",
                pricingTier = data["pricingTier"] as? String
            )
            OrderType.DELIVERY_REQUEST -> OrderPayload.DeliveryRequest(
                pickupLocation = (data["pickupLocation"] as? Map<String, Any>)?.let { 
                    LocationSnapshot(
                        lat = (it["lat"] as? Number)?.toDouble() ?: 0.0,
                        lng = (it["lng"] as? Number)?.toDouble() ?: 0.0,
                        addressSnapshot = it["addressSnapshot"] as? String ?: "",
                        instructions = it["instructions"] as? String ?: ""
                    )
                },
                destinationLocation = (data["destinationLocation"] as? Map<String, Any>)?.let { 
                    LocationSnapshot(
                        lat = (it["lat"] as? Number)?.toDouble() ?: 0.0,
                        lng = (it["lng"] as? Number)?.toDouble() ?: 0.0,
                        addressSnapshot = it["addressSnapshot"] as? String ?: "",
                        instructions = it["instructions"] as? String ?: ""
                    )
                },
                packageDescription = data["packageDescription"] as? String ?: "",
                recipientName = data["recipientName"] as? String ?: "",
                recipientPhone = data["recipientPhone"] as? String ?: "",
                recipientUid = data["recipientUid"] as? String,
                instructions = data["instructions"] as? String ?: ""
            )
            else -> null
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to map OrderPayload of type $type")
        null
    }
}


internal fun OrderPayload.toFirestore(): Map<String, Any?> = when (this) {

    is OrderPayload.ProductPurchase -> buildMap {
        variantId?.let { put("variantId", it) }
        put("quantity", quantity)
        put("unitPriceMinorUnits", unitPrice.minorUnits)
        put("unitPriceCurrency", unitPrice.currency)
        buyerNotes?.let { put("buyerNotes", it) }
    }
    is OrderPayload.FoodOrder -> buildMap {
        put("items", items.map { mapOf("id" to it.id, "title" to it.title, "quantity" to it.quantity, "addOns" to it.addOns) })
        preparationNotes?.let { put("preparationNotes", it) }
        requestedDeliveryTime?.let { put("requestedDeliveryTime", it) }
    }
    is OrderPayload.ServiceBooking -> mapOf(
        "serviceId" to serviceId,
        "requestedDate" to requestedDate,
        "requestedTime" to requestedTime,
        "durationMinutes" to durationMinutes,
        "locationType" to locationType
    )
    is OrderPayload.BulkPurchase -> buildMap {
        put("quantity", quantity)
        put("unitOfMeasure", unitOfMeasure)
        pricingTier?.let { put("pricingTier", it) }
    }
    is OrderPayload.DeliveryRequest -> buildMap {
        pickupLocation?.let { put("pickupLocation", it.toFirestore()) }
        destinationLocation?.let { put("destinationLocation", it.toFirestore()) }
        put("packageDescription", packageDescription)
        put("recipientName", recipientName)
        put("recipientPhone", recipientPhone)
        recipientUid?.let { put("recipientUid", it) }
        put("instructions", instructions)
    }
}





fun OrderItem.toFirestore() = mapOf(
    "listingId" to listingId, "title" to title, "quantity" to quantity,
    "unitPriceMinorUnits" to unitPrice.minorUnits, "unitPriceCurrency" to unitPrice.currency,
    "selectedOptions" to selectedOptions
)

fun DeliveryAddress.toFirestore(): Map<String, Any?> = mapOf(

    "label" to label, "lat" to lat, "lng" to lng, "streetHint" to streetHint,
    "city" to city, "district" to district, "country" to country
)

fun PaymentRequest.toFirestore() = mapOf(
    "orderId" to orderId, "amount" to amount.toFirestore(), "method" to method.name,
    "phoneNumber" to phoneNumber, "idempotencyKey" to idempotencyKey
)

data class FirestoreMerchantUsage(
    val userId: String = "",
    val periodStart: Long = 0L,
    val periodEnd: Long = 0L,
    val internalPromotionsUsed: Int = 0,
    val externalPromotionsUsed: Int = 0,
    val updatedAt: Any? = null
) {
    fun toDomain() = MerchantUsage(
        userId = userId,
        periodStart = periodStart,
        periodEnd = periodEnd,
        internalPromotionsUsed = internalPromotionsUsed,
        externalPromotionsUsed = externalPromotionsUsed,
        updatedAt = tsToLong(updatedAt)
    )
}

data class FirestoreSubscription(
    val id: String = "",
    val userId: String = "",
    val tier: String = "BASIC",
    val monthlyFeeMinorUnits: Long = 0L,
    val monthlyFeeCurrency: String = "LSL",
    val status: String = "PENDING",
    @get:PropertyName("isActive") @set:PropertyName("isActive") var isActive: Boolean = false,
    val provider: String = "MOPAY",
    val gatewayTransactionId: String? = null,
    val paymentReference: String? = null,
    val startedAt: Long = 0L,
    val renewsAt: Long = 0L,
    val cancelledAt: Long = 0L,
    val updatedAt: Any? = null
) {
    fun toDomain() = Subscription(
        id = id,
        userId = userId,
        tier = runCatching { UserTier.valueOf(tier) }.getOrDefault(UserTier.BASIC),
        monthlyFee = MoneyAmount(monthlyFeeCurrency, monthlyFeeMinorUnits),
        status = runCatching { SubscriptionStatus.valueOf(status) }.getOrDefault(SubscriptionStatus.PENDING),
        isActive = isActive,
        provider = provider,
        gatewayTransactionId = gatewayTransactionId,
        paymentReference = paymentReference,
        startedAt = startedAt,
        renewsAt = renewsAt,
        cancelledAt = cancelledAt,
        updatedAt = tsToLong(updatedAt)
    )
}
