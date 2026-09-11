package com.swiftshop.domain.commerce

import com.swiftshop.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

// â”€â”€â”€ Entitlement Rules (driven by data, not hard-coded) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class TierEntitlement(
    val tier: UserTier,
    val maxShops: Int,                        // -1 = unlimited
    val includedListingsPerShop: Int,         // used for BASIC
    val totalIncludedListings: Int,           // used for PREMIUM
    val additionalListingFeeMinorUnits: Long,
    val monthlyFeeMinorUnits: Long,
    val internalPromotionAllowance: Int,      // per week
    val internalPromotionUnlimited: Boolean,
    val externalPromotionAllowance: Int,      // per week
    val externalPromotionUnlimited: Boolean,
    val exposureLevel: ExposureLevel,
    val salesAnalyticsLevel: AnalyticsLevel,
    val advertAnalyticsEnabled: Boolean
)

data class OrderSummary(
    val subtotal: MoneyAmount = MoneyAmount.ZERO,
    val deliveryFee: MoneyAmount = MoneyAmount.ZERO,
    val platformFee: MoneyAmount = MoneyAmount.ZERO,
    val total: MoneyAmount = MoneyAmount.ZERO,
    // The delivery listing that sourced deliveryFee — null when no delivery applies.
    val selectedDeliveryListingId: String = "",
    val orderType: OrderType = OrderType.PRODUCT_PURCHASE
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
    fun observeUserListings(userId: String): Flow<List<Listing>>
    suspend fun searchListings(query: String): Result<List<Listing>>

    suspend fun searchShops(query: String): Result<List<Shop>>
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
        idempotencyKey: String,
        recipientUid: String? = null,
        payload: OrderPayload? = null,
        customerResponses: List<CustomerFieldResponse> = emptyList(),
        notes: String = ""
    ): Result<OrderInitiation>


    suspend fun initiateBooking(
        buyerId: String,
        listingId: String,
        slotId: String,
        paymentMethod: PaymentMethod,
        provider: String?,
        phoneNumber: String,
        idempotencyKey: String,
        locationType: FulfillmentType = FulfillmentType.AT_PROVIDER
    ): Result<OrderInitiation>
    suspend fun verifyMopayPayment(sessionId: String): Result<Unit>
    fun observeUserOrders(userId: String): Flow<List<Order>>

    /**
     * Observes orders where the user occupies a specific role.
     * Supports Buyer (REQUESTER), Seller (SELLER), etc.
     */
    fun observeOrdersByRole(userId: String, role: OrderRole): Flow<List<Order>>

    suspend fun getOrder(orderId: String): Result<Order>
    suspend fun updateOrderStatus(orderId: String, status: OrderStatus): Result<Unit>
    suspend fun cancelOrder(orderId: String, reason: String): Result<Unit>
    // Merchant
    fun observeMerchantUsage(userId: String): Flow<MerchantUsage?>
    fun observeSubscription(userId: String): Flow<Subscription?>
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

class SearchShopsUseCase(private val repository: CommerceRepository) {
    suspend operator fun invoke(query: String): Result<List<Shop>> = repository.searchShops(query)
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
        idempotencyKey: String,
        recipientUid: String? = null,
        payload: OrderPayload? = null,
        customerResponses: List<CustomerFieldResponse> = emptyList(),
        notes: String = ""
    ): Result<OrderInitiation> {
        if (items.isEmpty()) return Result.failure(IllegalArgumentException("Cart is empty"))
        if (idempotencyKey.isBlank()) return Result.failure(IllegalArgumentException("Idempotency key required"))
        if (deliveryListingId.isBlank()) return Result.failure(IllegalArgumentException("A delivery option must be selected"))
        // Server-side validates deliveryListingId and reads the canonical price from the listing.
        // The client must never pass a fee amount — the backend is the only financial authority.
        return repository.placeOrder(items, address, deliveryListingId, paymentMethod, provider, phoneNumber, idempotencyKey, recipientUid, payload, customerResponses, notes)
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

class ObserveMerchantEntitlementsUseCase(
    private val repository: CommerceRepository,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase
) {
    operator fun invoke(): Flow<MerchantEntitlement> = flow {
        val user = observeCurrentUser().first() ?: return@flow
        repository.observeSubscription(user.uid).collect { sub ->
            val tier = if (sub?.isActive == true) sub.tier else UserTier.BASIC
            val entitlement = TierEntitlements.forTier(tier)
            emit(
                MerchantEntitlement(
                    tier = tier,
                    maxShops = entitlement.maxShops,
                    includedListingsPerShop = entitlement.includedListingsPerShop,
                    totalIncludedListings = entitlement.totalIncludedListings,
                    additionalListingFee = MoneyAmount("LSL", entitlement.additionalListingFeeMinorUnits),
                    monthlyFee = MoneyAmount("LSL", entitlement.monthlyFeeMinorUnits),
                    internalPromotionAllowance = entitlement.internalPromotionAllowance,
                    internalPromotionUnlimited = entitlement.internalPromotionUnlimited,
                    externalPromotionAllowance = entitlement.externalPromotionAllowance,
                    externalPromotionUnlimited = entitlement.externalPromotionUnlimited,
                    exposureLevel = entitlement.exposureLevel,
                    salesAnalyticsLevel = entitlement.salesAnalyticsLevel,
                    advertAnalyticsEnabled = entitlement.advertAnalyticsEnabled
                )
            )
        }
    }
}

class CheckShopCreationEligibilityUseCase(
    private val repository: CommerceRepository,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase
) {
    suspend operator fun invoke(): Boolean {
        val user = observeCurrentUser().first() ?: return false
        val sub = repository.observeSubscription(user.uid).first()
        val tier = if (sub?.isActive == true) sub.tier else UserTier.BASIC
        val entitlement = TierEntitlements.forTier(tier)
        
        if (entitlement.maxShops == -1) return true
        
        val shops = repository.getUserShops(user.uid).first()
        return shops.size < entitlement.maxShops
    }
}

class CheckListingEligibilityUseCase(
    private val repository: CommerceRepository,
    private val observeCurrentUser: com.swiftshop.domain.auth.ObserveCurrentUserUseCase
) {
    sealed interface Result {
        data object Included : Result
        data object AdditionalFeeRequired : Result
        data object Denied : Result
    }

    suspend operator fun invoke(shopId: String): Result {
        val user = observeCurrentUser().first() ?: return Result.Denied
        val sub = repository.observeSubscription(user.uid).first()
        val tier = if (sub?.isActive == true) sub.tier else UserTier.BASIC
        val entitlement = TierEntitlements.forTier(tier)
        
        if (entitlement.totalIncludedListings == -1) return Result.Included
        
        val allListings = repository.getUserListings(user.uid).getOrNull() ?: emptyList()
        
        return when (tier) {
            UserTier.BASIC -> {
                val shopListings = allListings.filter { it.shopId == shopId }
                if (shopListings.size < entitlement.includedListingsPerShop) Result.Included
                else Result.AdditionalFeeRequired
            }
            UserTier.PREMIUM -> {
                if (allListings.size < entitlement.totalIncludedListings) Result.Included
                else Result.AdditionalFeeRequired
            }
            else -> Result.Included
        }
    }
}

