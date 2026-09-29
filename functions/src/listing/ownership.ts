/**
 * LISTING ENGINE — ownership.ts
 *
 * Shop relationship validation and counter maintenance.
 *
 * Counter fields managed here:
 *  shops/{shopId}.listingCount          — total listings in a shop
 *  profiles/{sellerId}.activeListingCount — total ACTIVE listings for a seller
 *
 * All mutations are applied inside the caller's Firestore transaction.
 * This module never opens its own transaction.
 */

import { ListingStatus } from "./types";
import { activeCountDelta } from "./lifecycle";

// ─── Creation ─────────────────────────────────────────────────────────────────

/**
 * Apply counter increments when a new listing is created.
 *
 *  shops/{shopId}.listingCount  += 1
 *  profiles/{sellerId}.activeListingCount += 1  (if status === ACTIVE)
 */
export function applyCreationCounters(
    transaction:  FirebaseFirestore.Transaction,
    db:           FirebaseFirestore.Firestore,
    shopId:       string,
    sellerId:     string,
    shopData:     FirebaseFirestore.DocumentData,
    profileData:  FirebaseFirestore.DocumentData,
    newStatus:    ListingStatus,
    now:          FirebaseFirestore.FieldValue,
): void {
    const shopRef    = db.collection("shops").doc(shopId);
    const profileRef = db.collection("profiles").doc(sellerId);

    transaction.update(shopRef, {
        listingCount: (shopData.listingCount || 0) + 1,
        updatedAt: now,
    });

    if (newStatus === ListingStatus.ACTIVE) {
        transaction.update(profileRef, {
            activeListingCount: (profileData.activeListingCount || 0) + 1,
            updatedAt: now,
        });
    }
}

// ─── Deletion ─────────────────────────────────────────────────────────────────

/**
 * Apply counter decrements when a listing is hard-deleted.
 *
 *  shops/{shopId}.listingCount          -= 1
 *  profiles/{sellerId}.activeListingCount -= 1  (if listing was ACTIVE)
 */
export function applyDeletionCounters(
    transaction: FirebaseFirestore.Transaction,
    db:          FirebaseFirestore.Firestore,
    listing:     FirebaseFirestore.DocumentData,
    shopData:    FirebaseFirestore.DocumentData | null,
    profileData: FirebaseFirestore.DocumentData | null,
    now:         FirebaseFirestore.FieldValue,
): void {
    if (shopData) {
        const shopRef = db.collection("shops").doc(listing.shopId);
        transaction.update(shopRef, {
            listingCount: Math.max(0, (shopData.listingCount || 0) - 1),
            updatedAt: now,
        });
    }

    const wasActive = listing.status === ListingStatus.ACTIVE;
    if (profileData && wasActive) {
        const profileRef = db.collection("profiles").doc(listing.sellerId);
        transaction.update(profileRef, {
            activeListingCount: Math.max(0, (profileData.activeListingCount || 0) - 1),
            updatedAt: now,
        });
    }
}

// ─── Update (status change + optional shop transfer) ─────────────────────────

/**
 * Apply counter corrections when a listing is updated.
 * Handles three independent concerns:
 *
 *  1. Status change → adjust profile.activeListingCount
 *  2. Shop transfer → decrement old shop, increment new shop
 *  3. Both at once  → apply all deltas atomically
 */
export function applyUpdateCounters(
    transaction:    FirebaseFirestore.Transaction,
    db:             FirebaseFirestore.Firestore,
    listing:        FirebaseFirestore.DocumentData,   // current Firestore state
    oldStatus:      ListingStatus,
    newStatus:      ListingStatus,
    oldShopId:      string,
    newShopId:      string,
    oldShopData:    FirebaseFirestore.DocumentData | null,
    newShopData:    FirebaseFirestore.DocumentData | null, // only when shop changes
    profileData:    FirebaseFirestore.DocumentData | null,
    now:            FirebaseFirestore.FieldValue,
): void {
    // 1. Active count delta
    const delta = activeCountDelta(oldStatus, newStatus);
    if (delta !== 0 && profileData) {
        const profileRef = db.collection("profiles").doc(listing.sellerId);
        transaction.update(profileRef, {
            activeListingCount: Math.max(
                0,
                (profileData.activeListingCount || 0) + delta
            ),
            updatedAt: now,
        });
    }

    // 2. Shop transfer counter maintenance (the bug the audit found)
    if (newShopId !== oldShopId) {
        if (oldShopData) {
            const oldShopRef = db.collection("shops").doc(oldShopId);
            transaction.update(oldShopRef, {
                listingCount: Math.max(0, (oldShopData.listingCount || 0) - 1),
                updatedAt: now,
            });
        }
        if (newShopData) {
            const newShopRef = db.collection("shops").doc(newShopId);
            transaction.update(newShopRef, {
                listingCount: (newShopData.listingCount || 0) + 1,
                updatedAt: now,
            });
        }
    }
}
