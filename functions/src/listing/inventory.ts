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
 *  restock(qty)  → stockQuantity += qty; status re-derived
 *
 * UNLIMITED-mode listings skip stock checks entirely.
 * NOT_APPLICABLE listings reject reserve/commit/release operations.
 *
 * commerce.ts integration points:
 *  createPurchaseOrder → reserveInventory()
 *  verifyMopayPayment  → commitInventory() + incrementCommitment()
 *  cancelOrder         → releaseInventory()
 *  cleanupExpiredReservations → releaseInventory()
 */

import * as admin from "firebase-admin";
import { InventoryMode, ListingStatus, ListingType, LISTING_TYPE_WORKFLOWS } from "./types";
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

    const newReserved  = currentReserved + quantity;
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
 *
 * Safe to call from both cancelOrder and cleanupExpiredReservations.
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
 * Called when payment is confirmed (verifyMopayPayment / wallet pay path).
 *
 * This is the ONLY place stockQuantity is decremented.
 * Also increments commitmentCount — a successful commit is a commitment.
 */
export function commitInventory(
    transaction:  FirebaseFirestore.Transaction,
    listingRef:   FirebaseFirestore.DocumentReference,
    listing:      FirebaseFirestore.DocumentData,
    quantity:     number,
    now:          FirebaseFirestore.FieldValue,
): void {
    const mode = listing.inventoryMode as InventoryMode;
    const type = listing.listingType as ListingType;
    const tracksCommitment = LISTING_TYPE_WORKFLOWS[type]?.tracksCommitment ?? false;

    const update: Record<string, unknown> = { updatedAt: now };

    if (mode === InventoryMode.STOCKED) {
        const currentStock    = listing.stockQuantity    || 0;
        const currentReserved = listing.reservedQuantity || 0;

        const newStock    = Math.max(0, currentStock    - quantity);
        const newReserved = Math.max(0, currentReserved - quantity);

        const currentStatus = listing.status as ListingStatus;
        const newStatus = deriveStatusFromStock(currentStatus, newStock - newReserved);

        update.stockQuantity    = newStock;
        update.reservedQuantity = newReserved;

        if (newStatus !== currentStatus) {
            update.status      = newStatus;
            update.isAvailable = isAvailableFromStatus(newStatus);
        }
    }
    // UNLIMITED / PREORDER / SCHEDULED: no stock mutation, but commitment still tracked.
    // NOT_APPLICABLE: should not reach here, but we still track commitment if configured.

    if (tracksCommitment) {
        update.commitmentCount = admin.firestore.FieldValue.increment(quantity);
    }

    transaction.update(listingRef, update);
}

// ─── Restock ──────────────────────────────────────────────────────────────────

/**
 * Add `quantity` units to a listing's stock.
 * The only authorised path for a seller to recover from OUT_OF_STOCK.
 *
 * Rules:
 *  - Only valid for STOCKED-mode listings.
 *  - Listing must be in a seller-modifiable status (not SUSPENDED/DELETED).
 *  - Derives new status via deriveStatusFromStock:
 *      OUT_OF_STOCK + newAvailable > 0  → ACTIVE
 *      PAUSED remains PAUSED (seller must explicitly resume).
 *      ACTIVE remains ACTIVE.
 *
 * Call this inside a transaction that has already read the listing doc.
 * Throws on invalid mode or insufficient quantity.
 */
