/**
 * LISTING ENGINE — types.ts
 *
 * Canonical Firestore DTO for a Listing document.
 * This is the single source of truth for what a Listing looks like in
 * persistence. Kotlin domain model and Web DTOs are downstream of this.
 *
 * Field naming matches existing Firestore documents so no migration is
 * required for fields that already exist. New fields added by the engine
 * are nullable / optional to remain backward-compatible with documents
 * written before this module existed.
 *
 * LOCKED: Do not add fields here without a corresponding entry in the
 * allowedKeys whitelist in validate.ts and a counter-update in ownership.ts
 * if the field affects any counter.
 */

// ─── Enums ────────────────────────────────────────────────────────────────────

/**
 * The business capability a Listing represents.
 * Drives which workflow path is legal for this Listing.
 * Mirrors Kotlin ListingType enum exactly.
 */
export enum ListingType {
    BUY             = "BUY",
    MAKE_PAYMENT    = "MAKE_PAYMENT",
    SET_APPOINTMENT = "SET_APPOINTMENT",
    PLACE_ORDER     = "PLACE_ORDER",
    REGISTER        = "REGISTER",
    DELIVER         = "DELIVER",
    TAKE_ME_THERE   = "TAKE_ME_THERE",
}

/**
 * Full Listing lifecycle. Replaces the boolean `isAvailable` for any
 * new write path. `isAvailable` is preserved on the DTO for backward
 * compatibility with existing readers (Kotlin, Web) but is now derived
 * from `status` by the engine on every write.
 *
 *  DRAFT        → created but not yet published; invisible to buyers
 *  ACTIVE       → visible and actionable
 *  PAUSED       → seller-paused; visible but not actionable
 *  OUT_OF_STOCK → stock reached zero; visible but purchase blocked
 *  ARCHIVED     → soft-deleted by seller; hidden from discovery
 *  SUSPENDED    → admin action; hidden, non-actionable, flagged
 *  DELETED      → hard-delete pending; document will be removed
 */
export enum ListingStatus {
    DRAFT        = "DRAFT",
    ACTIVE       = "ACTIVE",
    PAUSED       = "PAUSED",
    OUT_OF_STOCK = "OUT_OF_STOCK",
    ARCHIVED     = "ARCHIVED",
    SUSPENDED    = "SUSPENDED",
    DELETED      = "DELETED",
}

/**
 * How inventory is tracked for this Listing.
 * STOCKED        → stockQuantity is finite and decremented on purchase
 * UNLIMITED      → always available regardless of stockQuantity value
 * PREORDER       → purchasable before stock arrives; ships when ready
 * SCHEDULED      → availability is time-gated (appointments, slots)
 * NOT_APPLICABLE → non-purchasable types (DELIVER, TAKE_ME_THERE, REGISTER)
 */
export enum InventoryMode {
    STOCKED        = "STOCKED",
    UNLIMITED      = "UNLIMITED",
    PREORDER       = "PREORDER",
    SCHEDULED      = "SCHEDULED",
    NOT_APPLICABLE = "NOT_APPLICABLE",
}

// ─── Sub-document types ───────────────────────────────────────────────────────

export interface ListingCustomField {
    id:         string;
    label:      string;
    type:       "text" | "number" | "select" | "multiselect" | "date" | "boolean";
    options:    string[];
    isRequired: boolean;
}

// ─── Firestore DTO ────────────────────────────────────────────────────────────

/**
 * Exact shape of a document in listings/{listingId}.
 *
 * Fields marked [SERVER] are owned by Cloud Functions and must never be
 * accepted from client payloads.
 *
 * Fields marked [CLIENT] are accepted from clients but validated server-side
 * before persistence.
 *
 * Fields marked [DERIVED] are computed from other fields on every write.
 *
 * Fields marked [OPTIONAL] did not exist on documents written before the
 * engine was introduced; always use nullish coalescing when reading them.
 */
export interface ListingDoc {
    // ── Identity [SERVER] ─────────────────────────────────────────────────────
    id:       string;
    shopId:   string; // set at creation; mutable via updateListing (with counter fix)
    sellerId: string; // set at creation; immutable

