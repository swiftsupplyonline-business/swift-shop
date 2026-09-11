package com.swiftshop.domain.commerce

import com.swiftshop.core.model.*
import kotlinx.coroutines.flow.Flow

// ─── Entitlement Rules (driven by data, not hard-coded) ──────────────────────

data class TierEntitlement(
    val tier: UserTier,
    val maxShops: Int,          // -1 = unlimited
    val includedListingsPerShop: Int,         // used for BASIC
    val totalIncludedListings: Int,           // used for PREMIUM
    val additionalListingFeeMinorUnits: Long,
    val monthlyFeeMinorUnits: Long,
    val internalPromotionAllowance: Int,
    val internalPromotionUnlimited: Boolean,
    val externalPromotionAllowance: Int,
    val externalPromotionUnlimited: Boolean,
    val exposureLevel: ExposureLevel,
    val salesAnalyticsLevel: AnalyticsLevel,
    val advertAnalyticsEnabled: Boolean
)

data class OrderSummary(
    val subtotal: MoneyAmount = MoneyAmount.ZERO,
    val deliveryFee: MoneyAmount = MoneyAmount.ZERO,
    val platformFee: MoneyAmount = MoneyAmount.ZERO,
    val total: MoneyAmount = MoneyAmount.ZERO
)

object TierEntitlements {
    // These values are loaded from remote config in production.
    // Hard-coded here only as defaults.
    val BASIC = TierEntitlement(
        tier = UserTier.BASIC,
        maxShops = 3,
        includedListingsPerShop = 10,
        totalIncludedListings = -1, // Only per-shop for BASIC
        additionalListingFeeMinorUnits = 500L, // M5.00
        monthlyFeeMinorUnits = 0L,
        internalPromotionAllowance = 1,
        internalPromotionUnlimited = false,
        externalPromotionAllowance = 0,
        externalPromotionUnlimited = false,
        exposureLevel = ExposureLevel.STANDARD,
        salesAnalyticsLevel = AnalyticsLevel.BASIC,
        advertAnalyticsEnabled = false
    )
    val PREMIUM = TierEntitlement(
        tier = UserTier.PREMIUM,
        maxShops = 5,
        includedListingsPerShop = -1, 
        totalIncludedListings = 50,
        additionalListingFeeMinorUnits = 500L, // M5.00
        monthlyFeeMinorUnits = 9900L,  // M99.00
        internalPromotionAllowance = -1,
        internalPromotionUnlimited = true,
        externalPromotionAllowance = 10,
        externalPromotionUnlimited = false,
        exposureLevel = ExposureLevel.ENHANCED,
        salesAnalyticsLevel = AnalyticsLevel.ADVANCED,
        advertAnalyticsEnabled = true
    )
    val ELITE = TierEntitlement(
        tier = UserTier.ELITE,
        maxShops = -1,
        includedListingsPerShop = -1,
        totalIncludedListings = -1,
        additionalListingFeeMinorUnits = 0L,
        monthlyFeeMinorUnits = 49900L, // M499.00
        internalPromotionAllowance = -1,
        internalPromotionUnlimited = true,
        externalPromotionAllowance = 50,
        externalPromotionUnlimited = false,
        exposureLevel = ExposureLevel.MAXIMUM,
        salesAnalyticsLevel = AnalyticsLevel.FULL,
        advertAnalyticsEnabled = true
    )

    fun forTier(tier: UserTier): TierEntitlement = when (tier) {
        UserTier.BASIC -> BASIC
        UserTier.PREMIUM -> PREMIUM
        UserTier.ELITE -> ELITE
    }
}

// ─── Repository Interfaces ────────────────────────────────────────────────────

interface CommerceRepository {
    // Shops
    fun generateShopId(): String = "" // Placeholder for interface consistency
    fun getUserShops(userId: String): Flow<List<Shop>>
    fun getAllShops(): Flow<List<Shop>> = kotlinx.coroutines.flow.emptyFlow()
    suspend fun getShop(shopId: String): Result<Shop>
    suspend fun createShop(shop: Shop): Result<String>
    suspend fun updateShop(shop: Shop): Result<Unit>
    suspend fun deleteShop(shopId: String): Result<Unit> = Result.success(Unit)

    // Listings
    fun getShopListings(shopId: String, page: Int, pageSize: Int): Flow<List<Listing>>
    suspend fun getListing(listingId: String): Result<Listing>
    suspend fun getUserListings(userId: String): Result<List<Listing>>
    fun observeUserListings(userId: String): Flow<List<Listing>> = kotlinx.coroutines.flow.emptyFlow()
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

    // Merchant
    fun observeMerchantUsage(userId: String): Flow<MerchantUsage?> = kotlinx.coroutines.flow.emptyFlow()
    fun observeSubscription(userId: String): Flow<Subscription?> = kotlinx.coroutines.flow.emptyFlow()

    // Orders
    suspend fun calculateOrderFees(items: List<OrderItem>, address: DeliveryAddress): Result<OrderSummary>
    suspend fun placeOrder(
        items: List<OrderItem>,
        address: DeliveryAddress,
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

// ─── Use Cases ────────────────────────────────────────────────────────────────

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

class CalculateOrderFeesUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(items: List<OrderItem>, address: DeliveryAddress): Result<OrderSummary> =
        repository.calculateOrderFees(items, address)
}

class PlaceOrderUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(
        items: List<OrderItem>,
        address: DeliveryAddress,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String
    ): Result<OrderInitiation> {
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("Cart is empty"))
        if (idempotencyKey.isBlank()) return Result.failure(IllegalArgumentException("Idempotency key required"))
        // Server-side will re-validate all prices and calculate authoritative fees
        return repository.placeOrder(items, address, paymentMethod, provider, phoneNumber, idempotencyKey)
    }
}

class VerifyMopayPaymentUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(sessionId: String): Result<Unit> = repository.verifyMopayPayment(sessionId)
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
