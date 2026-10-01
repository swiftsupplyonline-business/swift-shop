import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { pickShopFields, ShopValidationError } from "./shopFields";
import { MopayClient, MOPAY_API_KEY } from "./mopay";
import { resolveEntitlement } from "./entitlements";
import {
    // Validation
    validateCreateInput,
    validateCreateSemantic,
    validateUpdateInput,
    validateUpdateSemantic,
    validateRestockInput,
    validateRestockSemantic,
    sanitiseClientPayload,
    resolveListingType,
    assertPurchasable,
    assertCommittable,
    // Inventory
    reserveInventory,
    releaseInventory,
    commitInventory,
    restockInventory,
    // Slug
    generateUniqueListingSlug,
    buildSlugUpdatePayload,
    // Lifecycle
    ListingStatus,
    isAvailableFromStatus,
    statusFromAvailabilityToggle,
    buildNewListingDoc,
    inventoryModeForType,
    defaultStockForMode,
    // Ownership counters
    applyCreationCounters,
    applyDeletionCounters,
    applyUpdateCounters,
    // Activity log
    recordActivity,
    ListingActivityType,
    activityCreated,
    activityPublished,
    activityPriceChanged,
    activityRestocked,
    activityPurchaseConfirmed,
    activityPurchaseCancelled,
    activityShopTransferred,
} from "./listing";

export const calculatePurchaseTotal = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required");
    const { items } = request.data;
    if (!Array.isArray(items) || items.length === 0) {
        throw new HttpsError("invalid-argument", "items are required");
    }

    const db = admin.firestore();
    let subtotal = 0;
    let shopId = "";
    const validItems: any[] = [];

    for (const item of items) {
        const ref = db.collection("listings").doc(item.listingId);
        const snap = await ref.get();
        if (!snap.exists) throw new HttpsError("not-found", `Listing ${item.listingId} not found`);
        const listing = snap.data()!;
        const quantity = item.quantity || 1;
        assertPurchasable(listing, quantity);
        if (!shopId) shopId = listing.shopId || "";
        else if (listing.shopId !== shopId) {
            throw new HttpsError("invalid-argument", "Multi-shop orders are not supported in this version.");
        }
        subtotal += (listing.priceMinorUnits || 0) * quantity;
        validItems.push({
            listingId: item.listingId,
            title: listing.title || item.title || "",
            quantity,
            unitPriceMinorUnits: listing.priceMinorUnits || 0,
            unitPriceCurrency: listing.priceCurrency || "LSL"
        });
    }

    const platformFee = Math.floor((subtotal * 15) / 1000);
    return {
        items: validItems,
        subtotalMinorUnits: subtotal,
        platformFeeMinorUnits: platformFee,
        totalMinorUnits: subtotal + platformFee,
        currency: "LSL"
    };
});

