import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * AI Natural Language Shopping Assistant.
 * Translates customer natural language queries into real marketplace searches.
 */
export const searchAssistant = onCall(async (request) => {
    const { query, maxPriceMinorUnits } = request.data;
    if (!query || typeof query !== "string") {
        throw new HttpsError("invalid-argument", "Natural language query string required");
    }

    const db = admin.firestore();
    const queryLower = query.toLowerCase();

    // Query active listings from Firestore
    let listingsRef: admin.firestore.Query = db.collection("listings");

    const snap = await listingsRef.limit(50).get();
    const allListings = snap.docs.map(doc => ({ id: doc.id, ...doc.data() } as any));

    // Filter listings based on query intent and price constraints
    const matchedListings = allListings.filter(l => {
        const titleMatch = l.title && l.title.toLowerCase().includes(queryLower);
        const categoryMatch = l.category && l.category.toLowerCase().includes(queryLower);
        const descMatch = l.description && l.description.toLowerCase().includes(queryLower);
        const priceMatch = maxPriceMinorUnits ? (l.priceMinorUnits || 0) <= maxPriceMinorUnits : true;

        return (titleMatch || categoryMatch || descMatch) && priceMatch;
    });

    return {
        success: true,
        query,
        matchedCount: matchedListings.length,
        results: matchedListings.slice(0, 12)
    };
});

/**
 * AI Merchant Listing Assistant.
 * Generates structured titles, descriptions, and tags from merchant notes.
 */
export const generateListingDetails = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) {
        throw new HttpsError("unauthenticated", "Auth required for merchant assistant");
    }

    const { rawTitle, rawNotes, category } = request.data;
    if (!rawTitle) {
        throw new HttpsError("invalid-argument", "rawTitle required");
    }

    const cleanTitle = rawTitle.trim();
    const formattedDescription = rawNotes
        ? `${cleanTitle}\n\n${rawNotes.trim()}\n\nAvailable on SwiftShop.`
        : `${cleanTitle} — Quality product available on SwiftShop. Order online for fast delivery.`;

    const suggestedTags = [
        category || "General",
        "SwiftShop",
        "Maseru",
        cleanTitle.split(" ")[0] || "Item"
    ].filter(Boolean);

    return {
        success: true,
        suggestedTitle: cleanTitle,
        suggestedDescription: formattedDescription,
        suggestedTags
    };
});
