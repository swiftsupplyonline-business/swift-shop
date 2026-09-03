package com.swiftshop.core.database

import androidx.room.*
import androidx.room.Database
import kotlinx.coroutines.flow.Flow

// ─── Entities ─────────────────────────────────────────────────────────────────

@Entity(tableName = "cached_listings")
data class ListingEntity(
    @PrimaryKey val id: String,
    val shopId: String,
    val sellerId: String,
    val title: String,
    val description: String,
    val priceMinorUnits: Long,
    val priceCurrency: String,
    val imageUrlsJson: String,           // JSON array
    val category: String,
    val listingType: String,
    val isAvailable: Boolean,
    val isSponsored: Boolean,
    val commitmentCount: Int,
    val deliveryEstimateDays: Int,
    val createdAt: Long,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_posts")
data class PostEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorName: String,
    val authorAvatarUrl: String,
    val authorTier: String,
    val type: String,
    val caption: String,
    val mediaUrlsJson: String,
    val videoUrl: String,
    val thumbnailUrl: String,
    val likeCount: Int,
    val commentCount: Int,
    val isLikedByMe: Boolean,
    val isBookmarkedByMe: Boolean,
    val isSponsored: Boolean,
    val createdAt: Long,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_shops")
data class ShopEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val name: String,
    val description: String,
    val logoUrl: String,
    val coverUrl: String,
    val category: String,
    val locationLat: Double,
    val locationLng: Double,
    val locationAddress: String,
    val isVerified: Boolean,
    val rating: Float,
    val reviewCount: Int,
    val listingCount: Int,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val buyerId: String,
    val sellerId: String,
    val shopId: String,
    val itemsJson: String,
    val subtotalMinorUnits: Long,
    val deliveryFeeMinorUnits: Long,
    val platformFeeMinorUnits: Long,
    val totalMinorUnits: Long,
    val currency: String,
    val status: String,
    val deliveryAddressJson: String,
    val paymentId: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "cached_messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val text: String,
    val attachmentUrl: String,
    val isRead: Boolean,
    val createdAt: Long,
    val syncStatus: String = "SYNCED"    // LOCAL | SYNCING | SYNCED | FAILED
)

@Entity(tableName = "cached_wallet_transactions")
data class WalletTransactionEntity(
    @PrimaryKey val transactionId: String,
    val userId: String,
    val type: String,
    val amountMinorUnits: Long,
    val feeMinorUnits: Long,
    val currency: String,
    val status: String,
    val reference: String,
    val description: String,
    val createdAt: Long,
    val cachedAt: Long = System.currentTimeMillis()
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface ListingDao {
    @Query("SELECT * FROM cached_listings ORDER BY cachedAt DESC LIMIT :limit OFFSET :offset")
    fun getListings(limit: Int = 20, offset: Int = 0): Flow<List<ListingEntity>>

    @Query("SELECT * FROM cached_listings WHERE id = :id")
    suspend fun getListingById(id: String): ListingEntity?

    @Query("SELECT * FROM cached_listings WHERE shopId = :shopId ORDER BY createdAt DESC")
    fun getShopListings(shopId: String): Flow<List<ListingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListings(listings: List<ListingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertListing(listing: ListingEntity)

    @Query("DELETE FROM cached_listings WHERE cachedAt < :beforeMs")
    suspend fun evictStaleListings(beforeMs: Long)

    @Query("DELETE FROM cached_listings")
    suspend fun clearAll()
}

@Dao
interface ShopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShops(shops: List<ShopEntity>)

    @Query("DELETE FROM cached_shops")
    suspend fun clearAll()
}

@Dao
interface PostDao {
    @Query("SELECT * FROM cached_posts ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getPosts(limit: Int = 20, offset: Int = 0): Flow<List<PostEntity>>

    @Query("SELECT * FROM cached_posts WHERE type = 'REEL' ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    fun getReels(limit: Int = 10, offset: Int = 0): Flow<List<PostEntity>>

    @Query("SELECT * FROM cached_posts WHERE id = :id")
    suspend fun getPostById(id: String): PostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPosts(posts: List<PostEntity>)

    @Query("UPDATE cached_posts SET isLikedByMe = :liked, likeCount = likeCount + :delta WHERE id = :postId")
    suspend fun updateLike(postId: String, liked: Boolean, delta: Int)

    @Query("DELETE FROM cached_posts WHERE cachedAt < :beforeMs")
    suspend fun evictStalePosts(beforeMs: Long)

    @Query("DELETE FROM cached_posts")
    suspend fun clearAll()
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM cached_orders WHERE buyerId = :userId ORDER BY createdAt DESC")
    fun getUserOrders(userId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM cached_orders WHERE id = :id")
    suspend fun getOrderById(id: String): OrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrders(orders: List<OrderEntity>)

    @Query("UPDATE cached_orders SET status = :status, updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun updateStatus(orderId: String, status: String, updatedAt: Long)

    @Query("DELETE FROM cached_orders")
    suspend fun clearAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    fun getMessages(conversationId: String, limit: Int = 50): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE cached_messages SET isRead = 1 WHERE conversationId = :conversationId")
    suspend fun markAllRead(conversationId: String)

    @Query("DELETE FROM cached_messages WHERE conversationId = :conversationId AND createdAt < :beforeMs")
    suspend fun evictOldMessages(conversationId: String, beforeMs: Long)

    @Query("DELETE FROM cached_messages")
    suspend fun clearAll()
}

@Dao
interface WalletTransactionDao {
    @Query("SELECT * FROM cached_wallet_transactions WHERE userId = :userId ORDER BY createdAt DESC LIMIT :limit")
    fun getTransactions(userId: String, limit: Int = 50): Flow<List<WalletTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<WalletTransactionEntity>)

    @Query("DELETE FROM cached_wallet_transactions WHERE userId = :userId AND cachedAt < :beforeMs")
    suspend fun evictStale(userId: String, beforeMs: Long)

    @Query("DELETE FROM cached_wallet_transactions")
    suspend fun clearAll()
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [
        ListingEntity::class,
        PostEntity::class,
        ShopEntity::class,
        OrderEntity::class,
        MessageEntity::class,
        WalletTransactionEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SwiftShopDatabase : RoomDatabase() {
    abstract fun listingDao(): ListingDao
    abstract fun shopDao(): ShopDao
    abstract fun postDao(): PostDao
    abstract fun orderDao(): OrderDao
    abstract fun messageDao(): MessageDao
    abstract fun walletTransactionDao(): WalletTransactionDao
}
