/**
 * LISTING ENGINE — validate.ts
 *
 * Single authoritative validation layer for all Listing operations.
 * Every Cloud Function that reads or writes a Listing MUST route through
 * these validators instead of implementing its own Listing rules.
 *
 * Validators throw plain Error (not HttpsError) so they can be called from
 * inside db.runTransaction() blocks. Callers wrap in HttpsError at the
 * function boundary.
 *
 * commerce.ts integration:
 *   createListing       → validateCreateInput() + validateCreateSemantic()
 *   updateListing       → validateUpdateInput() + validateUpdateSemantic()
 *   createPurchaseOrder → assertPurchasable()
 *   createDeliveryRequest → assertDeliverable()
 *   verifyMopayPayment  → assertCommittable()
 */

import {
    ListingType,
    ListingStatus,
    InventoryMode,
    LISTING_TYPE_WORKFLOWS,
    ACTIONABLE_STATUSES,
} from "./types";

// ─── Input types ──────────────────────────────────────────────────────────────

/** Fields accepted from a client at creation time. */
export interface ListingCreateInput {
    shopId:               string;
    title:                string;
    description?:         string;
    listingType?:         string;
    category?:            string;
    tags?:                string[];
    priceMinorUnits?:     number;
    priceCurrency?:       string;
    imageUrls?:           string[];
    images?:              string[];
    videoUrl?:            string;
    stockQuantity?:       number;
    deliveryEstimateDays?: number;
    durationMinutes?:     number;
    customFields?:        unknown[];
    fulfillmentOptions?:  string[];
    driverShareBps?:      number;  // DELIVER listings: driver's share of the net delivery fee, 0..10000 basis points
    isAvailable?:         boolean; // legacy; engine derives this from status
}

/** Fields accepted from a client at update time. */
export type ListingUpdateInput = Partial<Omit<ListingCreateInput, "shopId">> & {
    shopId?: string; // allowed but triggers counter maintenance in ownership.ts
};

/** Fields accepted for a restock action. */
export interface ListingRestockInput {
    listingId: string;
    quantity:  number;
}

// ─── Allowed client-owned field keys ─────────────────────────────────────────

/**
 * Exhaustive list of fields a client may supply on create or update.
 * Any key not in this list is silently dropped before persistence.
 * SERVER-owned fields (id, sellerId, status, shareSlug, commitmentCount,
 * viewCount, shareCount, publishedAt, …) must never appear here.
 */
export const CLIENT_ALLOWED_KEYS: ReadonlyArray<string> = [
    "shopId",
    "title",
    "description",
    "listingType",
    "category",
    "tags",
    "priceMinorUnits",
    "priceCurrency",
    "imageUrls",
    "images",
    "videoUrl",
    "stockQuantity",
    "deliveryEstimateDays",
    "durationMinutes",
    "customFields",
    "fulfillmentOptions",
    "driverShareBps",
    "isAvailable",
] as const;

// ─── Field sanitiser ──────────────────────────────────────────────────────────

/**
 * Strip any key not in CLIENT_ALLOWED_KEYS.
 * Always call this before writing client-supplied data to Firestore.
 * SERVER fields silently dropped here can never be injected by a client.
 */
export function sanitiseClientPayload(
    raw: Record<string, unknown>,
): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const key of CLIENT_ALLOWED_KEYS) {
        if (raw[key] !== undefined) out[key] = raw[key];
    }
    return out;
}

// ─── Creation validation ──────────────────────────────────────────────────────

/**
 * Validates a client's create payload before any Firestore reads.
 * Fast-fail on obviously bad input.
 * Throws a plain Error on failure.
 */
