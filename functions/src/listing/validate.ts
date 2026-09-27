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
 */

import {
    ListingType,
    ListingStatus,
    InventoryMode,
    LISTING_TYPE_WORKFLOWS,
    ACTIONABLE_STATUSES,
    ListingDoc,
} from "./types";

// ─── Input types ──────────────────────────────────────────────────────────────

/** Fields accepted from a client at creation time. */
export interface ListingCreateInput {
    shopId:              string;
    title:               string;
    description?:        string;
    listingType?:        string;
    category?:           string;
    tags?:               string[];
    priceMinorUnits?:    number;
    priceCurrency?:      string;
    imageUrls?:          string[];
    images?:             string[];
    videoUrl?:           string;
    stockQuantity?:      number;
    deliveryEstimateDays?: number;
    durationMinutes?:    number;
    customFields?:       unknown[];
    fulfillmentOptions?: string[];
    isAvailable?:        boolean;
}

/** Fields accepted from a client at update time. Same keys as create. */
export type ListingUpdateInput = Partial<Omit<ListingCreateInput, "shopId">> & {
    shopId?: string; // allowed but triggers counter maintenance in ownership.ts
};

// ─── Allowed client-owned field keys ─────────────────────────────────────────

/**
 * Exhaustive list of fields a client may supply on create or update.
 * Any key not in this list is silently dropped before persistence.
 * SERVER-owned fields (id, sellerId, status, shareSlug, …) must never
 * appear here.
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
    "isAvailable",
] as const;

// ─── Field sanitiser ──────────────────────────────────────────────────────────

/**
 * Strip any key that is not in CLIENT_ALLOWED_KEYS.
 * Always call this before writing client-supplied data to Firestore.
 */
export function sanitiseClientPayload(raw: Record<string, unknown>): Record<string, unknown> {
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
 * Throws an Error with a human-readable message on failure.
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

    const type = resolveListingType(input.listingType);
    const workflow = LISTING_TYPE_WORKFLOWS[type];

    if (workflow.priceRequired) {
        const price = input.priceMinorUnits;
        if (typeof price !== "number" || !Number.isInteger(price) || price <= 0) {
            throw new Error(`priceMinorUnits must be a positive integer for listingType ${type}`);
        }
    }

    if (workflow.requiresStock) {
        const stock = input.stockQuantity;
        if (stock !== undefined) {
            if (typeof stock !== "number" || !Number.isInteger(stock) || stock < 0) {
                throw new Error("stockQuantity must be a non-negative integer");
            }
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

    if (input.deliveryEstimateDays !== undefined) {
        if (typeof input.deliveryEstimateDays !== "number" || input.deliveryEstimateDays < 0) {
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
    input:   ListingCreateInput,
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
        if (typeof updates.title !== "string" || updates.title.trim().length < 2) {
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
    updates:    ListingUpdateInput,
    listing:    FirebaseFirestore.DocumentData,
    sellerId:   string,
    isAdmin:    boolean,
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
        if (!newShopData) throw new Error("Target shop not found");
        if (newShopData.ownerId !== sellerId && !isAdmin) {
            throw new Error("Unauthorized: you do not own the target shop");
        }
        if (newShopData.isActive === false) {
            throw new Error("Cannot transfer listing to an inactive shop");
        }
    }
}

// ─── Action gates ─────────────────────────────────────────────────────────────

/**
 * Gate: can this listing be purchased via createOrder?
 * Throws if the listing state forbids a purchase action.
 */
export function assertPurchasable(listing: FirebaseFirestore.DocumentData): void {
    const type = listing.listingType as ListingType;
    const status = listing.status as ListingStatus;

    if (!LISTING_TYPE_WORKFLOWS[type]?.canPurchase) {
        throw new Error(
            `Listing type ${type} does not support purchase. ` +
            `Use the appropriate action for this listing type.`
        );
    }

    if (!ACTIONABLE_STATUSES.has(status)) {
        throw new Error(`Listing is ${status} and cannot be purchased`);
    }

    const inventoryMode = listing.inventoryMode as InventoryMode;
    if (inventoryMode === InventoryMode.STOCKED) {
        const available = (listing.stockQuantity || 0) - (listing.reservedQuantity || 0);
        if (available <= 0) {
            throw new Error(`Listing "${listing.title}" is out of stock`);
        }
    }
}

/**
 * Gate: can this listing be used as a delivery service via createDeliveryRequest?
 * Throws if not.
 */
export function assertDeliverable(listing: FirebaseFirestore.DocumentData): void {
    const type = listing.listingType as ListingType;
    const status = listing.status as ListingStatus;

    if (!LISTING_TYPE_WORKFLOWS[type]?.canRequestDelivery) {
        throw new Error(`Listing type ${type} is not a delivery or transport service`);
    }

    if (!ACTIONABLE_STATUSES.has(status)) {
        throw new Error(`Delivery listing is ${status} and is not available`);
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
