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
import kotlinx.coroutines.launch
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
    private val firestore: FirebaseFirestore,
    private val functions: com.google.firebase.functions.FirebaseFunctions,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : FeedRepository {

    override fun getShopFeed(page: Int, pageSize: Int): Flow<PagingState<Listing>> = callbackFlow {
        val currentUserId = auth.currentUser?.uid
        val subscription = firestore.collection("listings")
            .whereEqualTo("isAvailable", true)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreListing::class.java) ?: emptyList()
                this@callbackFlow.launch {
                    val domainItems = items.map { item ->
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
                    trySend(PagingState.Success(domainItems, hasMore = items.size == pageSize))
                }
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
        val data = post.toFirestore()
        val result = functions.getHttpsCallable("publishPost").call(data).await()
        result.data as String
    }

    override fun getPostFeed(page: Int, pageSize: Int): Flow<PagingState<FeedPost>> = callbackFlow {
        val currentUserId = auth.currentUser?.uid
        val subscription = firestore.collection("posts")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java) ?: emptyList()
                
                this@callbackFlow.launch {
                    val domainItems = items.map { item ->
                        val isLiked = if (currentUserId != null) {
                            runCatching {
                                firestore.collection("posts").document(item.id)
                                    .collection("likes").document(currentUserId).get().await().exists()
                            }.getOrDefault(false)
                        } else false

                        val isBookmarked = if (currentUserId != null) {
                            runCatching {
                                firestore.collection("users").document(currentUserId)
                                    .collection("bookmarks").document("post_${item.id}").get().await().exists()
                            }.getOrDefault(false)
                        } else false

                        item.toDomain(isLiked = isLiked, isBookmarked = isBookmarked)
                    }
                    trySend(PagingState.Success(domainItems, hasMore = items.size == pageSize))
                }
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
        val data = mapOf("postId" to postId)
        functions.getHttpsCallable("likePost").call(data).await()
        Unit
    }

    override suspend fun unlikePost(postId: String): Result<Unit> = runCatching {
        val data = mapOf("postId" to postId)
        functions.getHttpsCallable("unlikePost").call(data).await()
        Unit
    }

    override suspend fun bookmarkPost(postId: String): Result<Unit> = runCatching {
        val data = mapOf("postId" to postId)
        functions.getHttpsCallable("bookmarkPost").call(data).await()
        Unit
    }

    override suspend fun unbookmarkPost(postId: String): Result<Unit> = runCatching {
        val data = mapOf("postId" to postId)
        functions.getHttpsCallable("unbookmarkPost").call(data).await()
        Unit
    }

    override fun observeListingComments(listingId: String): Flow<List<Comment>> = callbackFlow {
        val subscription = firestore.collection("comments")
            .whereEqualTo("listingId", listingId)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.toObjects(Comment::class.java) ?: emptyList())
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun postListingComment(comment: Comment): Result<String> = runCatching {
        val doc = firestore.collection("comments").document()
        val data = mapOf(
            "id" to doc.id,
            "listingId" to comment.listingId,
            "text" to comment.text,
            "authorName" to comment.authorName,
            "authorAvatarUrl" to comment.authorAvatarUrl,
            "parentCommentId" to comment.parentCommentId
        )
        functions.getHttpsCallable("createListingComment").call(data).await()
        doc.id
    }

    override suspend fun deleteListingComment(commentId: String): Result<Unit> = runCatching {
        val data = mapOf("commentId" to commentId)
        functions.getHttpsCallable("deleteListingComment").call(data).await()
        Unit
    }

    override suspend fun getPost(postId: String): Result<FeedPost?> = runCatching {
        val doc = firestore.collection("posts").document(postId).get().await()
        val item = doc.toObject(FirestoreFeedPost::class.java) ?: return@runCatching null
        
        val currentUserId = auth.currentUser?.uid
        val isLiked = if (currentUserId != null) {
            runCatching {
                firestore.collection("posts").document(postId)
                    .collection("likes").document(currentUserId).get().await().exists()
            }.getOrDefault(false)
        } else false

        val isBookmarked = if (currentUserId != null) {
            runCatching {
                firestore.collection("users").document(currentUserId)
                    .collection("bookmarks").document("post_${postId}").get().await().exists()
            }.getOrDefault(false)
        } else false

        item.toDomain(isLiked = isLiked, isBookmarked = isBookmarked)
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

    override suspend fun isListingBookmarkedByUser(listingId: String, userId: String): Result<Boolean> = runCatching {
        val doc = firestore.collection("users").document(userId)
            .collection("bookmarks").document(listingId).get().await()
        doc.exists()
    }

    override suspend fun getBookmarkedListingIds(userId: String): Result<List<String>> = runCatching {
        val snapshot = firestore.collection("users").document(userId)
            .collection("bookmarks")
            .whereEqualTo("type", "LISTING")
            .get().await()
        snapshot.documents.map { it.get("listingId") as? String ?: it.id }
    }

    override fun observePostComments(postId: String): Flow<List<Comment>> = callbackFlow {
        val subscription = firestore.collection("comments")
            .whereEqualTo("postId", postId)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.toObjects(Comment::class.java) ?: emptyList())
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun postPostComment(comment: Comment): Result<String> = runCatching {
        val doc = firestore.collection("comments").document()
        val data = mapOf(
            "id" to doc.id,
            "postId" to comment.postId,
            "text" to comment.text,
            "authorName" to comment.authorName,
            "authorAvatarUrl" to comment.authorAvatarUrl,
            "parentCommentId" to comment.parentCommentId
        )
        functions.getHttpsCallable("createPostComment").call(data).await()
        doc.id
    }

    override suspend fun deletePostComment(commentId: String): Result<Unit> = runCatching {
        val data = mapOf("commentId" to commentId)
        functions.getHttpsCallable("deletePostComment").call(data).await()
        Unit
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
    fun toDomain(isLiked: Boolean = false, isBookmarked: Boolean = false) = FeedPost(id, authorId, authorName, authorAvatarUrl, runCatching { UserTier.valueOf(authorTier) }.getOrDefault(UserTier.BASIC), shopId, runCatching { PostType.valueOf(type) }.getOrDefault(PostType.IMAGE), caption, mediaUrls, videoUrl, thumbnailUrl, videoDurationMs, likeCount, commentCount, reshareCount, bookmarkCount, hashtags, mentions, taggedListings, taggedShops, isLiked, isBookmarked, isSponsored, rankingScore, tsToLong(createdAt))
}
