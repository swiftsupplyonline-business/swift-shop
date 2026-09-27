/**
 * LISTING ENGINE — inventory.ts
 *
 * Authoritative inventory management.
 * All stock mutations go through these functions inside transactions.
 *
 * Inventory state machine per listing:
 *
 *  stockQuantity    → total units the seller says exist
 *  reservedQuantity → units locked by active reservations
 *  available        = stockQuantity - reservedQuantity
 *
 *  reserve(qty)  → available -= qty; reservedQuantity += qty
 *  release(qty)  → reservedQuantity -= qty (order cancelled / payment failed)
 *  commit(qty)   → stockQuantity -= qty; reservedQuantity -= qty (order confirmed)
 *
 * UNLIMITED-mode listings skip stock checks entirely.
 * NOT_APPLICABLE listings reject all inventory operations.
 */

import * as admin from "firebase-admin";
import { InventoryMode, ListingStatus } from "./types";
import { deriveStatusFromStock } from "./lifecycle";
import { isAvailableFromStatus } from "./types";

// ─── Reserve ──────────────────────────────────────────────────────────────────

/**
 * Reserve `quantity` units of a listing within a Firestore transaction.
 * Updates reservedQuantity and, if stock reaches zero, flips status to
 * OUT_OF_STOCK.
 *
 * Throws if:
 *  - inventoryMode is NOT_APPLICABLE
 *  - available < quantity (for STOCKED mode)
 */
export function reserveInventory(
    transaction:  FirebaseFirestore.Transaction,
    listingRef:   FirebaseFirestore.DocumentReference,
    listing:      FirebaseFirestore.DocumentData,
    quantity:     number,
    now:          FirebaseFirestore.FieldValue,
): void {
    const mode = listing.inventoryMode as InventoryMode;

    if (mode === InventoryMode.NOT_APPLICABLE) {
        throw new Error(
            `Listing "${listing.title}" does not support inventory reservation`
        );
    }

    if (mode !== InventoryMode.STOCKED) {
        // UNLIMITED / PREORDER / SCHEDULED — no stock tracking needed
        return;
    }

    const totalStock      = listing.stockQuantity    || 0;
    const currentReserved = listing.reservedQuantity || 0;
    const available       = totalStock - currentReserved;

    if (available < quantity) {
        throw new Error(
            `Insufficient stock for "${listing.title}". ` +
            `Requested: ${quantity}, Available: ${available}`
        );
    }

    const newReserved = currentReserved + quantity;
    const newAvailable = totalStock - newReserved;
    const currentStatus = listing.status as ListingStatus;
    const newStatus = deriveStatusFromStock(currentStatus, newAvailable);

    const update: Record<string, unknown> = {
        reservedQuantity: newReserved,
        updatedAt: now,
    };

    if (newStatus !== currentStatus) {
        update.status      = newStatus;
        update.isAvailable = isAvailableFromStatus(newStatus);
    }

    transaction.update(listingRef, update);
}

// ─── Release ──────────────────────────────────────────────────────────────────

/**
 * Release `quantity` reserved units back to available pool.
 * Called when an order is cancelled or payment fails.
 * Never modifies stockQuantity — only reservedQuantity.
 */
export function releaseInventory(
    transaction:  FirebaseFirestore.Transaction,
    listingRef:   FirebaseFirestore.DocumentReference,
    listing:      FirebaseFirestore.DocumentData,
    quantity:     number,
    now:          FirebaseFirestore.FieldValue,
): void {
    const mode = listing.inventoryMode as InventoryMode;

    if (mode !== InventoryMode.STOCKED) return; // nothing to release

    const totalStock      = listing.stockQuantity    || 0;
    const currentReserved = listing.reservedQuantity || 0;
    const newReserved     = Math.max(0, currentReserved - quantity);
    const newAvailable    = totalStock - newReserved;

    const currentStatus = listing.status as ListingStatus;
    const newStatus = deriveStatusFromStock(currentStatus, newAvailable);

    const update: Record<string, unknown> = {
        reservedQuantity: newReserved,
        updatedAt: now,
    };

    if (newStatus !== currentStatus) {
        update.status      = newStatus;
        update.isAvailable = isAvailableFromStatus(newStatus);
    }

    transaction.update(listingRef, update);
}

// ─── Commit ───────────────────────────────────────────────────────────────────

/**
 * Commit `quantity` units: decrement both stockQuantity and reservedQuantity.
 * Called when payment is confirmed (verifyMopayPayment / wallet pay).
 *
 * This is the only place stockQuantity is decremented.
 */
export function commitInventory(
    transaction:  FirebaseFirestore.Transaction,
    listingRef:   FirebaseFirestore.DocumentReference,
    listing:      FirebaseFirestore.DocumentData,
    quantity:     number,
    now:          FirebaseFirestore.FieldValue,
): void {
    const mode = listing.inventoryMode as InventoryMode;

    if (mode !== InventoryMode.STOCKED) return; // nothing to commit

    const currentStock    = listing.stockQuantity    || 0;
    const currentReserved = listing.reservedQuantity || 0;

    const newStock    = Math.max(0, currentStock    - quantity);
    const newReserved = Math.max(0, currentReserved - quantity);

    const currentStatus = listing.status as ListingStatus;
    const newStatus = deriveStatusFromStock(currentStatus, newStock - newReserved);

    const update: Record<string, unknown> = {
        stockQuantity:    newStock,
        reservedQuantity: newReserved,
        updatedAt: now,
    };

    if (newStatus !== currentStatus) {
        update.status      = newStatus;
        update.isAvailable = isAvailableFromStatus(newStatus);
    }

    transaction.update(listingRef, update);
}

// ─── Availability check (read-only) ──────────────────────────────────────────

/**
 * Return the number of units currently available for reservation.
 * Returns Infinity for non-STOCKED modes (no limit).
 */
export function availableStock(listing: FirebaseFirestore.DocumentData): number {
    const mode = listing.inventoryMode as InventoryMode;
    if (mode !== InventoryMode.STOCKED) return Infinity;
    return Math.max(0, (listing.stockQuantity || 0) - (listing.reservedQuantity || 0));
}
