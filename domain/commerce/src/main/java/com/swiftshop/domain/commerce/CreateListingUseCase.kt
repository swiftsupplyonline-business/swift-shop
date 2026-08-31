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

class CreateListingUseCase(
    private val repository: CommerceRepository,
    private val mediaUploader: MediaUploader
) {
    suspend operator fun invoke(
        title: String,
        description: String,
        category: String,
        price: MoneyAmount,
        stockQuantity: Int,
        imageUris: List<Uri>,
        sellerId: String,
        shopId: String,
        listingType: ListingType = ListingType.BUY,
        customFields: List<CustomField> = emptyList(),
        deliveryEstimateDays: Int = 0
    ): Result<String> = runCatching {
        Timber.d("DEBUG_CREATE: Inside UseCase - title: $title, type: $listingType, sellerId: $sellerId, shopId: $shopId, imageCount: ${imageUris.size}")
        if (title.isBlank()) throw IllegalArgumentException("Title is required")
        if (price.minorUnits <= 0) throw IllegalArgumentException("Price must be positive")

        val imageUrls = coroutineScope {
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
        Timber.d("DEBUG_CREATE: UseCase uploaded URLs: ${imageUrls.size}")

        val listing = Listing(
            id = "",
            shopId = shopId,
            sellerId = sellerId,
            title = title,
            description = description,
            price = price,
            category = category,
            imageUrls = imageUrls,
            stockQuantity = stockQuantity,
            listingType = listingType,
            customFields = customFields,
            deliveryEstimateDays = deliveryEstimateDays,
            isAvailable = stockQuantity > 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        repository.createListing(listing).getOrThrow()
    }
}
