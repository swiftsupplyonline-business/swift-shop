/**
 * LISTING ENGINE — builder.ts
 *
 * Sole place that assembles the initial document of a new listing. Server-owned fields
 * (seller, status, stock counters, engagement counters, slug) can never come from the client.
 */

import { ListingStatus, isAvailableFromStatus } from "./types";

export interface NewListingParams {
    clientFields:  Record<string, unknown>;
    listingId:     string;
    sellerId:      string;
    title:         string;
    listingType:   string;
    inventoryMode: string;
    stockQuantity: number;
    shareSlug:     string;
    now:           FirebaseFirestore.FieldValue;
}

export function buildNewListingDoc(p: NewListingParams): Record<string, unknown> {
    // New listings are published immediately; a future draft flow would pass DRAFT explicitly.
    const status = ListingStatus.ACTIVE;
    return {
        ...p.clientFields,
        id:               p.listingId,
        sellerId:         p.sellerId,
        listingType:      p.listingType,
        inventoryMode:    p.inventoryMode,
        stockQuantity:    p.stockQuantity,
        reservedQuantity: 0,
        status,
        isAvailable:      isAvailableFromStatus(status),
        isSponsored:      false,
        shareSlug:        p.shareSlug,
        slugAliases:      [],
        title_lowercase:  p.title.toLowerCase(),
        commitmentCount:  0,
        likeCount:        0,
        bookmarkCount:    0,
        commentCount:     0,
        shareCount:       0,
        viewCount:        0,
        rankingScore:     0,
        publishedAt:      p.now,
        createdAt:        p.now,
        updatedAt:        p.now,
    };
}
