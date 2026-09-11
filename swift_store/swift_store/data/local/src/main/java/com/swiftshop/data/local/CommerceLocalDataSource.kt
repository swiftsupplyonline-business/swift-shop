package com.swiftshop.data.local

import com.swiftshop.core.database.ListingDao
import com.swiftshop.core.database.ListingEntity
import com.swiftshop.core.database.ShopDao
import com.swiftshop.core.database.ShopEntity
import com.swiftshop.core.model.GeoPoint
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.ListingType
import com.swiftshop.core.model.MoneyAmount
import com.swiftshop.core.model.Shop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline cache for Commerce data, backed by the Room DAOs that already
 * existed in `core:database` but were never wired to anything (the "Local
 * Cache (Room) Completion" item the roadmap deferred to Phase C). This is
 * the client-side half of `data:repositories`' offline-first decorator.
 *
 * Scoped to Commerce (Shops/Listings) only, as a template — repeating this
 * shape for Orders/Messages/Wallet is the same pattern against the DAOs
 * that already exist in SwiftShopDatabase.kt.
 */
@Singleton
class CommerceLocalDataSource @Inject constructor(
    private val listingDao: ListingDao,
    private val shopDao: ShopDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ─── Listings ─────────────────────────────────────────────────────────

    fun observeShopListings(shopId: String): Flow<List<Listing>> =
        listingDao.getShopListings(shopId).map { it.map(::toDomain) }

    suspend fun cacheListings(listings: List<Listing>) {
        listingDao.insertListings(listings.map(::toEntity))
    }

    suspend fun cacheListing(listing: Listing) {
        listingDao.insertListing(toEntity(listing))
    }

    suspend fun getCachedListing(id: String): Listing? = listingDao.getListingById(id)?.let(::toDomain)

    // ─── Shops ────────────────────────────────────────────────────────────

    suspend fun cacheShops(shops: List<Shop>) {
        shopDao.insertShops(shops.map(::toEntity))
    }

    // ─── Mapping ──────────────────────────────────────────────────────────

    private fun toEntity(l: Listing) = ListingEntity(
        id = l.id,
        shopId = l.shopId,
        sellerId = l.sellerId,
        title = l.title,
        description = l.description,
        priceMinorUnits = l.price.minorUnits,
        priceCurrency = l.price.currency,
        imageUrlsJson = json.encodeToString(l.imageUrls),
        category = l.category,
        listingType = l.listingType.name,
        isAvailable = l.isAvailable,
        isSponsored = l.isSponsored,
        commitmentCount = l.commitmentCount,
        deliveryEstimateDays = l.deliveryEstimateDays,
        createdAt = l.createdAt
    )

    private fun toDomain(e: ListingEntity) = Listing(
        id = e.id,
        shopId = e.shopId,
        sellerId = e.sellerId,
        title = e.title,
        description = e.description,
        price = MoneyAmount(e.priceCurrency, e.priceMinorUnits),
        imageUrls = runCatching { json.decodeFromString<List<String>>(e.imageUrlsJson) }.getOrDefault(emptyList()),
        category = e.category,
        listingType = runCatching { ListingType.valueOf(e.listingType) }.getOrDefault(ListingType.BUY),
        isAvailable = e.isAvailable,
        isSponsored = e.isSponsored,
        commitmentCount = e.commitmentCount,
        deliveryEstimateDays = e.deliveryEstimateDays,
        createdAt = e.createdAt
    )

    private fun toEntity(s: Shop) = ShopEntity(
        id = s.id,
        ownerId = s.ownerId,
        name = s.name,
        description = s.description,
        logoUrl = s.logoUrl,
        coverUrl = s.coverUrl,
        category = s.category,
        locationLat = s.location.lat,
        locationLng = s.location.lng,
        locationAddress = s.locationAddress,
        isVerified = s.isVerified,
        rating = s.rating,
        reviewCount = s.reviewCount,
        listingCount = s.listingCount
    )
}
