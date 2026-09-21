import { onRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * Public marketplace contract.
 *
 * This mapper deliberately follows the Firestore DTOs used by the Kotlin app:
 * - FirestoreListing: priceMinorUnits/priceCurrency, imageUrls, listingType,
 *   shopId/sellerId, availability and inventory fields.
 * - FirestoreShop: locationLat/locationLng, locationAddress, ownerId,
 *   verification/activity and listing counters.
 *
 * The web marketplace reads the same production collections as the Android app;
 * it does not maintain a second catalogue.
 */

const timestampMillis = (value: any): number | null => {
    if (!value) return null;
    if (typeof value.toMillis === "function") return value.toMillis();
    if (value instanceof Date) return value.getTime();
    if (typeof value === "number") return value;
    return null;
};

const mapListing = (data: FirebaseFirestore.DocumentData, id: string) => ({
    id,
    shopId: String(data.shopId || ""),
    sellerId: String(data.sellerId || ""),
    title: String(data.title || ""),
    description: String(data.description || ""),
    priceMinorUnits: Number(data.priceMinorUnits || 0),
    priceCurrency: String(data.priceCurrency || "LSL"),
    // Kotlin's FirestoreListing uses imageUrls. Keep legacy "images" as a
    // compatibility fallback because createListing currently accepts both.
    imageUrls: Array.isArray(data.imageUrls)
        ? data.imageUrls
        : Array.isArray(data.images)
            ? data.images
            : [],
    videoUrl: String(data.videoUrl || ""),
    category: String(data.category || ""),
    tags: Array.isArray(data.tags) ? data.tags : [],
    listingType: String(data.listingType || "BUY"),
    isAvailable: data.isAvailable !== false,
    isSponsored: data.isSponsored === true,
    stockQuantity: Number(data.stockQuantity ?? 1),
    commitmentCount: Number(data.commitmentCount || 0),
    deliveryEstimateDays: Number(data.deliveryEstimateDays || 0),
    customFields: Array.isArray(data.customFields) ? data.customFields : [],
    commentCount: Number(data.commentCount || 0),
    bookmarkCount: Number(data.bookmarkCount || 0),
    likeCount: Number(data.likeCount || 0),
    createdAt: timestampMillis(data.createdAt),
    updatedAt: timestampMillis(data.updatedAt)
});

const mapShop = (data: FirebaseFirestore.DocumentData, id: string) => ({
    id,
    ownerId: String(data.ownerId || ""),
    name: String(data.name || ""),
    description: String(data.description || ""),
    logoUrl: String(data.logoUrl || ""),
    coverUrl: String(data.coverUrl || ""),
    category: String(data.category || ""),
    // Canonical Kotlin representation is locationLat/locationLng.
    // Also expose a web-friendly location object without changing the source schema.
    locationLat: Number(data.locationLat ?? data.location?.lat ?? 0),
    locationLng: Number(data.locationLng ?? data.location?.lng ?? 0),
    locationAddress: String(data.locationAddress || ""),
    location: {
        lat: Number(data.locationLat ?? data.location?.lat ?? 0),
        lng: Number(data.locationLng ?? data.location?.lng ?? 0)
    },
    isVerified: data.isVerified === true,
    isActive: data.isActive !== false,
    rating: Number(data.rating || 0),
    reviewCount: Number(data.reviewCount || 0),
    followerCount: Number(data.followerCount || 0),
    listingCount: Number(data.listingCount || 0),
    createdAt: timestampMillis(data.createdAt),
    updatedAt: timestampMillis(data.updatedAt)
});

const mapPost = (data: FirebaseFirestore.DocumentData, id: string) => ({
    id,
    authorId: String(data.authorId || ""),
    authorName: String(data.authorName || ""),
    authorAvatarUrl: String(data.authorAvatarUrl || ""),
    authorTier: String(data.authorTier || "BASIC"),
    shopId: String(data.shopId || ""),
    type: String(data.type || "IMAGE"),
    caption: String(data.caption || ""),
    mediaUrls: Array.isArray(data.mediaUrls) ? data.mediaUrls : [],
    videoUrl: String(data.videoUrl || ""),
    thumbnailUrl: String(data.thumbnailUrl || ""),
    videoDurationMs: Number(data.videoDurationMs || 0),
    likeCount: Number(data.likeCount || 0),
    commentCount: Number(data.commentCount || 0),
    reshareCount: Number(data.reshareCount || 0),
    bookmarkCount: Number(data.bookmarkCount || 0),
    hashtags: Array.isArray(data.hashtags) ? data.hashtags : [],
    mentions: Array.isArray(data.mentions) ? data.mentions : [],
    taggedListings: Array.isArray(data.taggedListings) ? data.taggedListings : [],
    taggedShops: Array.isArray(data.taggedShops) ? data.taggedShops : [],
    isSponsored: data.isSponsored === true,
    createdAt: timestampMillis(data.createdAt),
    updatedAt: timestampMillis(data.updatedAt)
});

export const publicMarketplace = onRequest({ cors: true }, async (_request, response) => {
    try {
        const db = admin.firestore();

        // These are the same production collections written/read by the Kotlin
        // commerce architecture. No web-side catalogue is maintained.
        const [listingSnap, shopSnap, postSnap] = await Promise.all([
            db.collection("listings").orderBy("createdAt", "desc").limit(100).get(),
            db.collection("shops").orderBy("createdAt", "desc").limit(100).get(),
            db.collection("posts").orderBy("createdAt", "desc").limit(100).get()
        ]);

        const listings = listingSnap.docs
            .map(d => mapListing(d.data(), d.id))
            .filter(x => x.isAvailable);

        const shops = shopSnap.docs
            .map(d => mapShop(d.data(), d.id))
            .filter(x => x.isActive);

        const posts = postSnap.docs
            .map(d => mapPost(d.data(), d.id));

        response.set("Cache-Control", "public, max-age=15, s-maxage=15");
        response.status(200).json({
            ok: true,
            generatedAt: Date.now(),
            source: "firestore:kotlin-commerce-architecture",
            schema: "swift-marketplace-v1",
            listings,
            shops,
            posts
        });
    } catch (error: any) {
        console.error("publicMarketplace failed", error);
        response.status(500).json({
            ok: false,
            error: "Marketplace data is temporarily unavailable."
        });
    }
});
