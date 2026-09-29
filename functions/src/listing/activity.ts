/**
 * LISTING ENGINE — activity.ts
 *
 * Authoritative activity / history log for Listing state changes.
 *
 * Every meaningful event in a Listing's lifecycle is written as an immutable
 * document in listingActivities/{activityId}. The listing document itself
 * holds current state; this subcollection holds the story of how it got there.
 *
 * Collection: listingActivities/{activityId}
 * Indexed by: listingId, type, actorId, createdAt
 *
 * Design rules:
 *  - Activity documents are NEVER updated or deleted after creation.
 *  - All writes are performed inside the same transaction as the state change.
 *  - The engine writes activities; no client writes directly to this collection.
 *  - Firestore security rules must deny all client writes to listingActivities.
 *
 * commerce.ts integration points (what will call recordActivity):
 *   createListing       → CREATED, PUBLISHED
 *   updateListing       → UPDATED, PRICE_CHANGED, SHOP_TRANSFERRED
 *   restockListing      → RESTOCKED
 *   archiveListing      → ARCHIVED
 *   suspendListing      → SUSPENDED (admin)
 *   reinstateListing    → REINSTATED (admin)
 *   verifyMopayPayment  → PURCHASE_CONFIRMED
 *   cancelOrder         → PURCHASE_CANCELLED
 *   createDeliveryRequest → DELIVERY_REQUESTED
 */

import * as admin from "firebase-admin";

// ─── Event types ──────────────────────────────────────────────────────────────

export enum ListingActivityType {
    // Lifecycle
    CREATED           = "CREATED",
    PUBLISHED         = "PUBLISHED",
    UPDATED           = "UPDATED",
    PAUSED            = "PAUSED",
    RESUMED           = "RESUMED",
    OUT_OF_STOCK      = "OUT_OF_STOCK",
    RESTOCKED         = "RESTOCKED",
    ARCHIVED          = "ARCHIVED",
    RESTORED          = "RESTORED",

    // Admin actions
    SUSPENDED         = "SUSPENDED",
    REINSTATED        = "REINSTATED",

    // Commerce events
    PURCHASE_CONFIRMED  = "PURCHASE_CONFIRMED",
    PURCHASE_CANCELLED  = "PURCHASE_CANCELLED",
    DELIVERY_REQUESTED  = "DELIVERY_REQUESTED",
    DELIVERY_COMPLETED  = "DELIVERY_COMPLETED",
    APPOINTMENT_BOOKED  = "APPOINTMENT_BOOKED",
    APPOINTMENT_COMPLETED = "APPOINTMENT_COMPLETED",
    REGISTRATION_COMPLETED = "REGISTRATION_COMPLETED",

    // Price events
    PRICE_CHANGED     = "PRICE_CHANGED",

    // Structural events
    SHOP_TRANSFERRED  = "SHOP_TRANSFERRED",

    // Engagement milestones (optional; fire at thresholds like 100/1000 views)
    VIEW_MILESTONE    = "VIEW_MILESTONE",
    COMMITMENT_MILESTONE = "COMMITMENT_MILESTONE",
}

// ─── Activity document shape ──────────────────────────────────────────────────

export interface ListingActivityDoc {
    id:        string;
    listingId: string;
    type:      ListingActivityType;

    /** UID of the person who caused this event (seller, buyer, admin, or "system"). */
    actorId:   string;
    actorRole: "seller" | "buyer" | "admin" | "system";

    /** Human-readable summary — used in seller dashboard activity feed. */
    summary:   string;

    /** Structured event data specific to the activity type. */
    metadata:  Record<string, unknown>;

    createdAt: FirebaseFirestore.FieldValue | FirebaseFirestore.Timestamp;
}

// ─── Writer ───────────────────────────────────────────────────────────────────

