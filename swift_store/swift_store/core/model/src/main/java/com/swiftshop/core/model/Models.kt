package com.swiftshop.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.UUID

// ── Money ─────────────────────────────────────────────────────────────────────────

/**
 * Exact monetary representation. Never use Double for money.
 * minorUnits: amount in smallest currency unit (e.g. lisente for LSL).
 */
@Serializable
@Parcelize
data class MoneyAmount(
    val currency: String = "LSL",
    val minorUnits: Long  // e.g. 9900 = M99.00
) : Parcelable {
    companion object {
        val ZERO = MoneyAmount("LSL", 0L)
        fun fromMajorUnits(major: Double, currency: String = "LSL"): MoneyAmount =
            MoneyAmount(currency, (major * 100).toLong())
    }
    fun toDisplayString(): String = "${currency} ${minorUnits / 100}.${(minorUnits % 100).toString().padStart(2, '0')}"
    operator fun plus(other: MoneyAmount): MoneyAmount {
        require(currency == other.currency) { "Currency mismatch" }
        return copy(minorUnits = minorUnits + other.minorUnits)
    }
    operator fun minus(other: MoneyAmount): MoneyAmount {
        require(currency == other.currency) { "Currency mismatch" }
        return copy(minorUnits = minorUnits - other.minorUnits)
    }
}

// ── Tier ──────────────────────────────────────────────────────────────────────────

enum class UserTier {
    BASIC,   // Free: 1 shop, unlimited listings
    PREMIUM, // M99/mo: 3 shops, unlimited listings
    ELITE    // M499/mo: unlimited shops, max exposure
}

// ── Account Status ───────────────────────────────────────────────────────────────

enum class UserAccountStatus {
    ACTIVE,
    LIMITED,
    UNDER_REVIEW,
    FROZEN,
    SUSPENDED,
    BANNED,
    DELETED
}

// ── User / Auth ──────────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class User(
    val uid: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val tier: UserTier = UserTier.BASIC,
    val isVerified: Boolean = false,
    val accountStatus: UserAccountStatus = UserAccountStatus.ACTIVE,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) : Parcelable

@Serializable
@Parcelize
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val coverUrl: String = "",
    val avatarUrl: String = "",
    val bio: String = "",
    val location: String = "",
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val shopCount: Int = 0,
    val postCount: Int = 0,
    val activeListingCount: Int = 0,
    val reputationScore: Float = 0f,
    val tier: UserTier = UserTier.BASIC,
    val isFollowedByMe: Boolean = false,
    val totalDeliveries: Int = 0,
    val achievements: List<Achievement> = emptyList()
) : Parcelable

// ── Achievement ──────────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class Achievement(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val earnedAt: Long = 0L
) : Parcelable

// ── Shop ──────────────────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class Shop(
    val id: String = "",
    val ownerId: String = "",
    val name: String = "",
    val description: String = "",
    val logoUrl: String = "",
    val coverUrl: String = "",
    val category: String = "",
    val location: GeoPoint = GeoPoint(),
    val locationAddress: String = "",
    val isVerified: Boolean = false,
    val isActive: Boolean = true,
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val followerCount: Int = 0,
    val listingCount: Int = 0,
    val createdAt: Long = 0L
) : Parcelable

// ── Listing / Product ─────────────────────────────────────────────────────────────

enum class SlotStatus {
    AVAILABLE,
    RESERVED,
    BOOKED
}

@Serializable
@Parcelize
data class AvailabilitySlot(
    val id: String = "",
    val providerId: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val status: SlotStatus = SlotStatus.AVAILABLE,
    val orderId: String? = null,
    val reservedBy: String? = null,
    val expiresAt: Long? = null
) : Parcelable

enum class AppointmentStatus {
    SCHEDULED, COMPLETED, CANCELLED
}

@Serializable
@Parcelize
data class Appointment(
    val id: String = "",
    val orderId: String = "",
    val buyerId: String = "",
    val sellerId: String = "",
    val shopId: String = "",
    val listingId: String = "",
    val listingTitle: String = "",
    val appointmentStartTime: Long = 0L,
    val status: AppointmentStatus = AppointmentStatus.SCHEDULED,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) : Parcelable