    // ── Classification [CLIENT] ───────────────────────────────────────────────
    listingType: ListingType;
    category:    string;
    tags:        string[];

    // ── Presentation [CLIENT] ─────────────────────────────────────────────────
    title:       string;
    description: string;
    imageUrls:   string[];
    images:      string[];   // legacy alias for imageUrls; preserved for old readers
    videoUrl:    string;

    // ── Commercial [CLIENT] ───────────────────────────────────────────────────
    priceMinorUnits:    number;
    priceCurrency:      string; // always "LSL" for now
    fulfillmentOptions: string[];

    // ── Inventory [CLIENT + SERVER] ───────────────────────────────────────────
    inventoryMode:    InventoryMode; // [SERVER] derived from listingType at creation
    stockQuantity:    number;        // [CLIENT]
    reservedQuantity: number;        // [SERVER] managed by inventory.ts only

    // ── Workflow [CLIENT] ─────────────────────────────────────────────────────
    customFields:         ListingCustomField[];
    durationMinutes:      number;   // SET_APPOINTMENT
    deliveryEstimateDays: number;   // BUY / PLACE_ORDER

    // ── Lifecycle [SERVER + DERIVED] ─────────────────────────────────────────
    status:      ListingStatus; // [SERVER] authoritative lifecycle state
    isAvailable: boolean;       // [DERIVED] true iff status === ACTIVE; kept for legacy readers

    // ── Timestamps [SERVER] ───────────────────────────────────────────────────
    createdAt:   FirebaseFirestore.FieldValue | FirebaseFirestore.Timestamp;
    updatedAt:   FirebaseFirestore.FieldValue | FirebaseFirestore.Timestamp;
    /** Set once when status first transitions DRAFT → ACTIVE. Never reset. [OPTIONAL] */
    publishedAt: FirebaseFirestore.FieldValue | FirebaseFirestore.Timestamp | null;

    // ── Discovery [SERVER] ───────────────────────────────────────────────────
    title_lowercase: string;      // [DERIVED] for case-insensitive search
    shareSlug:       string;      // [SERVER] collision-safe, set at creation
    slugAliases:     string[];    // [SERVER] prior slugs retained for redirect
    rankingScore:    number;      // [SERVER]
    isSponsored:     boolean;     // [SERVER]

    // ── Engagement counters [SERVER] ──────────────────────────────────────────
    //
    // These are append-only counters maintained by the engine.
    // Viewer-specific state (isLikedByMe, isBookmarkedByMe) is NOT stored
    // on the listing document — it is resolved per-viewer at read time.
    //
    likeCount:     number;
    bookmarkCount: number;
    commentCount:  number;
    /** Total share events recorded by the engine. [OPTIONAL, default 0] */
    shareCount:    number;
    /**
     * commitmentCount: customers who successfully completed this listing's
     * defined transaction. Incremented by the engine on payment confirmation,
     * appointment confirmation, registration acceptance, etc.
     * Display label is determined by ListingType (see COMMITMENT_LABELS).
     */
    commitmentCount: number;

    // ── Performance counters [SERVER, OPTIONAL] ───────────────────────────────
    //
    // These are written by the analytics pipeline, not by the commerce path.
    // All default to 0 if absent on older documents.
    //
    /** Qualifying Listing detail views (deduplicated by the analytics pipeline). */
    viewCount: number;
}

// ─── Commitment display labels ────────────────────────────────────────────────

/**
 * Human-readable label for commitmentCount per ListingType.
 * Used by Kotlin and Web to display "427 purchased", "184 appointments", etc.
 */
export const COMMITMENT_LABELS: Record<ListingType, string> = {
    [ListingType.BUY]:             "purchased",
    [ListingType.MAKE_PAYMENT]:    "payments made",
    [ListingType.SET_APPOINTMENT]: "appointments booked",
    [ListingType.PLACE_ORDER]:     "orders placed",
    [ListingType.REGISTER]:        "registrations",
    [ListingType.DELIVER]:         "deliveries completed",
    [ListingType.TAKE_ME_THERE]:   "trips completed",
};

