package com.swiftshop.domain.commerce

import com.swiftshop.core.model.*
import kotlinx.coroutines.flow.Flow

// â”€â”€â”€ Entitlement Rules (driven by data, not hard-coded) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class TierEntitlement(
    val tier: UserTier,
    val maxShops: Int,          // -1 = unlimited
    val freeListings: Int,      // per shop
    val additionalListingFeeMinorUnits: Long,
    val monthlyFeeMinorUnits: Long
)

data class OrderSummary(
    val subtotal: MoneyAmount = MoneyAmount.ZERO,
    val deliveryFee: MoneyAmount = MoneyAmount.ZERO,
    val platformFee: MoneyAmount = MoneyAmount.ZERO,
    val total: MoneyAmount = MoneyAmount.ZERO,
    // The delivery listing that sourced deliveryFee â€” null when no delivery applies.
    val selectedDeliveryListingId: String = ""
)

object TierEntitlements {
    // These values are loaded from remote config in production.
    // Hard-coded here only as defaults.
    val BASIC = TierEntitlement(
        tier = UserTier.BASIC,
        maxShops = 1,
        freeListings = 3,
        additionalListingFeeMinorUnits = 500L, // M5.00
        monthlyFeeMinorUnits = 0L
    )
    val PREMIUM = TierEntitlement(
        tier = UserTier.PREMIUM,
        maxShops = 3,
        freeListings = Int.MAX_VALUE,
        additionalListingFeeMinorUnits = 0L,
        monthlyFeeMinorUnits = 9900L  // M99.00
    )
    val ELITE = TierEntitlement(
        tier = UserTier.ELITE,
        maxShops = Int.MAX_VALUE,
        freeListings = Int.MAX_VALUE,
        additionalListingFeeMinorUnits = 0L,
        monthlyFeeMinorUnits = 49900L // M499.00
    )

    fun forTier(tier: UserTier): TierEntitlement = when (tier) {
        UserTier.BASIC -> BASIC
        UserTier.PREMIUM -> PREMIUM
        UserTier.ELITE -> ELITE
    }
}

// â”€â”€â”€ Repository Interfaces â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

interface CommerceRepository {
    // Shops
    fun generateShopId(): String
    fun getUserShops(userId: String): Flow<List<Shop>>
    fun getAllShops(): Flow<List<Shop>>
    suspend fun getShop(shopId: String): Result<Shop>
    suspend fun createShop(shop: Shop): Result<String>
    suspend fun updateShop(shop: Shop): Result<Unit>
    suspend fun deleteShop(shopId: String): Result<Unit>

    // Listings
    fun getShopListings(shopId: String, page: Int, pageSize: Int): Flow<List<Listing>>
    suspend fun getListing(listingId: String): Result<Listing>
    suspend fun getUserListings(userId: String): Result<List<Listing>>
    suspend fun searchListings(query: String): Result<List<Listing>>
    suspend fun createListing(listing: Listing): Result<String>
    suspend fun updateListing(listing: Listing): Result<Unit>
    suspend fun deleteListing(listingId: String): Result<Unit>

    // Cart
    fun observeCart(userId: String): Flow<List<CartItem>>
    suspend fun addToCart(userId: String, item: CartItem): Result<Unit>
    suspend fun removeFromCart(userId: String, listingId: String): Result<Unit>
    suspend fun clearCart(userId: String): Result<Unit>

    // Subscriptions
    suspend fun initiateSubscription(targetTier: UserTier): Result<String>

    // Delivery Listings
    // Returns all active DELIVER-type listings available for selection at checkout.
    // The fee shown here is the canonical price â€” the backend validates against it.
    suspend fun getDeliveryListings(): Result<List<DeliveryListing>>