/**
 * Write an activity record inside an existing Firestore transaction.
 *
 * Always call this as the LAST operation before the transaction closes so
 * that a transaction failure rolls back the activity along with the state
 * change it describes.
 *
 * @param transaction  The active Firestore transaction
 * @param db           Firestore instance
 * @param listingId    The listing this event belongs to
 * @param type         The event type from ListingActivityType
 * @param actorId      UID of the triggering actor (pass "system" for scheduled functions)
 * @param actorRole    Role classification of the actor
 * @param summary      Short human-readable description for the dashboard feed
 * @param metadata     Structured data relevant to the event type (old/new values, IDs, etc.)
 */
export function recordActivity(
    transaction: FirebaseFirestore.Transaction,
    db:          FirebaseFirestore.Firestore,
    listingId:   string,
    type:        ListingActivityType,
    actorId:     string,
    actorRole:   ListingActivityDoc["actorRole"],
    summary:     string,
    metadata:    Record<string, unknown> = {},
): void {
    const activityRef = db.collection("listingActivities").doc();
    const now         = admin.firestore.FieldValue.serverTimestamp();

    const doc: Omit<ListingActivityDoc, "createdAt"> & { createdAt: FirebaseFirestore.FieldValue } = {
        id:        activityRef.id,
        listingId,
        type,
        actorId,
        actorRole,
        summary,
        metadata,
        createdAt: now,
    };

    transaction.set(activityRef, doc);
}

// ─── Pre-built activity builders ──────────────────────────────────────────────
//
// Each function returns the (summary, metadata) pair for a specific event.
// Pass these directly to recordActivity.
//

export function activityCreated(
    title: string,
    listingType: string,
    shopId: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Listing "${title}" created`,
        metadata: { listingType, shopId },
    };
}

export function activityPublished(
    title: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Listing "${title}" published and now live`,
        metadata: {},
    };
}

export function activityPriceChanged(
    title:        string,
    oldPriceMinor: number,
    newPriceMinor: number,
    currency:      string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    const fmt = (v: number) => `${currency} ${(v / 100).toFixed(2)}`;
    return {
        summary:  `Price changed from ${fmt(oldPriceMinor)} to ${fmt(newPriceMinor)}`,
        metadata: { oldPriceMinorUnits: oldPriceMinor, newPriceMinorUnits: newPriceMinor, currency },
    };
}

export function activityRestocked(
    title:       string,
    addedQty:    number,
    newTotalQty: number,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Restocked +${addedQty} units (new total: ${newTotalQty})`,
        metadata: { addedQuantity: addedQty, newStockQuantity: newTotalQty },
    };
}

export function activityOutOfStock(
    title: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `"${title}" went out of stock`,
        metadata: {},
    };
}

export function activityPurchaseConfirmed(
    title:    string,
    orderId:  string,
    quantity: number,
    amountMinorUnits: number,
    currency: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    const display = `${currency} ${(amountMinorUnits / 100).toFixed(2)}`;
    return {
        summary:  `Purchase confirmed: ${quantity}× "${title}" for ${display}`,
        metadata: { orderId, quantity, amountMinorUnits, currency },
    };
}

export function activityPurchaseCancelled(
    title:    string,
    orderId:  string,
    reason:   string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Order cancelled for "${title}": ${reason}`,
        metadata: { orderId, reason },
    };
}

export function activityShopTransferred(
    title:      string,
    oldShopId:  string,
    newShopId:  string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Listing "${title}" moved to a different shop`,
        metadata: { oldShopId, newShopId },
    };
}

export function activitySuspended(
    title:  string,
    reason: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Listing "${title}" suspended by admin: ${reason}`,
        metadata: { reason },
    };
}

export function activityReinstated(
    title: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Listing "${title}" reinstated`,
        metadata: {},
    };
}

export function activityDeliveryRequested(
    title:     string,
    requestId: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Delivery requested via "${title}"`,
        metadata: { requestId },
    };
}

export function activityDeliveryCompleted(
    title:     string,
    requestId: string,
): Pick<ListingActivityDoc, "summary" | "metadata"> {
    return {
        summary:  `Delivery completed via "${title}"`,
        metadata: { requestId },
    };
}