// ─── Workflow routing table ───────────────────────────────────────────────────

/**
 * Which actions are legal for each ListingType.
 * Used by validate.ts to gate operations at the engine level.
 */
export const LISTING_TYPE_WORKFLOWS: Record<ListingType, {
    inventoryMode:      InventoryMode;
    canPurchase:        boolean;
    canBook:            boolean;
    canRequestDelivery: boolean;
    requiresStock:      boolean;
    priceRequired:      boolean;
    /** Whether a successful action increments commitmentCount. Always true for
     *  real transaction types; false only for passive types if any are added. */
    tracksCommitment:   boolean;
}> = {
    [ListingType.BUY]: {
        inventoryMode:      InventoryMode.STOCKED,
        canPurchase:        true,
        canBook:            false,
        canRequestDelivery: false,
        requiresStock:      true,
        priceRequired:      true,
        tracksCommitment:   true,
    },
    [ListingType.MAKE_PAYMENT]: {
        inventoryMode:      InventoryMode.UNLIMITED,
        canPurchase:        true,
        canBook:            false,
        canRequestDelivery: false,
        requiresStock:      false,
        priceRequired:      true,
        tracksCommitment:   true,
    },
    [ListingType.SET_APPOINTMENT]: {
        inventoryMode:      InventoryMode.SCHEDULED,
        canPurchase:        false,
        canBook:            true,
        canRequestDelivery: false,
        requiresStock:      false,
        priceRequired:      true,
        tracksCommitment:   true,
    },
    [ListingType.PLACE_ORDER]: {
        inventoryMode:      InventoryMode.UNLIMITED,
        canPurchase:        true,
        canBook:            false,
        canRequestDelivery: false,
        requiresStock:      false,
        priceRequired:      true,
        tracksCommitment:   true,
    },
    [ListingType.REGISTER]: {
        inventoryMode:      InventoryMode.NOT_APPLICABLE,
        canPurchase:        false,
        canBook:            false,
        canRequestDelivery: false,
        requiresStock:      false,
        priceRequired:      false,
        tracksCommitment:   true,
    },
    [ListingType.DELIVER]: {
        inventoryMode:      InventoryMode.NOT_APPLICABLE,
        canPurchase:        false,
        canBook:            false,
        canRequestDelivery: true,
        requiresStock:      false,
        priceRequired:      true,
        tracksCommitment:   true,
    },
    [ListingType.TAKE_ME_THERE]: {
        inventoryMode:      InventoryMode.NOT_APPLICABLE,
        canPurchase:        false,
        canBook:            false,
        canRequestDelivery: true,
        requiresStock:      false,
        priceRequired:      true,
        tracksCommitment:   true,
    },
};

// ─── Status helpers ───────────────────────────────────────────────────────────

/** Statuses under which a buyer can view the listing in search / feed. */
export const DISCOVERABLE_STATUSES = new Set<ListingStatus>([
    ListingStatus.ACTIVE,
    ListingStatus.PAUSED,
    ListingStatus.OUT_OF_STOCK,
]);

/** Statuses under which a purchase / booking / delivery action is allowed. */
export const ACTIONABLE_STATUSES = new Set<ListingStatus>([
    ListingStatus.ACTIVE,
]);

/** Derive legacy isAvailable from status. */
export function isAvailableFromStatus(status: ListingStatus): boolean {
    return status === ListingStatus.ACTIVE;
}

/** Derive InventoryMode from ListingType. Always use this; never hardcode. */
export function inventoryModeForType(type: ListingType): InventoryMode {
    return LISTING_TYPE_WORKFLOWS[type].inventoryMode;
}

/**
 * Default initial stock for a new listing by InventoryMode.
 * STOCKED listings get stock = 1 unless the seller specifies otherwise.
 * All other modes get 0 (stock is not meaningful for them).
 */
export function defaultStockForMode(mode: InventoryMode): number {
    return mode === InventoryMode.STOCKED ? 1 : 0;
}