enum class ListingType {
    // Legacy Taxonomy
    PRODUCT, SERVICE, BUY, MAKE_PAYMENT, SET_APPOINTMENT, PLACE_ORDER, REGISTER, DELIVER, TAKE_ME_THERE,
    
    // Maseru-First Taxonomy
    PHYSICAL_ITEM,
    PREPARED_FOOD,
    BOOKABLE_SERVICE,
    BULK_SUPPLY,
    DELIVERY_SERVICE;

    /**
     * Maps legacy types to the new Maseru-First taxonomy.
     */
    fun toCanonical(): ListingType = when (this) {
        PRODUCT -> PHYSICAL_ITEM
        SERVICE -> BOOKABLE_SERVICE
        BUY -> PHYSICAL_ITEM
        else -> this
    }
}

@Serializable
@Parcelize
data class Listing(
    val id: String = "",
    val shopId: String = "",
    val sellerId: String = "",
    val title: String = "",
    val description: String = "",
    val price: MoneyAmount = MoneyAmount.ZERO,
    val imageUrls: List<String> = emptyList(),
    val videoUrl: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val listingType: ListingType = ListingType.BUY,
    val isAvailable: Boolean = true,
    val isSponsored: Boolean = false,
    @Deprecated("Use totalQuantity - reservedQuantity")
    val stockQuantity: Int = 1,
    val totalQuantity: Int = 1,    // Authoritative physical stock
    val reservedQuantity: Int = 0, // Active holds
    val availableQuantity: Int = 1, // Derived/Calculated
    val commitmentCount: Int = 0,
    val bookmarkCount: Int = 0,
    val isBookmarkedByMe: Boolean = false,
    val deliveryEstimateDays: Int = 0,
    val customFields: List<CustomField> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) : Parcelable {
    fun toSnapshot() = ListingSnapshot(
        listingId = id,
        shopId = shopId,
        sellerId = sellerId,
        title = title,
        description = description,
        price = price,
        listingType = listingType.name,
        category = category,
        snapshotAt = System.currentTimeMillis()
    )
}

@Serializable
@Parcelize
data class CustomField(
    val id: String = "",
    val label: String = "",
    val type: String = "text",
    val options: List<String> = emptyList(),
    val isRequired: Boolean = false
) : Parcelable

// ── Feed Post ─────────────────────────────────────────────────────────────────────

enum class PostType { IMAGE, CAROUSEL, REEL }

@Serializable
@Parcelize
data class FeedPost(
    val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",
    val authorTier: UserTier = UserTier.BASIC,
    val shopId: String = "",
    val type: PostType = PostType.IMAGE,
    val caption: String = "",
    val mediaUrls: List<String> = emptyList(),
    val videoUrl: String = "",
    val thumbnailUrl: String = "",
    val videoDurationMs: Long = 0L,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val reshareCount: Int = 0,
    val bookmarkCount: Int = 0,
    val hashtags: List<String> = emptyList(),
    val mentions: List<String> = emptyList(),
    val taggedListings: List<String> = emptyList(),
    val taggedShops: List<String> = emptyList(),
    val isLikedByMe: Boolean = false,
    val isBookmarkedByMe: Boolean = false,
    val isSponsored: Boolean = false,
    val rankingScore: Double = 0.0,
    val createdAt: Long = 0L
) : Parcelable

@Serializable
@Parcelize
data class Bookmark(
    val contentId: String = "",
    val contentType: String = "",
    val createdAt: Long = 0L
) : Parcelable

// ── Comment ───────────────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class Comment(
    val id: String = "",
    val postId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",
    val text: String = "",
    val parentCommentId: String = "",
    val replyCount: Int = 0,
    val likeCount: Int = 0,
    val createdAt: Long = 0L
) : Parcelable

// ── Order / Commerce ──────────────────────────────────────────────────────────────

enum class OrderStatus {
    PENDING, 
    RESERVED,         // Inventory hold acquired
    PAYMENT_PENDING,  // Gateway session active
    CONFIRMED, 
    PROCESSING, 
    READY, 
    DISPATCHED, 
    DELIVERED, 
    CANCELLED, 
    REFUNDED
}

/** Independent state contracts to prevent state conflation. */
enum class PaymentStatus { PENDING, AUTHORIZED, PAID, FAILED, REFUNDED }
enum class FulfillmentStatus { PENDING, PREPARING, READY, DISPATCHED, DELIVERED, RETURNED }
enum class SettlementStatus { PENDING, ESCROW_HOLD, RELEASED, SETTLED }
enum class InventoryStatus { PENDING, RESERVED, COMMITTED, RELEASED }

