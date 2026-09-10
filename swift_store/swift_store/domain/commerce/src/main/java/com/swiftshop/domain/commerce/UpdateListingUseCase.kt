package com.swiftshop.domain.commerce

import android.net.Uri
import com.swiftshop.core.media.MediaUploadProgress
import com.swiftshop.core.media.MediaUploader
import com.swiftshop.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import timber.log.Timber

class UpdateListingUseCase(
    private val repository: CommerceRepository,
    private val mediaUploader: MediaUploader
) {
    suspend operator fun invoke(
        listingId: String,
        title: String,
        description: String,
        category: String,
        price: MoneyAmount,
        totalQuantity: Int,
        imageUris: List<Uri>, // New images to upload
        existingImageUrls: List<String>, // Images already in Cloud Storage
        sellerId: String,
        listingType: ListingType,
        customFields: List<CustomField> = emptyList(),
        deliveryEstimateDays: Int = 0,
        durationMinutes: Int = 0,
        fulfillmentOptions: List<FulfillmentType> = emptyList(),
        isAvailable: Boolean = true
    ): Result<Unit> = runCatching {
        Timber.d("DEBUG_UPDATE: Updating listing $listingId - title: $title")
        
        if (title.isBlank()) throw IllegalArgumentException("Title is required")
        if (price.minorUnits <= 0) throw IllegalArgumentException("Price must be positive")

        // 1. Upload new images if any
        val newUploadedUrls = coroutineScope {
            imageUris.map { uri ->
                async {
                    val progress = mediaUploader.uploadImage(sellerId, uri)
                        .first { it is MediaUploadProgress.Complete || it is MediaUploadProgress.Failed }
                    if (progress is MediaUploadProgress.Failed)
                        throw IllegalStateException("Image upload failed: ${progress.message}")
                    (progress as MediaUploadProgress.Complete).asset.url
                }
            }.awaitAll()
        }

        val allImageUrls = existingImageUrls + newUploadedUrls

        // 2. Prepare listing object for repository call
        // Note: The repository implementation (FirebaseCommerceRepository) 
        // expects a full Listing object but the backend updateListing 
        // only uses certain fields from toFirestore().
        val updatedListing = Listing(
            id = listingId,
            shopId = "", // Not allowed to change, ignored by backend
            sellerId = sellerId,
            title = title,
            description = description,
            price = price,
            category = category,
            imageUrls = allImageUrls,
            totalQuantity = totalQuantity,
            listingType = listingType,
            customFields = customFields,
            deliveryEstimateDays = deliveryEstimateDays,
            durationMinutes = durationMinutes,
            fulfillmentOptions = fulfillmentOptions,
            isAvailable = isAvailable,
            updatedAt = System.currentTimeMillis()
        )

        repository.updateListing(updatedListing).getOrThrow()
    }
}
