package com.swiftshop.data.firebase

import java.util.Date
import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.core.model.*
import com.swiftshop.domain.feed.FeedItem
import com.swiftshop.domain.feed.FeedRepository
import com.swiftshop.domain.feed.RankingFactors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

fun tsToLong(v: Any?): Long = when (v) {
    is com.google.firebase.Timestamp -> v.toDate().time
    is java.util.Date -> v.time
    is Long -> v
    is Number -> v.toLong()
    else -> 0L
}

@Singleton
class FirebaseFeedRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : FeedRepository {

    override fun getShopFeed(page: Int, pageSize: Int): Flow<PagingState<Listing>> = callbackFlow {
        val subscription = firestore.collection("listings")
            .whereEqualTo("isAvailable", true)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreListing::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(PagingState.Success(items, hasMore = items.size == pageSize))
            }
        awaitClose { subscription.remove() }
    }

    override fun getUserPosts(userId: String): Flow<List<FeedPost>> = callbackFlow {
        val subscription = firestore.collection("posts")
            .whereEqualTo("authorId", userId)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(items)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun publishPost(post: FeedPost): Result<String> = runCatching {
        val docRef = firestore.collection("posts").document()
        val data = post.copy(id = docRef.id).toFirestore()
        docRef.set(data).await()
        docRef.id
    }

    override fun getPostFeed(page: Int, pageSize: Int): Flow<PagingState<FeedPost>> = callbackFlow {
        val subscription = firestore.collection("posts")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(PagingState.Success(items, hasMore = items.size == pageSize))
            }
        awaitClose { subscription.remove() }
    }

    override fun getReelFeed(page: Int, pageSize: Int): Flow<PagingState<FeedPost>> = callbackFlow {
        val subscription = firestore.collection("posts")
            .whereEqualTo("type", "REEL")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(PagingState.Success(items, hasMore = items.size == pageSize))
            }
        awaitClose { subscription.remove() }
    }

    override fun getPersonalizedFeed(page: Int, pageSize: Int): Flow<PagingState<FeedItem>> = callbackFlow {
        // Personalized feed requires complex server-side ranking.
        // Client fallback: Return image posts.
        val subscription = firestore.collection("posts")
            .orderBy("rankingScore", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { 
                    FeedItem.PostItem(it.toDomain(), RankingFactors(relevanceScore = it.rankingScore))
                } ?: emptyList()
                trySend(PagingState.Success(items, hasMore = items.size == pageSize))
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun recordImpression(contentId: String, contentType: String) {
        // Analytics intent
    }

    override suspend fun recordClick(contentId: String, contentType: String) {
        // Analytics intent
    }

    override suspend fun likePost(postId: String): Result<Unit> = runCatching {
        // Atomic increment handled via Cloud Function or FieldValue.increment
        firestore.collection("posts").document(postId)
            .update("likeCount", com.google.firebase.firestore.FieldValue.increment(1))
            .await()
    }

    override suspend fun unlikePost(postId: String): Result<Unit> = runCatching {
        firestore.collection("posts").document(postId)
            .update("likeCount", com.google.firebase.firestore.FieldValue.increment(-1))
            .await()
    }

    override suspend fun bookmarkPost(postId: String): Result<Unit> = runCatching {
        firestore.collection("posts").document(postId)
            .update("bookmarkCount", com.google.firebase.firestore.FieldValue.increment(1))
            .await()
    }

    override suspend fun unbookmarkPost(postId: String): Result<Unit> = runCatching {
        firestore.collection("posts").document(postId)
            .update("bookmarkCount", com.google.firebase.firestore.FieldValue.increment(-1))
            .await()
    }
}

fun FeedPost.toFirestore() = mapOf(
    "id" to id,
    "authorId" to authorId,
    "authorName" to authorName,
    "authorAvatarUrl" to authorAvatarUrl,
    "authorTier" to authorTier.name,
    "shopId" to shopId,
    "type" to type.name,
    "caption" to caption,
    "mediaUrls" to mediaUrls,
    "videoUrl" to videoUrl,
    "thumbnailUrl" to thumbnailUrl,
    "videoDurationMs" to videoDurationMs,
    "likeCount" to likeCount,
    "commentCount" to commentCount,
    "reshareCount" to reshareCount,
    "bookmarkCount" to bookmarkCount,
    "hashtags" to hashtags,
    "mentions" to mentions,
    "taggedListings" to taggedListings,
    "taggedShops" to taggedShops,
    "isSponsored" to isSponsored,
    "rankingScore" to rankingScore,
    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
)

data class FirestoreFeedPost(
    val id: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorAvatarUrl: String = "",
    val authorTier: String = "BASIC",
    val shopId: String = "",
    val type: String = "IMAGE",
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
    val isSponsored: Boolean = false,
    val rankingScore: Double = 0.0,
    val createdAt: Any? = null
) {
    fun toDomain() = FeedPost(id, authorId, authorName, authorAvatarUrl, runCatching { UserTier.valueOf(authorTier) }.getOrDefault(UserTier.BASIC), shopId, runCatching { PostType.valueOf(type) }.getOrDefault(PostType.IMAGE), caption, mediaUrls, videoUrl, thumbnailUrl, videoDurationMs, likeCount, commentCount, reshareCount, bookmarkCount, hashtags, mentions, taggedListings, taggedShops, false, false, isSponsored, rankingScore, tsToLong(createdAt))
}