/** Explicit transaction/workflow types derived from the originating Listing. */
enum class OrderType {
    PRODUCT_PURCHASE,
    FOOD_ORDER,
    SERVICE_BOOKING,
    BULK_PURCHASE,
    DELIVERY_REQUEST,
    LEGACY
}

/** Roles a user can occupy on an order. */
enum class OrderRole {
    REQUESTER,
    LISTING_AUTHOR,
    SELLER,
    SERVICE_PROVIDER,
    DELIVERY_PROVIDER,
    RECIPIENT,
    ADMIN
}

/**
 * A transactional snapshot of the Listing state at Order creation.
 */
@Serializable
@Parcelize
data class ListingSnapshot(
    val listingId: String = "",
    val shopId: String = "",
    val sellerId: String = "",
    val sellerName: String = "",
    val title: String = "",
    val description: String = "",
    val price: MoneyAmount = MoneyAmount.ZERO,
    val listingType: String = "",
    val category: String = "",
    val variantId: String? = null,
    val fulfillmentOptions: List<String> = emptyList(),
    val snapshotAt: Long = 0L
) : Parcelable

/**
 * Controlled polymorphic payload for type-specific Order data.
 */
@Serializable
@Parcelize
sealed class OrderPayload : Parcelable {
    @Serializable @Parcelize
    data class ProductPurchase(
        val variantId: String? = null,
        val quantity: Int = 1,
        val unitPrice: MoneyAmount = MoneyAmount.ZERO,
        val buyerNotes: String? = null
    ) : OrderPayload()

    @Serializable @Parcelize
    data class FoodOrder(
        val items: List<FoodOrderItem> = emptyList(),
        val preparationNotes: String? = null,
        val requestedDeliveryTime: Long? = null
    ) : OrderPayload()

    @Serializable @Parcelize
    data class ServiceBooking(
        val serviceId: String = "",
        val requestedDate: String = "",
        val requestedTime: String = "",
        val durationMinutes: Int = 0,
        val locationType: String = "ON_SITE"
    ) : OrderPayload()

    @Serializable @Parcelize
    data class BulkPurchase(
        val quantity: Double = 0.0,
        val unitOfMeasure: String = "",
        val pricingTier: String? = null
    ) : OrderPayload()

    @Serializable @Parcelize
    data class DeliveryRequest(
        val pickupLocation: LocationSnapshot? = null,
        val destinationLocation: LocationSnapshot? = null,
        val packageDescription: String = "",
        val recipientName: String = "",
        val recipientPhone: String = "",
        val instructions: String = ""
    ) : OrderPayload()
}

@Serializable
@Parcelize
data class FoodOrderItem(
    val id: String = "",
    val title: String = "",
    val quantity: Int = 1,
    val addOns: List<String> = emptyList()
) : Parcelable

@Serializable
@Parcelize
data class Order(
    val id: String = "",
    val buyerId: String = "",         // Legacy compatibility
    val sellerId: String = "",        // Legacy compatibility
    val shopId: String = "",
    val type: OrderType = OrderType.LEGACY,
    val sourceListingId: String = "",
    val listingSnapshot: ListingSnapshot? = null,
    val participants: Map<String, String> = emptyMap(), // Role.name -> userId
    val payload: OrderPayload? = null,
    val items: List<OrderItem> = emptyList(),
    val subtotal: MoneyAmount = MoneyAmount.ZERO,
    val deliveryFee: MoneyAmount = MoneyAmount.ZERO,
    val platformFee: MoneyAmount = MoneyAmount.ZERO,
    val total: MoneyAmount = MoneyAmount.ZERO,
    val status: OrderStatus = OrderStatus.PENDING,
    val paymentStatus: PaymentStatus = PaymentStatus.PENDING,
    val fulfillmentStatus: FulfillmentStatus = FulfillmentStatus.PENDING,
    val settlementStatus: SettlementStatus = SettlementStatus.PENDING,
    val inventoryStatus: InventoryStatus = InventoryStatus.PENDING,
    val deliveryAddress: DeliveryAddress = DeliveryAddress(),
    val selectedDeliveryListingId: String = "",
    val deliveryListingSnapshot: DeliveryListingSnapshot? = null,
    val paymentId: String = "",
    val paymentUrl: String? = null,
    val mopaySessionId: String? = null,
    val slotId: String? = null,
    val fulfillmentType: String? = null,
    val appointmentStartTime: Long? = null,
    val originLocationSnapshot: LocationSnapshot? = null,
    val destinationLocationSnapshot: LocationSnapshot? = null,
    val notes: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) : Parcelable

