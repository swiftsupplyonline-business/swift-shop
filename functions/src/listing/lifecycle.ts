/**
 * LISTING ENGINE — lifecycle.ts
 *
 * Authoritative state machine for ListingStatus transitions.
 *
 * Rules:
 *  - Sellers may move listings through normal operational states.
 *  - Admins may additionally suspend, reinstate, or mark-deleted.
 *  - Deleted listings may not be transitioned; they must be hard-deleted.
 *  - OUT_OF_STOCK is set by the engine (inventory.ts); sellers cannot
 *    set it directly — they can only restock (ACTIVE) or pause (PAUSED).
 */

import { ListingStatus } from "./types";

// ─── Transition table ─────────────────────────────────────────────────────────

/** Seller-allowed transitions (excluding admin-only ones). */
const SELLER_TRANSITIONS: Partial<Record<ListingStatus, ListingStatus[]>> = {
    [ListingStatus.DRAFT]:        [ListingStatus.ACTIVE, ListingStatus.ARCHIVED],
    [ListingStatus.ACTIVE]:       [ListingStatus.PAUSED, ListingStatus.ARCHIVED],
    [ListingStatus.PAUSED]:       [ListingStatus.ACTIVE, ListingStatus.ARCHIVED],
    [ListingStatus.OUT_OF_STOCK]: [ListingStatus.PAUSED, ListingStatus.ARCHIVED],
    // ARCHIVED, SUSPENDED, DELETED → no seller transitions
};

/** Admin-only additional transitions. */
const ADMIN_TRANSITIONS: Partial<Record<ListingStatus, ListingStatus[]>> = {
    [ListingStatus.DRAFT]:        [ListingStatus.SUSPENDED, ListingStatus.DELETED],
    [ListingStatus.ACTIVE]:       [ListingStatus.SUSPENDED, ListingStatus.DELETED],
    [ListingStatus.PAUSED]:       [ListingStatus.SUSPENDED, ListingStatus.DELETED],
    [ListingStatus.OUT_OF_STOCK]: [ListingStatus.SUSPENDED, ListingStatus.DELETED],
    [ListingStatus.ARCHIVED]:     [ListingStatus.ACTIVE, ListingStatus.SUSPENDED, ListingStatus.DELETED],
    [ListingStatus.SUSPENDED]:    [ListingStatus.ACTIVE, ListingStatus.ARCHIVED, ListingStatus.DELETED],
};

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Assert that the requested status transition is legal for the caller.
 * Throws a plain Error on violation.
 */
export function assertStatusTransition(
    currentStatus: ListingStatus,
    targetStatus:  ListingStatus,
    isAdmin:       boolean,
): void {
    if (currentStatus === ListingStatus.DELETED) {
        throw new Error("Deleted listings cannot be transitioned");
    }

    if (targetStatus === ListingStatus.OUT_OF_STOCK) {
        throw new Error(
            "OUT_OF_STOCK is set by the inventory engine; " +
            "restock by setting stockQuantity > 0 and status ACTIVE"
        );
    }

    const sellerAllowed = SELLER_TRANSITIONS[currentStatus] ?? [];
    if (sellerAllowed.includes(targetStatus)) return;

    if (isAdmin) {
        const adminAllowed = ADMIN_TRANSITIONS[currentStatus] ?? [];
        if (adminAllowed.includes(targetStatus)) return;
    }

    throw new Error(
        `Status transition ${currentStatus} → ${targetStatus} is not permitted` +
        (isAdmin ? "" : " (admin-only transitions exist for this state)")
    );
}

/**
 * Derive the new status after a stock change.
 * Called by inventory.ts after committing or releasing stock.
 *
 *  - If current status is ACTIVE and stock reaches zero → OUT_OF_STOCK
 *  - If current status is OUT_OF_STOCK and stock is restored → ACTIVE
 *  - All other statuses are unchanged.
 */
export function deriveStatusFromStock(
    currentStatus: ListingStatus,
    newStock:      number,
): ListingStatus {
    if (currentStatus === ListingStatus.ACTIVE && newStock <= 0) {
        return ListingStatus.OUT_OF_STOCK;
    }
    if (currentStatus === ListingStatus.OUT_OF_STOCK && newStock > 0) {
        return ListingStatus.ACTIVE;
    }
    return currentStatus;
}

/**
 * Determine whether a status change affects the seller's
 * activeListingCount on their profile. Returns the delta to apply.
 *  +1 → going active
 *  -1 → leaving active
 *   0 → no change needed
 */
export function activeCountDelta(
    oldStatus: ListingStatus,
    newStatus: ListingStatus,
): number {
    const wasActive = oldStatus === ListingStatus.ACTIVE;
    const isActive  = newStatus === ListingStatus.ACTIVE;
    if (!wasActive && isActive)  return +1;
    if (wasActive  && !isActive) return -1;
    return 0;
}
