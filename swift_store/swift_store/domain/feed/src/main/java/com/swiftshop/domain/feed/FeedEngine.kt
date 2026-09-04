package com.swiftshop.domain.feed

import com.swiftshop.core.model.AdCampaign
import android.net.Uri
import com.swiftshop.core.media.MediaUploadProgress
import com.swiftshop.core.media.MediaUploader
import com.swiftshop.core.model.FeedPost
import com.swiftshop.core.model.PostType
import com.swiftshop.core.model.UserTier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.PagingState
import kotlinx.coroutines.flow.Flow

// â”€â”€â”€ Ranking Score Model â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class RankingFactors(
    val relevanceScore: Double = 0.0,   // content matches user interests
    val socialScore: Double = 0.0,      // engagement velocity
    val commerceScore: Double = 0.0,    // purchase signals from similar users
    val freshnessScore: Double = 0.0,   // recency decay
    val personalScore: Double = 0.0,    // explicit preferences
    val qualityScore: Double = 0.0,     // content quality heuristic
    val sponsoredScore: Double = 0.0    // paid boost
) {
    /** Weighted composite â€” weights configurable server-side */
    fun compositeScore(weights: RankingWeights = RankingWeights()): Double =
        (relevanceScore * weights.relevance) +
        (socialScore * weights.social) +
        (commerceScore * weights.commerce) +
        (freshnessScore * weights.freshness) +
        (personalScore * weights.personal) +
        (qualityScore * weights.quality) +
        (sponsoredScore * weights.sponsored)
}

data class RankingWeights(
    val relevance: Double = 0.25,
    val social: Double = 0.20,
    val commerce: Double = 0.15,
    val freshness: Double = 0.15,
    val personal: Double = 0.15,
    val quality: Double = 0.05,
    val sponsored: Double = 0.05
)

// â”€â”€â”€ Feed Item discriminated union â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

sealed class FeedItem {
    data class PostItem(val post: FeedPost, val ranking: RankingFactors) : FeedItem()
    data class ListingItem(val listing: Listing, val ranking: RankingFactors) : FeedItem()
    data class AdItem(val campaign: AdCampaign, val listing: Listing?) : FeedItem()
    data class ShopSuggestion(val shopId: String) : FeedItem()
    data class PeopleYouMayKnow(val userIds: List<String>) : FeedItem()
}

// â”€â”€â”€ Feed insertion policy (configurable, not hard-coded) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class FeedInsertionPolicy(
    val adInsertionInterval: Int = 5,       // every N organic items, insert one ad
    val shopSuggestionInterval: Int = 15,
    val peopleCardInterval: Int = 20
)

// â”€â”€â”€ Repository Interface â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

interface FeedRepository {
    fun getShopFeed(page: Int, pageSize: Int): Flow<PagingState<Listing>>
    fun getPostFeed(page: Int, pageSize: Int): Flow<PagingState<FeedPost>>
    fun getReelFeed(page: Int, pageSize: Int): Flow<PagingState<FeedPost>>
    fun getPersonalizedFeed(page: Int, pageSize: Int): Flow<PagingState<FeedItem>>
    fun getUserPosts(userId: String): Flow<List<FeedPost>>
    fun getUserReels(userId: String): Flow<List<FeedPost>>
    suspend fun getPost(postId: String): Result<FeedPost>
    suspend fun publishPost(post: FeedPost): Result<String>
    suspend fun recordImpression(contentId: String, contentType: String)
    suspend fun recordClick(contentId: String, contentType: String)
    suspend fun likePost(postId: String): Result<Unit>
    suspend fun unlikePost(postId: String): Result<Unit>
    suspend fun toggleBookmark(uid: String, contentId: String, type: String): Result<Unit>
    fun observeBookmarkedIds(uid: String): Flow<Set<String>>
    fun getBookmarkedContent(uid: String): Flow<List<FeedItem>>
}

// ─── Comment Repository ────────────────────────────────────────────────────────

interface CommentRepository {
    fun getRootComments(postId: String): Flow<List<com.swiftshop.core.model.Comment>>
    fun getReplies(postId: String, parentCommentId: String): Flow<List<com.swiftshop.core.model.Comment>>
    suspend fun addComment(comment: com.swiftshop.core.model.Comment): Result<String>
    suspend fun deleteComment(commentId: String, postId: String, parentId: String?): Result<Unit>
}

// ─── Use Cases ─────────────────────────────────────────────────────────────────

class GetShopFeedUseCase(private val repository: FeedRepository) {
    operator fun invoke(page: Int, pageSize: Int = 20) =
        repository.getShopFeed(page, pageSize)
}

class GetPostFeedUseCase(private val repository: FeedRepository) {
    operator fun invoke(page: Int, pageSize: Int = 20) =
        repository.getPostFeed(page, pageSize)
}

class GetPostUseCase(private val repository: FeedRepository) {
    suspend operator fun invoke(postId: String): Result<FeedPost> =
        repository.getPost(postId)
}

class GetUserPostsUseCase(private val repository: FeedRepository) {
    operator fun invoke(userId: String) =
        repository.getUserPosts(userId)
}