// ─── createPurchaseOrder: product purchase only ───────────────────────────────
export const createPurchaseOrder = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { items, paymentMethod, provider, idempotencyKey, customerEmail, customerName } = request.data;
    if (!items || !Array.isArray(items) || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Missing items or idempotencyKey");
    }

    const db = admin.firestore();
    let orderId = "";
    let finalTotal = 0;

    try {
        orderId = await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("purchaseIdempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) {
                console.log(`Idempotent request for key ${idempotencyKey}. Returning existing orderId.`);
                return idempotencyDoc.data()?.orderId;
            }

            let subtotal = 0;
            const newOrderId = db.collection("orders").doc().id;
            const validatedItems = [];
            let shopId = "";
            let sellerId = "";

            // ── Validate items and reserve inventory via engine ────────────────
            const now = admin.firestore.FieldValue.serverTimestamp();
            const nowTimestamp = admin.firestore.Timestamp.now();
            const reservationExpiresAt = admin.firestore.Timestamp.fromMillis(
                nowTimestamp.toMillis() + 15 * 60 * 1000
            );

            for (const item of items) {
                const listingRef = db.collection("listings").doc(item.listingId);
                const listingDoc = await transaction.get(listingRef);

                if (!listingDoc.exists) {
                    const itemTitle = item.title || "Unknown Item";
                    throw new Error(`Item "${itemTitle}" is no longer available. Please remove it from your cart.`);
                }
                const listing = listingDoc.data()!;
                const requestedQty = item.quantity || 1;

                // ENGINE: single authoritative gate — checks type, status, and quantity-aware stock
                assertPurchasable(listing, requestedQty);

                // ENGINE: reserve inventory and auto-transition to OUT_OF_STOCK if needed
                reserveInventory(transaction, listingRef, listing, requestedQty, now);

                // Create reservation record (unchanged)
                const resId = db.collection("reservations").doc().id;
                transaction.set(db.collection("reservations").doc(resId), {
                    id: resId,
                    orderId: newOrderId,
                    listingId: item.listingId,
                    quantity: requestedQty,
                    status: "ACTIVE",
                    expiresAt: reservationExpiresAt,
                    createdAt: nowTimestamp
                });

                if (!shopId) {
                    shopId = listing.shopId;
                    sellerId = listing.sellerId;
                } else if (listing.shopId !== shopId) {
                    throw new Error("Multi-shop orders are not supported in this version.");
                }

                const itemTotal = listing.priceMinorUnits * requestedQty;
                subtotal += itemTotal;

                validatedItems.push({
                    listingId: item.listingId,
                    title: listing.title,
                    quantity: requestedQty,
                    unitPriceMinorUnits: listing.priceMinorUnits,
                    unitPriceCurrency: listing.priceCurrency || "LSL"
                });
            }

            const platformFee = Math.floor((subtotal * 15) / 1000);
            const total = subtotal + platformFee;
            finalTotal = total;

            let orderStatus = "RESERVED";

            if (paymentMethod === "SWIFT_WALLET") {
                const walletRef = db.collection("wallets").doc(auth.uid);
                const walletDoc = await transaction.get(walletRef);
                if (!walletDoc.exists) throw new Error("Wallet not found");

                const availableBalance = walletDoc.data()?.availableBalanceMinorUnits || 0;
                if (availableBalance < total) {
                    throw new Error(`Insufficient wallet balance. Required: ${total}, Available: ${availableBalance}`);
                }

                transaction.update(walletRef, {
                    availableBalanceMinorUnits: availableBalance - total,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    debitAccount: `user_${auth.uid}`,
                    creditAccount: "system_order_escrow",
                    amountMinorUnits: total,
                    currency: "LSL",
                    reference: `ORDER_PAY_${newOrderId}`,
                    timestamp: admin.firestore.FieldValue.serverTimestamp()
                });

                orderStatus = "CONFIRMED";
            }

            let pickupSnapshot: { lat: number; lng: number; shopName: string } | null = null;
            if (shopId) {
                const shopDoc = await transaction.get(db.collection("shops").doc(shopId));
                if (shopDoc.exists) {
                    const shopData = shopDoc.data()!;
                    if (typeof shopData.locationLat === "number" && typeof shopData.locationLng === "number") {
                        pickupSnapshot = { lat: shopData.locationLat, lng: shopData.locationLng, shopName: shopData.name || "" };
                    }
                }
            }

            const orderDoc = {
                id: newOrderId,
                buyerId: auth.uid,
                sellerId: sellerId,
                shopId: shopId,
                items: validatedItems,
                subtotalMinorUnits: subtotal,
                platformFeeMinorUnits: platformFee,
                totalMinorUnits: total,
                currency: "LSL",
                status: orderStatus,
                inventoryStatus: "RESERVED",
                settlementStatus: paymentMethod === "SWIFT_WALLET" ? "ESCROW_HOLD" : "PENDING",
                paymentStatus: paymentMethod === "SWIFT_WALLET" ? "PAID" : "PENDING",
                pickupSnapshot,
                fulfillmentStatus: "NOT_REQUESTED",
                paymentMethod: paymentMethod || "MOPAY",
                provider: provider || null,
                idempotencyKey: idempotencyKey,
                reservationExpiresAt: reservationExpiresAt,
                createdAt: nowTimestamp,
                updatedAt: nowTimestamp
            };

            transaction.set(db.collection("orders").doc(newOrderId), orderDoc);
            transaction.set(idempotencyRef, {
                orderId: newOrderId,
                userId: auth.uid,
                createdAt: nowTimestamp
            });

            return newOrderId;
        });

        if (paymentMethod === "MOPAY" && orderId) {
            const mopayRequest = {
                amount: finalTotal / 100,
                reference: orderId,
                redirectUrl: "swiftshop://checkout/verify",
                description: `Order ${orderId} at Swift Shop`,
                customerEmail: customerEmail || auth.token.email || "",
                customerName: customerName || auth.token.name || auth.uid,
            };

            const mopayResponse = await MopayClient.initiatePaymentSession(mopayRequest);

            if (mopayResponse.success && mopayResponse.sessionId) {
                await db.collection("orders").doc(orderId).update({
                    mopaySessionId: mopayResponse.sessionId,
                    paymentUrl: mopayResponse.paymentUrl,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                return {
                    orderId,
                    paymentUrl: mopayResponse.paymentUrl,
                    mopaySessionId: mopayResponse.sessionId
                };
            } else {
                console.error("MoPay Session Creation Failed:", mopayResponse.message);
                return {
                    orderId,
                    error: mopayResponse.message || "Failed to initiate payment gateway"
                };
            }
        }

        return { orderId };

    } catch (error: any) {
        console.error("Order creation failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});

// ─── Unchanged functions ──────────────────────────────────────────────────────
// confirmDelivery, updateOrderStatus, confirmMopayPayment, initiateSubscription,
// createShop, createListingComment, deleteListingComment
// No Listing Engine integration needed in Phase 3.

export const confirmDelivery = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId } = request.data;
    if (!orderId) throw new HttpsError("invalid-argument", "Missing orderId");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.buyerId !== auth.uid) throw new Error("Unauthorized");
            if (!["READY", "DISPATCHED", "DELIVERED"].includes(order.status)) {
                throw new Error(`Order cannot be confirmed in state ${order.status}`);
            }
            if (order.settlementStatus === "SETTLED") return;

            const now = admin.firestore.Timestamp.now();
            const subtotal      = order.subtotalMinorUnits || 0;
            const deliveryFee   = order.deliveryFeeMinorUnits || 0;
            const platformFee   = order.platformFeeMinorUnits || 0;
            const total         = order.totalMinorUnits || 0;
            const sellerProceeds = subtotal + deliveryFee;

            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId,
                debitAccount: "system_order_escrow",
                creditAccount: "system_clearing",
                amountMinorUnits: total,
                currency: "LSL",
                reference: `SETTLE_ORDER_${orderId}`,
                timestamp: now
            });

            const sellerWalletRef = db.collection("wallets").doc(order.sellerId);
            const sellerWalletDoc = await transaction.get(sellerWalletRef);
            const currentSellerBalance = sellerWalletDoc.data()?.availableBalanceMinorUnits || 0;
            transaction.update(sellerWalletRef, {
                availableBalanceMinorUnits: currentSellerBalance + sellerProceeds,
                updatedAt: now
            });

            const sellerLedgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(sellerLedgerId), {
                id: sellerLedgerId,
                debitAccount: "system_clearing",
                creditAccount: `user_${order.sellerId}`,
                amountMinorUnits: sellerProceeds,
                currency: "LSL",
                reference: `SALE_PROCEEDS_${orderId}`,
                timestamp: now
            });

            if (platformFee > 0) {
                const feeLedgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(feeLedgerId), {
                    id: feeLedgerId,
                    debitAccount: "system_clearing",
                    creditAccount: "system_fees",
                    amountMinorUnits: platformFee,
                    currency: "LSL",
                    reference: `PLATFORM_FEE_${orderId}`,
                    timestamp: now
                });
            }

            transaction.update(orderRef, {
                status: "DELIVERED",
                settlementStatus: "SETTLED",
                completedAt: now,
                updatedAt: now
            });
        });
        return { success: true };
    } catch (error: any) {
        console.error("Delivery confirmation failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});

const SELLER_TRANSITIONS: Record<string, string[]> = {
    CONFIRMED: ["PROCESSING"],
    PROCESSING: ["READY"]
};

export const updateOrderStatus = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, status } = request.data;
    if (!orderId || !status) throw new HttpsError("invalid-argument", "Missing orderId or status");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            const isAdmin  = auth.token.admin === true;
            const isSeller = order.sellerId === auth.uid;
            const isBuyer  = order.buyerId  === auth.uid;

            if (isBuyer && status === "CANCELLED") {
                if (!["PENDING", "RESERVED"].includes(order.status)) throw new Error("Buyer can only cancel pending or reserved orders");
            } else if (isSeller) {
                const allowedNext = SELLER_TRANSITIONS[order.status] || [];
                if (!allowedNext.includes(status)) {
                    throw new Error(`Seller cannot transition order from ${order.status} to ${status}`);
                }
            } else if (isAdmin) {
                // Admin authority preserved
            } else {
                throw new Error("Unauthorized status update");
            }

            transaction.update(orderRef, {
                status,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

export const confirmMopayPayment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth || !auth.token.admin) throw new HttpsError("permission-denied", "Admin only");

    const { orderId, paymentId } = request.data;
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.status !== "PENDING") throw new Error("Order not in PENDING state");

            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId,
                debitAccount: "system_mopay_clearing",
                creditAccount: "system_order_escrow",
                amountMinorUnits: order.totalMinorUnits,
                currency: "LSL",
                reference: `ORDER_CONFIRM_MOPAY_${orderId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(orderRef, {
                status: "CONFIRMED",
                paymentId,
                paymentStatus: "SUCCESS",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

export const initiateSubscription = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { targetTier } = request.data;
    if (!["PREMIUM", "ELITE"].includes(targetTier)) {
        throw new HttpsError("invalid-argument", "Invalid target tier");
    }

    const db = admin.firestore();
    const subscriptionId = db.collection("subscriptions").doc().id;

    await db.collection("subscriptions").doc(subscriptionId).set({
        id: subscriptionId,
        userId: auth.uid,
        tier: targetTier,
        status: "PENDING_PAYMENT",
        createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return subscriptionId;
});

export const createShop = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid  = auth.uid;
    const db   = admin.firestore();

    // Allowlist: clients can no longer self-assign isVerified / rating / counters / ownerId.
    let shop: Record<string, unknown>;
    try {
        shop = pickShopFields(request.data, { requireName: true });
    } catch (e: any) {
        throw new HttpsError("invalid-argument", e.message);
    }

    try {
        return await db.runTransaction(async (transaction) => {
            const userDoc = await transaction.get(db.collection("users").doc(uid));
            if (!userDoc.exists) throw new Error("User not found");
            const tier = userDoc.data()!.tier || "BASIC";

            const profileRef = db.collection("profiles").doc(uid);
            const profileDoc = await transaction.get(profileRef);
            if (!profileDoc.exists) throw new Error("Profile not found");
            const currentShopCount = profileDoc.data()!.shopCount || 0;

            const entitlement  = resolveEntitlement(tier);
            const shopsQuery   = db.collection("shops").where("ownerId", "==", uid);
            const shopsSnapshot = await transaction.get(shopsQuery);

            if (entitlement.maxShops !== -1 && shopsSnapshot.size >= entitlement.maxShops) {
                throw new Error(`Shop limit reached for ${tier} tier. Max: ${entitlement.maxShops}`);
            }

            const shopId = db.collection("shops").doc().id;
            transaction.set(db.collection("shops").doc(shopId), {
                isActive: true,
                ...shop,
                id: shopId,
                ownerId: uid,
                isVerified: false,
                rating: 0,
                reviewCount: 0,
                followerCount: 0,
                listingCount: 0,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            transaction.update(profileRef, {
                shopCount: currentShopCount + 1,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            return shopId;
        });
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Update a shop's profile. Replaces the client-side direct write that Firestore rules (correctly) deny.
 * Only the owner may update, and only allowlisted profile fields are accepted. The public share slug
 * is intentionally NOT regenerated on rename so already-shared /s/ and /d/ links keep working.
 */
export const updateShop = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const shopId = request.data?.shopId;
    if (typeof shopId !== "string" || !shopId) throw new HttpsError("invalid-argument", "shopId required");

    let updates: Record<string, unknown>;
    try {
        updates = pickShopFields(request.data?.updates, { requireName: false });
    } catch (e: any) {
        if (e instanceof ShopValidationError) throw new HttpsError("invalid-argument", e.message);
        throw e;
    }

    const db = admin.firestore();
    const ref = db.collection("shops").doc(shopId);
    await db.runTransaction(async (tx) => {
        const snap = await tx.get(ref);
        if (!snap.exists) throw new HttpsError("not-found", "Shop not found");
        if (snap.data()!.ownerId !== auth.uid) throw new HttpsError("permission-denied", "Only the shop owner can update this shop");
        tx.update(ref, { ...updates, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
    });
    return { success: true };
});

export const createListingComment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId, text, id: commentId } = request.data;
    if (!listingId || !text) throw new HttpsError("invalid-argument", "Missing listingId or text");

    const db  = admin.firestore();
    const uid = auth.uid;

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(listingId);
            const commentRef = db.collection("comments").doc(commentId || db.collection("comments").doc().id);

            const [listingDoc, commentDoc] = await Promise.all([
                transaction.get(listingRef),
                transaction.get(commentRef)
            ]);

            if (!listingDoc.exists) throw new Error("Listing not found");
            if (commentDoc.exists) return;

            const now = admin.firestore.FieldValue.serverTimestamp();

            transaction.set(commentRef, {
                id: commentRef.id,
                listingId,
                authorId: uid,
                authorName: request.data.authorName || "Anonymous",
                authorAvatarUrl: request.data.authorAvatarUrl || "",
                text,
                parentCommentId: request.data.parentCommentId || "",
                replyCount: 0,
                likeCount: 0,
                createdAt: now,
                updatedAt: now
            });

            transaction.update(listingRef, {
                commentCount: admin.firestore.FieldValue.increment(1),
                updatedAt: now
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

export const deleteListingComment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { commentId } = request.data;
    if (!commentId) throw new HttpsError("invalid-argument", "Missing commentId");

    const db  = admin.firestore();
    const uid = auth.uid;

    try {
        await db.runTransaction(async (transaction) => {
            const commentRef = db.collection("comments").doc(commentId);
            const commentDoc = await transaction.get(commentRef);

            if (!commentDoc.exists) return;

            const commentData = commentDoc.data()!;
            if (commentData.authorId !== uid && auth.token.admin !== true) {
                throw new Error("Unauthorized");
            }

            const listingId = commentData.listingId;
            if (!listingId) throw new Error("Comment is not associated with a Listing");

            const listingRef = db.collection("listings").doc(listingId);
            const listingDoc = await transaction.get(listingRef);

            transaction.delete(commentRef);

            if (listingDoc.exists) {
                const currentCount = listingDoc.data()?.commentCount || 0;
                transaction.update(listingRef, {
                    commentCount: Math.max(0, currentCount - 1),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});
