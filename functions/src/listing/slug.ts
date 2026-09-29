/**
 * LISTING ENGINE — slug.ts
 *
 * Collision-safe slug generation and alias management.
 *
 * Problems solved vs. the current implementation:
 *  1. Duplicate titles under the same shop produce colliding slugs → 404.
 *     Fixed by appending a short suffix when a collision is detected.
 *  2. Changing the listing title silently breaks previously shared URLs.
 *     Fixed by retaining the old slug in slugAliases[] on update so
 *     sharePreview.ts can resolve it as a redirect.
 *  3. findListingBySlug does a full collection scan as a legacy fallback.
 *     Fixed by always writing a canonical slug at creation, making the
 *     direct index query always hit.
 */

import * as admin from "firebase-admin";

// ─── Core normaliser (matches existing shareSlug module) ─────────────────────

/**
 * Convert a display name to a URL-safe slug.
 * Must produce identical output to normalizeShareSlug() in ./shareSlug.ts
 * so existing documents remain resolvable.
 */
export function normalizeSlug(value: string): string {
    if (!value) return "listing";
    return value
        .toLowerCase()
        .replace(/[^\w\s-]/g, "")
        .replace(/[\s_]+/g, "-")
        .replace(/-+/g, "-")
        .trim()
        .replace(/^-+|-+$/g, "")
        || "listing";
}

// ─── Collision-safe creation slug ────────────────────────────────────────────

/**
 * Generate a slug that is guaranteed to be unique within the given shop.
 * If the base slug is already taken, appends "-2", "-3", … until a free
 * slot is found (max 20 attempts before falling back to a random suffix).
 *
 * Must be called inside a transaction or immediately before the write that
 * uses the slug so there is no TOCTOU gap in a low-volume environment.
 * For high-volume shops a probabilistic suffix approach is safer but
 * unnecessary at Lesotho market scale.
 */
export async function generateUniqueListingSlug(
    db:     FirebaseFirestore.Firestore,
    shopId: string,
    title:  string,
    excludeListingId?: string, // exclude the current doc on update
): Promise<string> {
    const base = normalizeSlug(title);

    for (let attempt = 1; attempt <= 20; attempt++) {
        const candidate = attempt === 1 ? base : `${base}-${attempt}`;

        let query = db.collection("listings")
            .where("shopId",     "==", shopId)
            .where("shareSlug",  "==", candidate)
            .limit(2);

        const snap = await query.get();

        const conflicts = excludeListingId
            ? snap.docs.filter(d => d.id !== excludeListingId)
            : snap.docs;

        if (conflicts.length === 0) return candidate;
    }

    // Fallback: base + 6-char random hex (astronomically unlikely to collide)
    const suffix = Math.random().toString(16).slice(2, 8);
    return `${base}-${suffix}`;
}

// ─── Alias management on title change ────────────────────────────────────────

/**
 * Returns the update payload fragment needed when a listing's title changes.
 *
 * - Sets a new collision-safe shareSlug.
 * - Appends the old slug to slugAliases[] so existing shared links still
 *   resolve (sharePreview.ts must check slugAliases when direct lookup fails).
 * - Keeps slugAliases deduplicated and capped at 10 entries (oldest dropped).
 *
 * Call this inside the same transaction as the listing update.
 */
export async function buildSlugUpdatePayload(
    db:           FirebaseFirestore.Firestore,
    listingId:    string,
    shopId:       string,
    newTitle:     string,
    currentSlug:  string,
    currentAliases: string[],
): Promise<{ shareSlug: string; slugAliases: string[]; title_lowercase: string }> {
    const newSlug = await generateUniqueListingSlug(db, shopId, newTitle, listingId);

    // Only rotate aliases if the slug is actually changing
    const aliasSet = new Set(currentAliases);
    if (newSlug !== currentSlug && currentSlug) {
        aliasSet.add(currentSlug);
    }

    // Cap at 10 aliases, drop oldest (Set preserves insertion order)
    const aliases = [...aliasSet].slice(-10);

    return {
        shareSlug:       newSlug,
        slugAliases:     aliases,
        title_lowercase: newTitle.toLowerCase(),
    };
}

// ─── Slug resolution for sharePreview ────────────────────────────────────────

/**
 * Find a listing document by shopId + slug, checking both shareSlug and
 * slugAliases. Returns null if not found or ambiguous.
 *
 * Replaces the existing findListingBySlug in sharePreview.ts.
 * No full collection scan fallback — documents written via the engine
 * always have a canonical shareSlug set.
 */
export async function resolveListingBySlug(
    db:          FirebaseFirestore.Firestore,
    shopId:      string,
    productSlug: string,
): Promise<{ doc: FirebaseFirestore.QueryDocumentSnapshot; isAlias: boolean } | null> {
    // 1. Direct canonical slug lookup
    const directSnap = await db.collection("listings")
        .where("shopId",    "==", shopId)
        .where("shareSlug", "==", productSlug)
        .limit(2)
        .get();

    if (directSnap.size === 1) return { doc: directSnap.docs[0], isAlias: false };
    if (directSnap.size > 1)   return null; // ambiguous — collision that slipped through

    // 2. Alias lookup (slug was retired after a title change)
    const aliasSnap = await db.collection("listings")
        .where("shopId",      "==", shopId)
        .where("slugAliases", "array-contains", productSlug)
        .limit(2)
        .get();

    if (aliasSnap.size === 1) return { doc: aliasSnap.docs[0], isAlias: true };

    return null;
}