export function validateCreateInput(input: ListingCreateInput): void {
    if (!input.shopId || typeof input.shopId !== "string") {
        throw new Error("shopId is required");
    }
    if (!input.title || typeof input.title !== "string" || input.title.trim().length < 2) {
        throw new Error("title must be at least 2 characters");
    }
    if (input.title.trim().length > 120) {
        throw new Error("title must be 120 characters or fewer");
    }

    const type     = resolveListingType(input.listingType);
    const workflow = LISTING_TYPE_WORKFLOWS[type];

    if (workflow.priceRequired) {
        const price = input.priceMinorUnits;
        if (typeof price !== "number" || !Number.isInteger(price) || price <= 0) {
            throw new Error(
                `priceMinorUnits must be a positive integer for listingType ${type}`
            );
        }
    }

    if (workflow.requiresStock && input.stockQuantity !== undefined) {
        if (
            typeof input.stockQuantity !== "number" ||
            !Number.isInteger(input.stockQuantity) ||
            input.stockQuantity < 0
        ) {
            throw new Error("stockQuantity must be a non-negative integer");
        }
    }

    if (input.tags !== undefined) {
        if (!Array.isArray(input.tags) || input.tags.length > 20) {
            throw new Error("tags must be an array of at most 20 items");
        }
    }

    if (input.durationMinutes !== undefined) {
        if (typeof input.durationMinutes !== "number" || input.durationMinutes < 0) {
            throw new Error("durationMinutes must be a non-negative number");
        }
    }

    if (input.driverShareBps !== undefined) {
        if (!Number.isInteger(input.driverShareBps) || input.driverShareBps < 0 || input.driverShareBps > 10000) {
            throw new Error("driverShareBps must be an integer from 0 to 10000 (0% to 100%)");
        }
    }

    if (input.deliveryEstimateDays !== undefined) {
        if (
            typeof input.deliveryEstimateDays !== "number" ||
            input.deliveryEstimateDays < 0
        ) {
            throw new Error("deliveryEstimateDays must be a non-negative number");
        }
    }
}

/**
 * Full semantic validation executed inside a transaction after reading
 * the shop document.
 * Throws on any contract violation.
 */
export function validateCreateSemantic(
    input:    ListingCreateInput,
    shopData: FirebaseFirestore.DocumentData,
    sellerId: string,
): void {
    if (shopData.ownerId !== sellerId) {
        throw new Error("Unauthorized: you do not own this shop");
    }
    if (shopData.isActive === false) {
        throw new Error("Cannot create a listing in an inactive shop");
    }
}

// ─── Update validation ────────────────────────────────────────────────────────

export function validateUpdateInput(updates: ListingUpdateInput): void {
    if (updates.title !== undefined) {
        if (
            typeof updates.title !== "string" ||
            updates.title.trim().length < 2
        ) {
            throw new Error("title must be at least 2 characters");
        }
        if (updates.title.trim().length > 120) {
            throw new Error("title must be 120 characters or fewer");
        }
    }

    if (updates.priceMinorUnits !== undefined) {
        if (
            typeof updates.priceMinorUnits !== "number" ||
            !Number.isInteger(updates.priceMinorUnits) ||
            updates.priceMinorUnits < 0
        ) {
            throw new Error("priceMinorUnits must be a non-negative integer");
        }
    }

    if (updates.stockQuantity !== undefined) {
        if (
            typeof updates.stockQuantity !== "number" ||
            !Number.isInteger(updates.stockQuantity) ||
            updates.stockQuantity < 0
        ) {
            throw new Error("stockQuantity must be a non-negative integer");
        }
    }

    if (updates.tags !== undefined) {
        if (!Array.isArray(updates.tags) || updates.tags.length > 20) {
            throw new Error("tags must be an array of at most 20 items");
        }
    }
}

export function validateUpdateSemantic(
    updates:     ListingUpdateInput,
    listing:     FirebaseFirestore.DocumentData,
    sellerId:    string,
    isAdmin:     boolean,
    newShopData?: FirebaseFirestore.DocumentData,
): void {
    if (listing.sellerId !== sellerId && !isAdmin) {
        throw new Error("Unauthorized: you do not own this listing");
    }

    if (listing.status === ListingStatus.SUSPENDED && !isAdmin) {
        throw new Error("Suspended listings cannot be updated");
    }

    if (listing.status === ListingStatus.DELETED) {
        throw new Error("Deleted listings cannot be updated");
    }

    // Shop transfer: target shop must exist and belong to the same seller
    if (updates.shopId !== undefined && updates.shopId !== listing.shopId) {
        if (!newShopData) {
            throw new Error("Target shop not found");
        }
        if (newShopData.ownerId !== sellerId && !isAdmin) {
            throw new Error("Unauthorized: you do not own the target shop");
        }
        if (newShopData.isActive === false) {
            throw new Error("Cannot transfer listing to an inactive shop");
        }
    }
}

// ─── Restock validation ───────────────────────────────────────────────────────

export function validateRestockInput(input: ListingRestockInput): void {
    if (!input.listingId || typeof input.listingId !== "string") {
        throw new Error("listingId is required");
    }
    if (
        typeof input.quantity !== "number" ||
        !Number.isInteger(input.quantity) ||
        input.quantity <= 0
    ) {
        throw new Error("quantity must be a positive integer");
    }
}

