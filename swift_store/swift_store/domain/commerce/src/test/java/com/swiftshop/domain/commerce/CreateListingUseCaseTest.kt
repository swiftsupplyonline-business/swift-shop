package com.swiftshop.domain.commerce

import android.net.Uri
import com.swiftshop.core.media.MediaUploadProgress
import com.swiftshop.core.media.MediaUploader
import com.swiftshop.core.model.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class CreateListingUseCaseTest {

    private lateinit var repository: CommerceRepository
    private lateinit var mediaUploader: MediaUploader
    private lateinit var useCase: CreateListingUseCase

    @Before
    fun setup() {
        repository = mock()
        mediaUploader = mock()
        useCase = CreateListingUseCase(repository, mediaUploader)
    }

    @Test
    fun `invoke uploads images and creates listing with URLs`() = runTest {
        val sellerId = "user123"
        val shopId = "shop456"
        val mockUri = mock<Uri>()
        val mockAsset = MediaAsset(url = "https://example.com/image.jpg")
        
        whenever(mediaUploader.uploadImage(eq(sellerId), any(), anyOrNull()))
            .thenReturn(flowOf(MediaUploadProgress.Complete(mockAsset)))
        
        whenever(repository.createListing(any())).thenReturn(Result.success("listing789"))

        val result = useCase(
            title = "Test Item",
            description = "Desc",
            category = "Cat",
            price = MoneyAmount("LSL", 1000),
            stockQuantity = 5,
            imageUris = listOf(mockUri),
            sellerId = sellerId,
            shopId = shopId,
            durationMinutes = 60,
            fulfillmentOptions = listOf(FulfillmentType.PICKUP)
        )

        assertTrue(result.isSuccess)
        assertEquals("listing789", result.getOrNull())

        verify(mediaUploader).uploadImage(eq(sellerId), eq(mockUri), anyOrNull())
        
        argumentCaptor<Listing>().apply {
            verify(repository).createListing(capture())
            val listing = firstValue
            assertEquals("Test Item", listing.title)
            assertEquals(listOf("https://example.com/image.jpg"), listing.imageUrls)
            assertEquals(60, listing.durationMinutes)
            assertEquals(listOf(FulfillmentType.PICKUP), listing.fulfillmentOptions)
        }
    }
}