    // Orders
    // deliveryListingId must refer to an active DeliveryListing.  The backend
    // reads the listing's price authoritatively â€” the client must not pass a fee amount.
    suspend fun calculateOrderFees(
        items: List<OrderItem>,
        address: DeliveryAddress,
        deliveryListingId: String
    ): Result<OrderSummary>
    suspend fun placeOrder(
        items: List<OrderItem>,
        address: DeliveryAddress,
        deliveryListingId: String,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String
    ): Result<OrderInitiation>
    suspend fun initiateBooking(
        buyerId: String,
        listingId: String,
        slotId: String,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String
    ): Result<OrderInitiation>
    suspend fun verifyMopayPayment(sessionId: String): Result<Unit>
    fun observeUserOrders(userId: String): Flow<List<Order>>
    suspend fun getOrder(orderId: String): Result<Order>
    suspend fun updateOrderStatus(orderId: String, status: OrderStatus): Result<Unit>
    suspend fun cancelOrder(orderId: String, reason: String): Result<Unit>
}

data class CartItem(
    val listingId: String = "",
    val shopId: String = "",
    val title: String = "",
    val imageUrl: String = "",
    val quantity: Int = 1,
    val unitPrice: MoneyAmount = MoneyAmount.ZERO,
    val selectedOptions: Map<String, String> = emptyMap()
)

// â”€â”€â”€ Use Cases â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class GetShopUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(shopId: String): Result<Shop> = repository.getShop(shopId)
}

class GetShopListingsUseCase(private val repository: CommerceRepository) {
    operator fun invoke(shopId: String, page: Int = 0, pageSize: Int = 20): Flow<List<Listing>> =
        repository.getShopListings(shopId, page, pageSize)
}

class GetListingUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(listingId: String): Result<Listing> = repository.getListing(listingId)
}

class GetUserListingsUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(userId: String): Result<List<Listing>> = repository.getUserListings(userId)
}

class SearchListingsUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(query: String): Result<List<Listing>> = repository.searchListings(query)
}

class ObserveCartUseCase(private val repository: CommerceRepository) {
    operator fun invoke(userId: String): Flow<List<CartItem>> = repository.observeCart(userId)
}

class AddToCartUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(userId: String, item: CartItem): Result<Unit> = repository.addToCart(userId, item)
}

class RemoveFromCartUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(userId: String, listingId: String): Result<Unit> = repository.removeFromCart(userId, listingId)
}

class ClearCartUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(userId: String): Result<Unit> = repository.clearCart(userId)
}

class GetDeliveryListingsUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(): Result<List<DeliveryListing>> = repository.getDeliveryListings()
}

class CalculateOrderFeesUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(
        items: List<OrderItem>,
        address: DeliveryAddress,
        deliveryListingId: String
    ): Result<OrderSummary> = repository.calculateOrderFees(items, address, deliveryListingId)
}

class PlaceOrderUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(
        items: List<OrderItem>,
        address: DeliveryAddress,
        deliveryListingId: String,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String
    ): Result<OrderInitiation> {
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("Cart is empty"))
        if (idempotencyKey.isBlank()) return Result.failure(IllegalArgumentException("Idempotency key required"))
        if (deliveryListingId.isBlank()) return Result.failure(IllegalArgumentException("A delivery option must be selected"))
        // Server-side validates deliveryListingId and reads the canonical price from the listing.
        // The client must never pass a fee amount â€” the backend is the only financial authority.
        return repository.placeOrder(items, address, deliveryListingId, paymentMethod, provider, phoneNumber, idempotencyKey)
    }
}

class VerifyMopayPaymentUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(sessionId: String): Result<Unit> = repository.verifyMopayPayment(sessionId)
}

class GetOrderUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(orderId: String): Result<Order> = repository.getOrder(orderId)
}

class CanCreateShopUseCase {
    operator fun invoke(tier: UserTier, existingShopCount: Int): Boolean {
        val entitlement = TierEntitlements.forTier(tier)
        return entitlement.maxShops == -1 || existingShopCount < entitlement.maxShops
    }
}

class CreateShopUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(shop: Shop): Result<String> = repository.createShop(shop)
}

class UpdateShopUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(shop: Shop): Result<Unit> = repository.updateShop(shop)
}

class DeleteShopUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(shopId: String): Result<Unit> = repository.deleteShop(shopId)
}

class DeleteListingUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(listingId: String): Result<Unit> = repository.deleteListing(listingId)
}