export function restockInventory(
    transaction:  FirebaseFirestore.Transaction,
    listingRef:   FirebaseFirestore.DocumentReference,
    listing:      FirebaseFirestore.DocumentData,
    quantity:     number,
    now:          FirebaseFirestore.FieldValue,
): void {
    if (quantity <= 0 || !Number.isInteger(quantity)) {
        throw new Error("Restock quantity must be a positive integer");
    }

    const mode = listing.inventoryMode as InventoryMode;
    if (mode !== InventoryMode.STOCKED) {
        throw new Error(
            `Restock is only valid for STOCKED listings. ` +
            `This listing uses inventoryMode: ${mode}`
        );
    }

    const currentStatus = listing.status as ListingStatus;
    if (
        currentStatus === ListingStatus.SUSPENDED ||
        currentStatus === ListingStatus.DELETED
    ) {
        throw new Error(
            `Cannot restock a listing in ${currentStatus} status`
        );
    }

    const currentStock    = listing.stockQuantity    || 0;
    const currentReserved = listing.reservedQuantity || 0;
    const newStock        = currentStock + quantity;
    const newAvailable    = newStock - currentReserved;

    // Only auto-promote OUT_OF_STOCK → ACTIVE. PAUSED stays PAUSED.
    const newStatus = deriveStatusFromStock(currentStatus, newAvailable);

    const update: Record<string, unknown> = {
        stockQuantity: newStock,
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

// ─── Purchase + commit in one step (wallet-paid orders) ───────────────────────

/**
 * Reserve AND commit `quantity` units in a single write. Used when payment is
 * already settled inside the same transaction (wallet-paid orders), so there is
 * no ACTIVE reservation left for the expiry sweep to release.
 *
 *  stockQuantity -= quantity; reservedQuantity unchanged (other buyers' holds stay intact).
 *
 * Throws if the quantity is not a positive integer, the mode does not support
 * inventory, or STOCKED availability is insufficient.
 */
export function purchaseAndCommitInventory(
    transaction:  FirebaseFirestore.Transaction,
    listingRef:   FirebaseFirestore.DocumentReference,
    listing:      FirebaseFirestore.DocumentData,
    quantity:     number,
    now:          FirebaseFirestore.FieldValue,
): void {
    if (!Number.isSafeInteger(quantity) || quantity <= 0) {
        throw new Error("Quantity must be a positive whole number");
    }
    const mode = listing.inventoryMode as InventoryMode;
    const type = listing.listingType as ListingType;
    if (mode === InventoryMode.NOT_APPLICABLE) {
        throw new Error(`Listing "${listing.title}" does not support inventory reservation`);
    }

    const update: Record<string, unknown> = { updatedAt: now };

    if (mode === InventoryMode.STOCKED) {
        const totalStock      = listing.stockQuantity    || 0;
        const currentReserved = listing.reservedQuantity || 0;
        const available       = totalStock - currentReserved;
        if (available < quantity) {
            throw new Error(
                `Insufficient stock for "${listing.title}". ` +
                `Requested: ${quantity}, Available: ${available}`
            );
        }
        const newStock = totalStock - quantity;
        const currentStatus = listing.status as ListingStatus;
        const newStatus = deriveStatusFromStock(currentStatus, newStock - currentReserved);
        update.stockQuantity = newStock;
        if (newStatus !== currentStatus) {
            update.status      = newStatus;
            update.isAvailable = isAvailableFromStatus(newStatus);
        }
    }

    if (LISTING_TYPE_WORKFLOWS[type]?.tracksCommitment ?? false) {
        update.commitmentCount = admin.firestore.FieldValue.increment(quantity);
    }

    transaction.update(listingRef, update);
}

// ─── Return committed stock (cancelled paid order) ────────────────────────────

/**
 * Give `quantity` previously COMMITTED units back to stock when a paid order is
 * cancelled and refunded. Counterpart of commitInventory / purchaseAndCommitInventory.
 * Re-derives status (OUT_OF_STOCK → ACTIVE when stock returns). STOCKED mode only.
 */
export function returnCommittedInventory(
    transaction:  FirebaseFirestore.Transaction,
    listingRef:   FirebaseFirestore.DocumentReference,
    listing:      FirebaseFirestore.DocumentData,
    quantity:     number,
    now:          FirebaseFirestore.FieldValue,
): void {
    if (listing.inventoryMode !== InventoryMode.STOCKED) return;
    const newStock = (listing.stockQuantity || 0) + quantity;
    const newAvailable = newStock - (listing.reservedQuantity || 0);
    const currentStatus = listing.status as ListingStatus;
    const newStatus = deriveStatusFromStock(currentStatus, newAvailable);
    const update: Record<string, unknown> = { stockQuantity: newStock, updatedAt: now };
    if (newStatus !== currentStatus) {
        update.status      = newStatus;
        update.isAvailable = isAvailableFromStatus(newStatus);
    }
    transaction.update(listingRef, update);
}
