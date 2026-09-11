package com.swiftshop.data.firebase

import java.util.Date
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.core.model.*
import com.swiftshop.domain.feed.FeedItem
import com.swiftshop.domain.feed.FeedRepository
import com.swiftshop.domain.feed.RankingFactors
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseFeedRepository @Inject constructor(

    private val firestore: FirebaseFirestore,
    private val auth: com.google.firebase.auth.FirebaseAuth
) : FeedRepository {

    private suspend fun joinSocialState(posts: List<FeedPost>): List<FeedPost> {
        val uid = auth.currentUser?.uid ?: return posts
        if (posts.isEmpty()) return posts

        val postIds = posts.map { it.id }.toSet()
        
        // 1. Fetch likes for these posts by current user
        val likesMap = try {
            postIds.chunked(10).flatMap { chunk ->
                chunk.map { postId ->
                    val exists = firestore.collection("posts").document(postId)
                        .collection("likes").document(uid).get().await().exists()
                    postId to exists
                }
            }.toMap()
        } catch (e: Exception) {
            Timber.e(e, "SECURITY_ERROR: Failed to fetch likes. Check firestore.rules parity.")
            emptyMap()
        }

        // 2. Fetch bookmarks
        val bookmarks = try {
            firestore.collection("users").document(uid).collection("bookmarks")
                .get().await().documents.map { it.id }.toSet()
        } catch (e: Exception) {
            Timber.e(e, "SECURITY_ERROR: Failed to fetch bookmarks. Check firestore.rules parity.")
            emptySet()
        }

        return posts.map { post ->
            post.copy(
                isLikedByMe = likesMap[post.id] ?: false,
                isBookmarkedByMe = post.id in bookmarks
            )
        }
    }

    private suspend fun joinProfiles(posts: List<FeedPost>): List<FeedPost> {
        if (posts.isEmpty()) return posts
        
        val uids = posts.map { it.authorId }.filter { it.isNotBlank() }.toSet()
        if (uids.isEmpty()) return posts

        val profilesMap = fetchProfilesMap(uids)
        
        return posts.map { post ->
            profilesMap[post.authorId]?.let { profile ->
                post.copy(
                    authorName = profile.displayName,
                    authorAvatarUrl = profile.avatarUrl,
                    authorTier = profile.tier
                )
            } ?: post
        }
    }

    private suspend fun joinProfilesToFeedItems(items: List<FeedItem>): List<FeedItem> {
        val posts = items.mapNotNull { (it as? FeedItem.PostItem)?.post }
        if (posts.isEmpty()) return items
        
        val joinedPosts = joinProfiles(posts).associateBy { it.id }
        
        return items.map { item ->
            if (item is FeedItem.PostItem) {
                joinedPosts[item.post.id]?.let { joinedPost ->
                    item.copy(post = joinedPost)
                } ?: item
            } else {
                item
            }
        }
    }

    private suspend fun fetchProfilesMap(uids: Set<String>): Map<String, UserProfile> {
        if (uids.isEmpty()) return emptyMap()
        
        return uids.chunked(30).flatMap { chunk ->
            val snapshots = firestore.collection("profiles")
                .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk.toList())
                .get()
                .await()
            
            snapshots.documents.mapNotNull { doc ->
                doc.toObject(FirestoreUserProfile::class.java)?.toDomain(doc.id)
            }
        }.associateBy { it.uid }
    }

    private fun com.google.firebase.firestore.Query.snapshots(): Flow<com.google.firebase.firestore.QuerySnapshot> = callbackFlow {
        val subscription = addSnapshotListener { snapshot, _ ->
            if (snapshot != null) trySend(snapshot)
        }
        awaitClose { subscription.remove() }
    }

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
            .whereIn("type", listOf("IMAGE", "CAROUSEL"))
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(items)
            }
        awaitClose { subscription.remove() }
    }.map { joinProfiles(it) }

    override fun getUserReels(userId: String): Flow<List<FeedPost>> = callbackFlow {
        val subscription = firestore.collection("posts")
            .whereEqualTo("authorId", userId)
            .whereEqualTo("type", "REEL")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(items)
            }
        awaitClose { subscription.remove() }
    }.map { joinProfiles(it) }

    override suspend fun getPost(postId: String): Result<FeedPost> = runCatching {
        val snapshot = firestore.collection("posts").document(postId).get().await()
        val post = snapshot.toObject(FirestoreFeedPost::class.java)?.toDomain() 
            ?: throw NoSuchElementException("Post not found")
        
        joinProfiles(listOf(post)).first()
    }

    override suspend fun publishPost(post: FeedPost): Result<String> = runCatching {
        val docRef = firestore.collection("posts").document()
        val data = post.copy(id = docRef.id).toFirestore()
        docRef.set(data).await()
        docRef.id
    }

    override suspend fun deleteContent(post: FeedPost): Result<Unit> = runCatching {
        val storage = com.google.firebase.storage.FirebaseStorage.getInstance()
        val urls = post.mediaUrls + post.videoUrl + post.thumbnailUrl
        
        urls.filter { it.isNotBlank() }.forEach { url ->
            runCatching {
                storage.getReferenceFromUrl(url).delete().await()
            }
        }
        
        firestore.collection("posts").document(post.id).delete().await()
    }

    override fun getPostFeed(page: Int, pageSize: Int): Flow<PagingState<FeedPost>> = callbackFlow<PagingState<FeedPost>> {
        val subscription = firestore.collection("posts")
            .whereIn("type", listOf("IMAGE", "CAROUSEL"))
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(PagingState.Success(items, hasMore = items.size == pageSize))
            }
        awaitClose { subscription.remove() }
    }.map { state ->
        when (state) {
            is PagingState.Success<*> -> {
                val s = state as PagingState.Success<FeedPost>
                s.copy(items = joinSocialState(joinProfiles(s.items)))
            }
            is PagingState.LoadingMore<*> -> {
                val s = state as PagingState.LoadingMore<FeedPost>
                s.copy(items = joinSocialState(joinProfiles(s.items)))
            }
            else -> state
        }
    }

    override fun getReelFeed(page: Int, pageSize: Int): Flow<PagingState<FeedPost>> = callbackFlow<PagingState<FeedPost>> {
        val subscription = firestore.collection("posts")
            .whereEqualTo("type", "REEL")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            .addSnapshotListener { snapshot, _ ->
                val items = snapshot?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(PagingState.Success(items, hasMore = items.size == pageSize))
            }
        awaitClose { subscription.remove() }
    }.map { state ->
        when (state) {
            is PagingState.Success<*> -> {
                val s = state as PagingState.Success<FeedPost>
                s.copy(items = joinSocialState(joinProfiles(s.items)))
            }
            is PagingState.LoadingMore<*> -> {
                val s = state as PagingState.LoadingMore<FeedPost>
                s.copy(items = joinSocialState(joinProfiles(s.items)))
            }
            else -> state
        }
    }

    override suspend fun searchPosts(query: String): Result<List<FeedPost>> = runCatching {
        val snapshot = firestore.collection("posts")
            .whereGreaterThanOrEqualTo("caption", query)
            .whereLessThanOrEqualTo("caption", query + "\uf8ff")
            .get().await()
        
        val posts = snapshot.toObjects(FirestoreFeedPost::class.java).map { it.toDomain() }
        joinProfiles(posts)
    }

    override fun getPersonalizedFeed(page: Int, pageSize: Int): Flow<PagingState<FeedItem>> = callbackFlow<PagingState<FeedItem>> {
        // ── Personalized Discovery Interleaving ──
        // Fetches posts and listings and interleaves them for a mixed ecosystem discovery.
        
        val postsQuery = firestore.collection("posts")
            .whereIn("type", listOf("IMAGE", "CAROUSEL"))
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())

        val listingsQuery = firestore.collection("listings")
            .whereEqualTo("isAvailable", true)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit((pageSize / 3).toLong().coerceAtLeast(5))

        val subscription = postsQuery.addSnapshotListener { postsSnap, _ ->
            val posts = postsSnap?.toObjects(FirestoreFeedPost::class.java)?.map { it.toDomain() } ?: emptyList()
            
            // Note: Mixing snapshot listeners is tricky for pagination, 
            // but for a single-page pass this establishes the pattern.
            firestore.collection("listings")
                .whereEqualTo("isAvailable", true)
                .limit(5)
                .get()
                .addOnSuccessListener { listingsSnap ->
                    val listings = listingsSnap.toObjects(FirestoreListing::class.java).map { it.toDomain() }
                    
                    val interleaved = mutableListOf<FeedItem>()
                    var listingIndex = 0
                    
                    posts.forEachIndexed { index, post ->
                        interleaved.add(FeedItem.PostItem(post, RankingFactors(relevanceScore = post.rankingScore)))
                        if ((index + 1) % 3 == 0 && listingIndex < listings.size) {
                            interleaved.add(FeedItem.ListingItem(listings[listingIndex], RankingFactors()))
                            listingIndex++
                        }
                    }
                    
                    trySend(PagingState.Success(interleaved, hasMore = posts.size == pageSize))
                }
        }
        awaitClose { subscription.remove() }
    }.map { state ->
        when (state) {
            is PagingState.Success<*> -> {
                val s = state as PagingState.Success<FeedItem>
                // Join social state for both posts and listings if applicable
                // (Currently joinProfilesToFeedItems only handles posts)
                s.copy(items = joinProfilesToFeedItems(s.items))
            }
            else -> state
        }
    }

    override suspend fun recordImpression(contentId: String, contentType: String) {
        // Analytics intent
    }

    override suspend fun recordClick(contentId: String, contentType: String) {
        // Analytics intent
    }

    override suspend fun likePost(postId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        firestore.collection("posts").document(postId)
            .collection("likes").document(uid)
            .set(mapOf("createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
            .await()
    }

    override suspend fun unlikePost(postId: String): Result<Unit> = runCatching {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        firestore.collection("posts").document(postId)
            .collection("likes").document(uid)
            .delete()
            .await()
    }

    override suspend fun toggleBookmark(uid: String, contentId: String, type: String): Result<Unit> = runCatching {
        val bookmarkRef = firestore.collection("users").document(uid).collection("bookmarks").document(contentId)
        val exists = bookmarkRef.get().await().exists()
        
        firestore.runBatch { batch ->
            if (exists) {
                batch.delete(bookmarkRef)
                // Best effort counter update
                val collection = if (type == "LISTING") "listings" else "posts"
                batch.update(firestore.collection(collection).document(contentId), "bookmarkCount", com.google.firebase.firestore.FieldValue.increment(-1))
            } else {
                val data = mapOf(
                    "contentId" to contentId,
                    "contentType" to type,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                batch.set(bookmarkRef, data)
                val collection = if (type == "LISTING") "listings" else "posts"
                batch.update(firestore.collection(collection).document(contentId), "bookmarkCount", com.google.firebase.firestore.FieldValue.increment(1))
            }
        }.await()
    }

    override fun observeBookmarkedIds(uid: String): Flow<Set<String>> = callbackFlow {
        val subscription = firestore.collection("users").document(uid).collection("bookmarks")
            .addSnapshotListener { snapshot, _ ->
                trySend(snapshot?.documents?.map { it.id }?.toSet() ?: emptySet())
            }
        awaitClose { subscription.remove() }
    }

    override fun getBookmarkedContent(uid: String): Flow<List<FeedItem>> = firestore.collection("users").document(uid).collection("bookmarks")
        .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
        .snapshots()
        .map { snapshot ->
            val pointers = snapshot.documents.mapNotNull { 
                it.getString("contentType")?.let { type -> it.id to type } 
            }
            
            if (pointers.isEmpty()) return@map emptyList<FeedItem>()

            val postsIds = pointers.filter { it.second != "LISTING" }.map { it.first }
            val listingIds = pointers.filter { it.second == "LISTING" }.map { it.first }

            val resolvedPosts = if (postsIds.isNotEmpty()) {
                postsIds.chunked(10).flatMap { chunk ->
                    val snapshots = firestore.collection("posts")
                        .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                        .get().await()
                    snapshots.toObjects(FirestoreFeedPost::class.java).map { it.toDomain() }
                }.let { joinProfiles(it) }.associateBy { it.id }
            } else emptyMap()

            val resolvedListings = if (listingIds.isNotEmpty()) {
                listingIds.chunked(10).flatMap { chunk ->
                    val snapshots = firestore.collection("listings")
                        .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                        .get().await()
                    snapshots.toObjects(FirestoreListing::class.java).map { it.toDomain() }
                }.associateBy { it.id }
            } else emptyMap()

            pointers.mapNotNull { (id, type) ->
                if (type == "LISTING") {
                    resolvedListings[id]?.let { FeedItem.ListingItem(it, RankingFactors()) }
                } else {
                    resolvedPosts[id]?.let { FeedItem.PostItem(it, RankingFactors()) }
                }
            }
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