export function validateRestockSemantic(
    listing:  FirebaseFirestore.DocumentData,
    sellerId: string,
    isAdmin:  boolean,
): void {
    if (listing.sellerId !== sellerId && !isAdmin) {
        throw new Error("Unauthorized: you do not own this listing");
    }
    if (listing.inventoryMode !== "STOCKED") {
        throw new Error(
            `Only STOCKED listings can be restocked. ` +
            `This listing uses inventoryMode: ${listing.inventoryMode}`
        );
    }
    if (
        listing.status === ListingStatus.SUSPENDED ||
        listing.status === ListingStatus.DELETED
    ) {
        throw new Error(`Cannot restock a listing in ${listing.status} status`);
    }
}

// ─── Action gates ─────────────────────────────────────────────────────────────

/**
 * Gate: can this listing be purchased via createPurchaseOrder?
 *
 * Checks:
 *  1. ListingType supports purchase
 *  2. Status is ACTIVE
 *  3. Stock is available (STOCKED mode)
 *
 * Replaces the inline Listing checks in commerce.ts createPurchaseOrder.
 * Throws a plain Error; caller wraps in HttpsError.
 */
export function assertPurchasable(
    listing:  FirebaseFirestore.DocumentData,
    quantity: number = 1,
): void {
    const type   = listing.listingType as ListingType;
    const status = listing.status as ListingStatus;

    // Guard: type must support purchase
    if (!LISTING_TYPE_WORKFLOWS[type]?.canPurchase) {
        throw new Error(
            `Listing type "${type}" does not support purchase. ` +
            `Use the appropriate action for this listing type.`
        );
    }

    // Guard: status must be actionable
    if (!ACTIONABLE_STATUSES.has(status)) {
        const hint = status === ListingStatus.OUT_OF_STOCK
            ? " — the seller has not restocked yet"
            : status === ListingStatus.PAUSED
            ? " — the seller has temporarily paused this listing"
            : "";
        throw new Error(`Listing is ${status} and cannot be purchased${hint}`);
    }

    // Guard: stock for STOCKED mode
    const inventoryMode = listing.inventoryMode as InventoryMode;
    if (inventoryMode === InventoryMode.STOCKED) {
        const available =
            (listing.stockQuantity || 0) - (listing.reservedQuantity || 0);
        if (available < quantity) {
            throw new Error(
                `"${listing.title}" has only ${available} unit(s) available ` +
                `(requested: ${quantity})`
            );
        }
    }
}

/**
 * Gate: can this listing be used as a delivery service via createDeliveryRequest?
 * Replaces the inline checks in logistics.ts.
 */
export function assertDeliverable(listing: FirebaseFirestore.DocumentData): void {
    const type   = listing.listingType as ListingType;
    const status = listing.status as ListingStatus;

    if (!LISTING_TYPE_WORKFLOWS[type]?.canRequestDelivery) {
        throw new Error(
            `Listing type "${type}" is not a delivery or transport service`
        );
    }

    if (!ACTIONABLE_STATUSES.has(status)) {
        throw new Error(`Delivery listing is "${status}" and is not available`);
    }
}

/**
 * Gate: is this listing in a valid state to have a commitment recorded?
 *
 * Called by verifyMopayPayment / wallet payment confirmation after an
 * order is confirmed. A listing can receive a commitment even if it has
 * since moved to OUT_OF_STOCK (the purchase happened while it was ACTIVE).
 * Only SUSPENDED/DELETED/ARCHIVED listings block commitment recording.
 */
export function assertCommittable(listing: FirebaseFirestore.DocumentData): void {
    const status = listing.status as ListingStatus;
    const blocked = new Set<ListingStatus>([
        ListingStatus.SUSPENDED,
        ListingStatus.DELETED,
        ListingStatus.ARCHIVED,
    ]);
    if (blocked.has(status)) {
        throw new Error(
            `Cannot record commitment on listing in ${status} status`
        );
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Resolve a raw string to a ListingType enum value.
 * Defaults to BUY if the value is absent or unrecognised.
 */
export function resolveListingType(raw?: string): ListingType {
    if (raw && Object.values(ListingType).includes(raw as ListingType)) {
        return raw as ListingType;
    }
    return ListingType.BUY;
}
