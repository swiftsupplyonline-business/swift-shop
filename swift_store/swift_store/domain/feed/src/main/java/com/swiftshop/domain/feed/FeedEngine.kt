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
    suspend fun publishPost(post: FeedPost): Result<String>
    suspend fun recordImpression(contentId: String, contentType: String)
    suspend fun recordClick(contentId: String, contentType: String)
    suspend fun likePost(postId: String): Result<Unit>
    suspend fun unlikePost(postId: String): Result<Unit>
    suspend fun bookmarkPost(postId: String): Result<Unit>
    suspend fun unbookmarkPost(postId: String): Result<Unit>
}

// â”€â”€â”€ Use Cases â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class GetShopFeedUseCase(private val repository: FeedRepository) {
    operator fun invoke(page: Int, pageSize: Int = 20) =
        repository.getShopFeed(page, pageSize)
}

class GetPostFeedUseCase(private val repository: FeedRepository) {
    operator fun invoke(page: Int, pageSize: Int = 20) =
        repository.getPostFeed(page, pageSize)
}

class GetUserPostsUseCase(private val repository: FeedRepository) {
    operator fun invoke(userId: String) =
        repository.getUserPosts(userId)
}

class CreatePostUseCase(
    private val repository: FeedRepository,
    private val mediaUploader: MediaUploader
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
            val post = if (type == PostType.REEL) {
                val videoUri = mediaUris.firstOrNull() ?: throw IllegalArgumentException("Reel requires a video")
                val progress = mediaUploader.uploadVideo(authorId, videoUri)
                    .first { 
                        it is MediaUploadProgress.Complete || 
                        it is MediaUploadProgress.Failed 
                    }
                if (progress is MediaUploadProgress.Failed)
                    throw IllegalStateException("Video upload failed: ${progress.message}")
                
                val asset = (progress as MediaUploadProgress.Complete).asset
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
            repository.publishPost(post).getOrThrow()
        }
    }
}

class GetReelFeedUseCase(private val repository: FeedRepository) {
    operator fun invoke(page: Int, pageSize: Int = 10) =
        repository.getReelFeed(page, pageSize)
}

class LikePostUseCase(private val repository: FeedRepository) {
    suspend operator fun invoke(postId: String, isLiked: Boolean): Result<Unit> =
        if (isLiked) repository.likePost(postId) else repository.unlikePost(postId)
}


