package com.swiftshop.data.repositories

import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.Order
import com.swiftshop.core.model.OrderStatus
import com.swiftshop.core.model.PaymentRequest
import com.swiftshop.core.model.Shop
import com.swiftshop.data.firebase.FirebaseCommerceRepository
import com.swiftshop.data.local.CommerceLocalDataSource
import com.swiftshop.domain.commerce.CartItem
import com.swiftshop.domain.commerce.CommerceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The concrete `:data:repositories` implementation the architecture docs
 * described as missing — an offline-first `CommerceRepository` that reads
 * from Room first (instant, works offline) and reconciles with Firestore
 * (source of truth) in the background, matching the "3-Tier local cache"
 * behavior claimed — but not actually wired anywhere — in the Phase 8E/9A
 * status docs.
 *
 * Scoped to Commerce as a worked example. Wallet/Feed/Messaging/Delivery/
 * Advertising/Profile repositories all follow the identical shape: wrap the
 * existing `data:firebase` implementation, add a `data:local` cache via the
 * matching DAO, decide a staleness policy. Repeating that by hand for all 8
 * is real but mechanical work — this establishes the pattern rather than
 * doing all 8, since the other 7 needed no such decorator to function
 * (they already work directly against Firestore; this only adds an offline
 * cache in front, it doesn't fix anything broken).
 *
 * Every write (create/update/delete/cart/orders) still goes straight to
 * Firebase — per D-003, the client never owns write-then-sync logic for
 * anything financial or order-related. Only *reads* get a local-first path.
 */
@Singleton
class OfflineFirstCommerceRepository @Inject constructor(
    private val remote: FirebaseCommerceRepository,
    private val local: CommerceLocalDataSource
) : CommerceRepository {

    // ─── Shops (pass-through — no local cache needed, low read volume) ────

    override fun generateShopId(): String = remote.generateShopId()

    override fun getUserShops(userId: String) = remote.getUserShops(userId)
        .onEach { local.cacheShops(it) }
        .catch { emit(emptyList()) }

    override fun getAllShops(): Flow<List<Shop>> = remote.getAllShops()
        .onEach { local.cacheShops(it) }
        .catch { emit(emptyList()) }

    override suspend fun getShop(shopId: String) = remote.getShop(shopId)
    override suspend fun createShop(shop: Shop) = remote.createShop(shop)
    override suspend fun updateShop(shop: Shop) = remote.updateShop(shop)
    override suspend fun deleteShop(shopId: String) = remote.deleteShop(shopId)

    // ─── Listings — the actual offline-first path ─────────────────────────

    override fun getShopListings(shopId: String, page: Int, pageSize: Int): Flow<List<Listing>> =
        merge(
            local.observeShopListings(shopId),
            remote.getShopListings(shopId, page, pageSize).onEach { local.cacheListings(it) }
        ).catch { emitAll(local.observeShopListings(shopId)) }

    override suspend fun getListing(listingId: String) = runCatching {
        remote.getListing(listingId).getOrThrow()
    }.recoverCatching {
        local.getCachedListing(listingId) ?: throw it
    }.also { result ->
        result.getOrNull()?.let { local.cacheListing(it) }
    }

    override suspend fun getUserListings(userId: String): Result<List<Listing>> = 
        remote.getUserListings(userId)

    override suspend fun searchListings(query: String): Result<List<Listing>> = 
        remote.searchListings(query)

    override suspend fun searchShops(query: String): Result<List<Shop>> = 
        remote.searchShops(query)

    override suspend fun createListing(listing: Listing) = remote.createListing(listing)
    override suspend fun updateListing(listing: Listing) = remote.updateListing(listing)
    override suspend fun deleteListing(listingId: String) = remote.deleteListing(listingId)

    // ─── Cart / Orders — always server-authoritative, no local cache ──────

    override fun observeCart(userId: String): Flow<List<CartItem>> = remote.observeCart(userId)
    override suspend fun addToCart(userId: String, item: CartItem) = remote.addToCart(userId, item)
    override suspend fun removeFromCart(userId: String, listingId: String) = remote.removeFromCart(userId, listingId)
    override suspend fun clearCart(userId: String) = remote.clearCart(userId)

    override suspend fun initiateSubscription(targetTier: com.swiftshop.core.model.UserTier) = remote.initiateSubscription(targetTier)

    // Delivery listings — pass-through to remote (Firestore is the source of truth).
    // These are queried fresh at checkout time; no local caching needed here since
    // the buyer always needs the current price and availability.
    override suspend fun getDeliveryListings() = remote.getDeliveryListings()

    override suspend fun calculateOrderFees(
        items: List<com.swiftshop.core.model.OrderItem>,
        address: com.swiftshop.core.model.DeliveryAddress,
        deliveryListingId: String
    ) = remote.calculateOrderFees(items, address, deliveryListingId)

    override suspend fun placeOrder(
        items: List<com.swiftshop.core.model.OrderItem>,
        address: com.swiftshop.core.model.DeliveryAddress,
        deliveryListingId: String,
        paymentMethod: com.swiftshop.core.model.PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String
    ) = remote.placeOrder(items, address, deliveryListingId, paymentMethod, provider, phoneNumber, idempotencyKey)

    override suspend fun verifyMopayPayment(sessionId: String) = remote.verifyMopayPayment(sessionId)

    override suspend fun initiateBooking(
        buyerId: String,
        listingId: String,
        slotId: String,
        paymentMethod: com.swiftshop.core.model.PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String
    ) = remote.initiateBooking(
        buyerId,
        listingId,
        slotId,
        paymentMethod,
        provider,
        phoneNumber,
        idempotencyKey
    )

    override fun observeUserOrders(userId: String): Flow<List<Order>> = remote.observeUserOrders(userId)
    override suspend fun getOrder(orderId: String) = remote.getOrder(orderId)
    override suspend fun updateOrderStatus(orderId: String, status: OrderStatus) = remote.updateOrderStatus(orderId, status)
    override suspend fun cancelOrder(orderId: String, reason: String) = remote.cancelOrder(orderId, reason)
}