@Serializable
@Parcelize
data class OrderItem(
    val listingId: String = "",
    val title: String = "",
    val quantity: Int = 1,
    val unitPrice: MoneyAmount = MoneyAmount.ZERO,
    val selectedOptions: Map<String, String> = emptyMap()
) : Parcelable

@Serializable
@Parcelize
data class DeliveryAddress(
    val label: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val streetHint: String = "",
    val city: String = "Maseru",
    val district: String = "",
    val country: String = "Lesotho"
) : Parcelable

// ── Wallet / Ledger ──────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class Wallet(
    val id: String = "",
    val userId: String = "",
    val availableBalance: MoneyAmount = MoneyAmount.ZERO,
    val pendingBalance: MoneyAmount = MoneyAmount.ZERO,
    val currency: String = "LSL",
    val updatedAt: Long = 0L
) : Parcelable

enum class TransactionStatus { PENDING, PROCESSING, COMPLETED, FAILED, REVERSED, REFUNDED }

enum class TransactionType {
    DEPOSIT, WITHDRAWAL, PURCHASE, SALE, TRANSFER_IN, TRANSFER_OUT,
    PLATFORM_FEE, SUBSCRIPTION, AD_PAYMENT, REFUND, REVERSAL
}

@Serializable
@Parcelize
data class WalletTransaction(
    val transactionId: String = UUID.randomUUID().toString(),
    val idempotencyKey: String = "",
    val userId: String = "",
    val type: TransactionType = TransactionType.PURCHASE,
    val amount: MoneyAmount = MoneyAmount.ZERO,
    val fee: MoneyAmount = MoneyAmount.ZERO,
    val status: TransactionStatus = TransactionStatus.PENDING,
    val reference: String = "",
    val description: String = "",
    val sourceAccount: String = "",
    val destinationAccount: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Long = 0L,
    val completedAt: Long = 0L
) : Parcelable

// ── Payment ───────────────────────────────────────────────────────────────────────

enum class PaymentMethod { MOPAY, SWIFT_WALLET }

@Serializable
@Parcelize
data class PaymentRequest(
    val orderId: String = "",
    val amount: MoneyAmount = MoneyAmount.ZERO,
    val method: PaymentMethod = PaymentMethod.MOPAY,
    val provider: String = "MPESA",
    val phoneNumber: String = "",
    val idempotencyKey: String = UUID.randomUUID().toString()
) : Parcelable

@Serializable
@Parcelize
data class OrderInitiation(
    val orderId: String,
    val paymentUrl: String? = null,
    val mopaySessionId: String? = null
) : Parcelable

// ── Messaging ────────────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class Conversation(
    val id: String = "",
    val participantIds: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastMessageAt: Long = 0L,
    val unreadCount: Int = 0,
    val deliveryRouteId: String = ""
) : Parcelable

@Serializable
@Parcelize
data class Message(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val text: String = "",
    val attachmentUrl: String = "",
    val isRead: Boolean = false,
    val createdAt: Long = 0L,
    val isEncrypted: Boolean = false,
    val encryptedPayload: String? = null,
    val encryptionVersion: String? = null,
    val keyVersion: String? = null
) : Parcelable

// ── Delivery Listing ─────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class DeliveryListing(
    val id: String = "",
    val shopId: String = "",
    val providerId: String = "",
    val providerName: String = "",
    val providerAvatarUrl: String = "",
    val title: String = "",
    val description: String = "",
    val price: MoneyAmount = MoneyAmount.ZERO,
    val estimatedMinutes: Int = 0,
    val coverageArea: String = "",
    val isAvailable: Boolean = true,
    val rating: Float = 0f,
    val completedDeliveries: Int = 0,
    val imageUrl: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) : Parcelable