class CreatePostUseCase(
    private val repository: FeedRepository,
    private val mediaUploader: MediaUploader,
    private val reelUploadManager: com.swiftshop.core.media.ReelUploadManager
) {
    suspend operator fun invoke(
        authorId: String,
        authorName: String,
        authorAvatarUrl: String,
        authorTier: UserTier,
        shopId: String,
        type: PostType,
        caption: String,
        mediaUris: List<Uri>,
        hashtags: List<String> = emptyList()
    ): Result<String> = coroutineScope {
        runCatching {
            val post = try {
                if (type == PostType.REEL) {
                    val videoUri = mediaUris.firstOrNull() ?: throw IllegalArgumentException("Reel requires a video")
                    
                    // Track progress via manager for background feed visibility
                    var lastProgress: MediaUploadProgress? = null
                    mediaUploader.uploadVideo(authorId, videoUri).collect { progress ->
                        lastProgress = progress
                        reelUploadManager.updateProgress(progress)
                        if (progress is MediaUploadProgress.Complete || progress is MediaUploadProgress.Failed) {
                            return@collect
                        }
                    }
                    
                    if (lastProgress is MediaUploadProgress.Failed)
                        throw IllegalStateException("Video upload failed: ${(lastProgress as MediaUploadProgress.Failed).message}")
                    
                    if (lastProgress !is MediaUploadProgress.Complete)
                        throw IllegalStateException("Upload timed out or was cancelled")

                    val asset = (lastProgress as MediaUploadProgress.Complete).asset
                    
                    FeedPost(
                        authorId = authorId,
                        authorName = authorName,
                        authorAvatarUrl = authorAvatarUrl,
                        authorTier = authorTier,
                        shopId = shopId,
                        type = type,
                        caption = caption,
                        videoUrl = asset.url,
                        thumbnailUrl = asset.thumbnailUrl,
                        hashtags = hashtags,
                        createdAt = System.currentTimeMillis()
                    )
                } else {
                    val mediaUrls = mediaUris.map { uri ->
                        async {
                            val progress = mediaUploader.uploadImage(authorId, uri)
                                .first { 
                                    it is MediaUploadProgress.Complete || 
                                    it is MediaUploadProgress.Failed 
                                }
                            if (progress is MediaUploadProgress.Failed)
                                throw IllegalStateException("Upload failed: ${progress.message}")
                            (progress as MediaUploadProgress.Complete).asset.url
                        }
                    }.awaitAll()

                    FeedPost(
                        authorId = authorId,
                        authorName = authorName,
                        authorAvatarUrl = authorAvatarUrl,
                        authorTier = authorTier,
                        shopId = shopId,
                        type = type,
                        caption = caption,
                        mediaUrls = mediaUrls,
                        hashtags = hashtags,
                        createdAt = System.currentTimeMillis()
                    )
                }
            } catch (e: Exception) {
                // Ensure terminal failure clears progress
                if (type == PostType.REEL) reelUploadManager.clear()
                throw e
            }

            repository.publishPost(post).getOrThrow().also {
                // Success: clear progress state
                if (type == PostType.REEL) reelUploadManager.clear()
            }
        }
    }
}

class GetReelFeedUseCase(private val repository: FeedRepository) {
    operator fun invoke(page: Int, pageSize: Int = 10) =
        repository.getReelFeed(page, pageSize)
}

class GetUserReelsUseCase(private val repository: FeedRepository) {
    operator fun invoke(userId: String) =
        repository.getUserReels(userId)
}

class LikePostUseCase(private val repository: FeedRepository) {
    suspend operator fun invoke(postId: String, isLiked: Boolean): Result<Unit> =
        if (isLiked) repository.likePost(postId) else repository.unlikePost(postId)
}

class ToggleBookmarkUseCase(private val repository: FeedRepository) {
    suspend operator fun invoke(uid: String, contentId: String, type: String): Result<Unit> =
        repository.toggleBookmark(uid, contentId, type)
}

class ObserveBookmarkedIdsUseCase(private val repository: FeedRepository) {
    operator fun invoke(uid: String): Flow<Set<String>> =
        repository.observeBookmarkedIds(uid)
}

class GetBookmarksUseCase(private val repository: FeedRepository) {
    operator fun invoke(uid: String): Flow<List<FeedItem>> =
        repository.getBookmarkedContent(uid)
}

class GetCommentsUseCase(private val repository: CommentRepository) {
    operator fun invoke(postId: String) = repository.getRootComments(postId)
}

class GetRepliesUseCase(private val repository: CommentRepository) {
    operator fun invoke(postId: String, parentId: String) = repository.getReplies(postId, parentId)
}

class AddCommentUseCase(private val repository: CommentRepository) {
    suspend operator fun invoke(comment: com.swiftshop.core.model.Comment) = repository.addComment(comment)
}

class DeleteCommentUseCase(private val repository: CommentRepository) {
    suspend operator fun invoke(commentId: String, postId: String, parentId: String?) = 
        repository.deleteComment(commentId, postId, parentId)
}


