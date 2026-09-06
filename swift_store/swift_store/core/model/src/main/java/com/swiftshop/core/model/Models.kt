package com.swiftshop.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.util.UUID

// â”€â”€â”€ Money â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Tier â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

enum class UserTier {
    BASIC,   // Free: 1 shop, unlimited listings
    PREMIUM, // M99/mo: 3 shops, unlimited listings
    ELITE    // M499/mo: unlimited shops, max exposure
}

// â”€â”€â”€ Account Status â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

enum class UserAccountStatus {
    ACTIVE,
    LIMITED,
    UNDER_REVIEW,
    FROZEN,
    SUSPENDED,
    BANNED,
    DELETED
}

// â”€â”€â”€ User / Auth â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Achievement â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Serializable
@Parcelize
data class Achievement(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val iconUrl: String = "",
    val earnedAt: Long = 0L
) : Parcelable

// â”€â”€â”€ Shop â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Listing / Product â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
    BUY, MAKE_PAYMENT, SET_APPOINTMENT, PLACE_ORDER, REGISTER, DELIVER, TAKE_ME_THERE
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
) : Parcelable

@Serializable
@Parcelize
data class CustomField(
    val id: String = "",
    val label: String = "",
    val type: String = "text",
    val options: List<String> = emptyList(),
    val isRequired: Boolean = false
) : Parcelable

// â”€â”€â”€ Feed Post â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Comment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Order / Commerce â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

@Serializable
@Parcelize
data class Order(
    val id: String = "",
    val buyerId: String = "",
    val sellerId: String = "",
    val shopId: String = "",
    val items: List<OrderItem> = emptyList(),
    val subtotal: MoneyAmount = MoneyAmount.ZERO,
    val deliveryFee: MoneyAmount = MoneyAmount.ZERO,
    val platformFee: MoneyAmount = MoneyAmount.ZERO,
    val total: MoneyAmount = MoneyAmount.ZERO,
    val status: OrderStatus = OrderStatus.PENDING,
    val deliveryAddress: DeliveryAddress = DeliveryAddress(),
    // The delivery listing selected by the buyer at checkout.
    // This ID is validated server-side; the fee is never trusted from the client.
    val selectedDeliveryListingId: String = "",
    // Immutable snapshot of the delivery offering at time of order.
    // Protects historical orders from future changes to the delivery listing.
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

// â”€â”€â”€ Wallet / Ledger â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Payment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Messaging â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
    // E2E Readiness - Implementation INACTIVE until cryptographic audit
    val isEncrypted: Boolean = false,
    val encryptedPayload: String? = null,
    val encryptionVersion: String? = null,
    val keyVersion: String? = null
) : Parcelable

// â”€â”€â”€ Delivery Listing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
//
// A DeliveryListing is a commercial offering created by a delivery provider.
// It appears in the marketplace, in checkout, and in search/discovery just like
// any other listing.  The price it carries is the canonical delivery fee for an
// order â€” never a hard-coded constant and never derived from route distance.
//
// Architecture law: the selected DeliveryListing is the ONLY authoritative source
// of the delivery fee.  The backend validates the fee against this record.

@Serializable
@Parcelize
data class DeliveryListing(
    val id: String = "",
    val shopId: String = "",
    val providerId: String = "",        // uid of the delivery provider / business owner
    val providerName: String = "",
    val providerAvatarUrl: String = "",
    val title: String = "",             // e.g. "Standard Maseru Delivery"
    val description: String = "",
    val price: MoneyAmount = MoneyAmount.ZERO,
    val estimatedMinutes: Int = 0,      // indicative only â€” not a contractual guarantee
    val coverageArea: String = "",      // human-readable e.g. "Maseru CBD & surrounds"
    val isAvailable: Boolean = true,
    val rating: Float = 0f,
    val completedDeliveries: Int = 0,
    val imageUrl: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) : Parcelable

// Immutable snapshot stored on an order at checkout time.
// If the delivery listing is later edited or deleted, the order record is unaffected.
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

// â”€â”€â”€ Delivery â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
    val color: String = "#3D5AFE", // Swift Brand Blue default
    val width: Float = 5f
) : Parcelable

/**
 * An immutable snapshot of a physical location associated with a transaction.
 * Captures the exact coordinates and address context at order creation.
 */
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

// â”€â”€â”€ Advertising â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ Subscription â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

// â”€â”€â”€ UI State Wrapper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