@Serializable
@Parcelize
data class DeliveryListingSnapshot(
    val listingId: String = "",
    val providerId: String = "",
    val providerName: String = "",
    val title: String = "",
    val priceMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val estimatedMinutes: Int = 0
) : Parcelable

// ── Delivery ─────────────────────────────────────────────────────────────────────

enum class DeliveryStatus {
    REQUESTED, ASSIGNED, PICKUP, IN_TRANSIT, DELIVERED, FAILED, CANCELLED
}

@Serializable
@Parcelize
data class DeliveryRoute(
    val id: String = "",
    val orderId: String = "",
    val driverId: String = "",
    val pickupLocation: GeoPoint = GeoPoint(),
    val dropoffLocation: GeoPoint = GeoPoint(),
    val status: DeliveryStatus = DeliveryStatus.REQUESTED,
    val distanceMeters: Double = 0.0,
    val estimatedMinutes: Int = 0,
    val driverCurrentLocation: GeoPoint? = null,
    val conversationId: String = "",
    val createdAt: Long = 0L
) : Parcelable

@Serializable
@Parcelize
data class GeoPoint(
    val lat: Double = 0.0,
    val lng: Double = 0.0
) : Parcelable {
    fun isValid(): Boolean = lat in -90.0..90.0 && lng in -180.0..180.0 && lat.isFinite() && lng.isFinite()
}

@Serializable
@Parcelize
enum class SwiftEntity : Parcelable {
    SHOP, PRODUCT, SERVICE, FORM, DELIVERY, PIN, SELLER, BUYER, PROVIDER
}

@Serializable
@Parcelize
data class MapMarker(
    val id: String,
    val position: GeoPoint,
    val title: String = "",
    val snippet: String = "",
    val entityType: SwiftEntity? = null
) : Parcelable

@Serializable
@Parcelize
data class MapPolyline(
    val id: String,
    val points: List<GeoPoint>,
    val color: String = "#3D5AFE",
    val width: Float = 5f
) : Parcelable

@Serializable
@Parcelize
data class LocationSnapshot(
    val lat: Double,
    val lng: Double,
    val addressSnapshot: String = "",
    val instructions: String = ""
) : Parcelable {
    fun toGeoPoint(): GeoPoint = GeoPoint(lat, lng)
}

// ── Advertising ──────────────────────────────────────────────────────────────────

enum class CampaignStatus { DRAFT, ACTIVE, PAUSED, COMPLETED, REJECTED }
enum class CampaignContentType { POST, LISTING, REEL }

@Serializable
@Parcelize
data class AdCampaign(
    val campaignId: String = "",
    val ownerId: String = "",
    val contentId: String = "",
    val contentType: CampaignContentType = CampaignContentType.LISTING,
    val budget: MoneyAmount = MoneyAmount.ZERO,
    val durationWeeks: Int = 1,
    val status: CampaignStatus = CampaignStatus.DRAFT,
    val targeting: AdTargeting = AdTargeting(),
    val impressions: Long = 0L,
    val clicks: Long = 0L,
    val conversions: Long = 0L,
    val createdAt: Long = 0L
) : Parcelable

@Serializable
@Parcelize
data class AdTargeting(
    val categories: List<String> = emptyList(),
    val locations: List<String> = emptyList(),
    val ageRange: String = ""
) : Parcelable

// ── Subscription ─────────────────────────────────────────────────────────────────

@Serializable
@Parcelize
data class Subscription(
    val id: String = "",
    val userId: String = "",
    val tier: UserTier = UserTier.BASIC,
    val monthlyFee: MoneyAmount = MoneyAmount.ZERO,
    val isActive: Boolean = false,
    val startedAt: Long = 0L,
    val renewsAt: Long = 0L,
    val cancelledAt: Long = 0L
) : Parcelable

// ── UI State Wrapper ─────────────────────────────────────────────────────────────

sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val throwable: Throwable? = null) : UiState<Nothing>
    data object Empty : UiState<Nothing>
    data object Offline : UiState<Nothing>
}

sealed interface PagingState<out T> {
    data object Idle : PagingState<Nothing>
    data object Loading : PagingState<Nothing>
    data class Success<T>(val items: List<T>, val hasMore: Boolean) : PagingState<T>
    data class Error(val message: String) : PagingState<Nothing>
    data object Empty : PagingState<Nothing>
    data class LoadingMore<T>(val items: List<T>) : PagingState<T>
}
