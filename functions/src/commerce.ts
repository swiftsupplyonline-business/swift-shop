import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
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
    normalizeSlug,
    // Lifecycle
    ListingStatus,
    isAvailableFromStatus,
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

// ─── calculateOrderFees ───────────────────────────────────────────────────────
// Unchanged: reads listing data for fee calculation only, no state mutations.
// Delivery listing availability check now uses status instead of isAvailable
// so it remains consistent with the engine's lifecycle model.

export const calculateOrderFees = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { items, requiresDelivery, deliveryAddress, selectedDeliveryListingId } = request.data;
    if (!items || !Array.isArray(items)) throw new HttpsError("invalid-argument", "Missing items");
    if (typeof requiresDelivery !== "boolean") throw new HttpsError("invalid-argument", "requiresDelivery is required");
    if (requiresDelivery && !deliveryAddress) throw new HttpsError("invalid-argument", "deliveryAddress is required when requiresDelivery is true");

    let subtotal = 0;
    let shopId = "";
    const db = admin.firestore();

    for (const item of items) {
        const listingDoc = await db.collection("listings").doc(item.listingId).get();
        if (!listingDoc.exists) {
            const itemTitle = item.title || "Unknown Item";
            throw new HttpsError("not-found", `Item "${itemTitle}" is no longer available. Please remove it from your cart.`);
        }
        const listing = listingDoc.data()!;
        subtotal += (listing.priceMinorUnits || 0) * (item.quantity || 1);

        if (!shopId) {
            shopId = listing.shopId;
        } else if (listing.shopId !== shopId) {
            throw new HttpsError("invalid-argument", "Multi-shop orders are not supported in this version.");
        }
    }

    let deliveryFee = 0;
    if (requiresDelivery) {
        if (selectedDeliveryListingId) {
            const deliveryListingDoc = await db.collection("listings").doc(selectedDeliveryListingId).get();
            if (!deliveryListingDoc.exists) throw new HttpsError("not-found", "Delivery listing not found");
            const deliveryListing = deliveryListingDoc.data()!;
            if (deliveryListing.listingType !== "DELIVER") throw new HttpsError("failed-precondition", "Invalid delivery listing type");
            // Use status as the authority; fall back to legacy isAvailable for docs written before the engine
            const isActive = deliveryListing.status
                ? deliveryListing.status === ListingStatus.ACTIVE
                : deliveryListing.isAvailable === true;
            if (!isActive) throw new HttpsError("failed-precondition", "Delivery service is currently unavailable");
            if (deliveryListing.shopId !== shopId) throw new HttpsError("invalid-argument", "Selected delivery provider does not belong to this shop.");
            deliveryFee = deliveryListing.priceMinorUnits || 0;
        } else {
            const deliverySnap = await db.collection("listings")
                .where("shopId", "==", shopId)
                .where("listingType", "==", "DELIVER")
                .where("isAvailable", "==", true)
                .get();
            if (deliverySnap.empty) throw new HttpsError("failed-precondition", "No delivery option is available for this shop.");
            deliveryFee = deliverySnap.docs[0].data().priceMinorUnits || 0;
        }
    }

    const platformFee = Math.floor((subtotal * 15) / 1000);
    const total = subtotal + deliveryFee + platformFee;

    return {
        subtotalMinorUnits: subtotal,
        deliveryFeeMinorUnits: deliveryFee,
        platformFeeMinorUnits: platformFee,
        totalMinorUnits: total,
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

// ─── createOrder ──────────────────────────────────────────────────────────────
// Engine integration:
//   assertPurchasable(listing, requestedQty) replaces inline isAvailable +
//   stock checks.  reserveInventory() replaces the inline transaction.update.
//   All other order/payment/ledger logic is unchanged.

export const createOrder = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { items, requiresDelivery, deliveryAddress, paymentMethod, provider, idempotencyKey, selectedDeliveryListingId, deliveryRequestId } = request.data;
    if (!items || !Array.isArray(items) || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Missing items or idempotencyKey");
    }
    if (typeof requiresDelivery !== "boolean") throw new HttpsError("invalid-argument", "requiresDelivery is required");
    if (requiresDelivery && !deliveryAddress) throw new HttpsError("invalid-argument", "deliveryAddress is required when requiresDelivery is true");
    if (requiresDelivery && !deliveryRequestId) throw new HttpsError("invalid-argument", "deliveryRequestId is required for orders requiring delivery");

    const db = admin.firestore();
    let orderId = "";
    let finalTotal = 0;

    try {
        orderId = await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
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

            let deliveryFee = 0;
            let finalDeliveryListingId = selectedDeliveryListingId;
            let drShopId = "";

            if (requiresDelivery) {
                const drRef = db.collection("deliveryRequests").doc(deliveryRequestId);
                const drDoc = await transaction.get(drRef);
                if (!drDoc.exists) throw new Error("Delivery request not found");
                const drData = drDoc.data()!;
                if (drData.status !== "ACCEPTED") throw new Error(`Delivery request status is ${drData.status}. Must be ACCEPTED.`);
                if (drData.requesterId !== auth.uid) throw new Error("Delivery request ownership mismatch");

                const drListingRef = db.collection("listings").doc(drData.listingId);
                const drListingSnap = await transaction.get(drListingRef);
                if (!drListingSnap.exists) throw new Error("Delivery listing not found");
                drShopId = drListingSnap.data()!.shopId as string;
                deliveryFee = drData.deliveryFeeMinorUnits || 0;
                finalDeliveryListingId = drData.listingId;
            }

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

            if (typeof drShopId !== "undefined" && drShopId !== shopId) {
                throw new Error(`Delivery request shop mismatch: request is for shop ${drShopId}, order is for shop ${shopId}`);
            }

            const platformFee = Math.floor((subtotal * 15) / 1000);
            const total = subtotal + deliveryFee + platformFee;
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

            const orderDoc = {
                id: newOrderId,
                buyerId: auth.uid,
                sellerId: sellerId,
                shopId: shopId,
                items: validatedItems,
                subtotalMinorUnits: subtotal,
                deliveryFeeMinorUnits: deliveryFee,
                platformFeeMinorUnits: platformFee,
                totalMinorUnits: total,
                currency: "LSL",
                status: orderStatus,
                inventoryStatus: "RESERVED",
                settlementStatus: paymentMethod === "SWIFT_WALLET" ? "ESCROW_HOLD" : "PENDING",
                paymentStatus: paymentMethod === "SWIFT_WALLET" ? "PAID" : "PENDING",
                requiresDelivery: requiresDelivery,
                deliveryRequestId: deliveryRequestId || null,
                selectedDeliveryListingId: finalDeliveryListingId || null,
                deliveryAddress: deliveryAddress || {},
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
                customerEmail: auth.token.email,
                customerName: auth.token.name || auth.uid,
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

// ─── verifyMopayPayment ───────────────────────────────────────────────────────
// Engine integration:
//   SUCCESS path: assertCommittable() then commitInventory() replace inline
//   stockQuantity-- / reservedQuantity-- mutations. commitInventory also
//   increments commitmentCount (first time this is wired).
//   FAILED/CANCELLED path: releaseInventory() replaces inline reservedQuantity-- .
//   All gateway verification, amount matching, ledger, and order status
//   transitions are unchanged.

export const verifyMopayPayment = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { sessionId } = request.data;
    if (!sessionId) throw new HttpsError("invalid-argument", "Missing sessionId");

    const db = admin.firestore();

    try {
        const orderQuery = await db.collection("orders")
            .where("mopaySessionId", "==", sessionId)
            .where("buyerId", "==", auth.uid)
            .limit(1)
            .get();

        if (orderQuery.empty) throw new Error("Order not found for this session.");

        const orderDoc = orderQuery.docs[0];
        const order = orderDoc.data();

        const mopaySession = await MopayClient.verifyPaymentSession(sessionId);
        if (!mopaySession) throw new Error("Could not verify session with MoPay.");

        if (mopaySession.reference !== order.id) throw new Error("Session reference mismatch.");

        const mopayAmountMinor = Math.round(mopaySession.amount * 100);
        if (mopayAmountMinor !== order.totalMinorUnits) {
            throw new Error(`Amount mismatch. Expected: ${order.totalMinorUnits}, Got: ${mopayAmountMinor}`);
        }

        if (mopaySession.transactionStatus === "SUCCESS") {
            await db.runTransaction(async (transaction) => {
                const freshOrderDoc = await transaction.get(orderDoc.ref);
                const freshOrder = freshOrderDoc.data()!;

                if (freshOrder.status === "CONFIRMED") return; // Idempotency
                if (freshOrder.status !== "RESERVED") {
                    throw new Error(`Order is in state ${freshOrder.status}, cannot confirm.`);
                }

                const now = admin.firestore.FieldValue.serverTimestamp();

                const resQuery = db.collection("reservations").where("orderId", "==", order.id);
                const resSnap = await transaction.get(resQuery);
                if (resSnap.empty) throw new Error("No reservations found for this order.");

                for (const resDoc of resSnap.docs) {
                    const reservation = resDoc.data();
                    if (reservation.status !== "ACTIVE") {
                        throw new Error(`Reservation for ${reservation.listingId} is ${reservation.status}. Cannot commit.`);
                    }

                    const listingRef = db.collection("listings").doc(reservation.listingId);
                    const listingSnap = await transaction.get(listingRef);

                    if (listingSnap.exists) {
                        const listing = listingSnap.data()!;
                        // ENGINE: guard — listing must be committable even if now OUT_OF_STOCK
                        assertCommittable(listing);
                        // ENGINE: commit stock + increment commitmentCount atomically
                        commitInventory(transaction, listingRef, listing, reservation.quantity, now);
                    }

                    transaction.update(resDoc.ref, { status: "COMMITTED", updatedAt: now });

                    // ENGINE: activity — purchase confirmed per listing
                    if (listingSnap.exists) {
                        const listing = listingSnap.data()!;
                        const { summary, metadata } = activityPurchaseConfirmed(
                            listing.title,
                            order.id,
                            reservation.quantity,
                            listing.priceMinorUnits * reservation.quantity,
                            "LSL"
                        );
                        recordActivity(
                            transaction, db,
                            reservation.listingId,
                            ListingActivityType.PURCHASE_CONFIRMED,
                            auth.uid, "buyer",
                            summary, metadata
                        );
                    }
                }

                // Ledger entry (unchanged)
                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    transactionId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                    debitAccount: "system_mopay_clearing",
                    creditAccount: "system_order_escrow",
                    amountMinorUnits: order.totalMinorUnits,
                    currency: "LSL",
                    reference: `ORDER_CONFIRM_MOPAY_${order.id}`,
                    timestamp: now
                });

                transaction.update(orderDoc.ref, {
                    status: "CONFIRMED",
                    paymentStatus: "SUCCESS",
                    inventoryStatus: "COMMITTED",
                    settlementStatus: "ESCROW_HOLD",
                    gatewayTransactionId: mopaySession.transactionId,
                    updatedAt: now
                });
            });

            return { status: "SUCCESS", orderId: order.id };

        } else if (
            mopaySession.transactionStatus === "FAILED" ||
            mopaySession.transactionStatus === "CANCELLED"
        ) {
            await db.runTransaction(async (transaction) => {
                const freshOrderDoc = await transaction.get(orderDoc.ref);
                const freshOrder = freshOrderDoc.data()!;
                if (freshOrder.status !== "RESERVED") return;

                const now = admin.firestore.FieldValue.serverTimestamp();
                const resQuery = db.collection("reservations").where("orderId", "==", order.id);
                const resSnap = await transaction.get(resQuery);

                for (const resDoc of resSnap.docs) {
                    const reservation = resDoc.data();
                    if (reservation.status !== "ACTIVE") continue;

                    const listingRef = db.collection("listings").doc(reservation.listingId);
                    const listingSnap = await transaction.get(listingRef);

                    if (listingSnap.exists) {
                        // ENGINE: release reservation and auto-promote if stock returns
                        releaseInventory(
                            transaction, listingRef,
                            listingSnap.data()!, reservation.quantity, now
                        );

                        // ENGINE: activity — purchase cancelled per listing
                        const listing = listingSnap.data()!;
                        const { summary, metadata } = activityPurchaseCancelled(
                            listing.title, order.id,
                            `Payment ${mopaySession.transactionStatus}`
                        );
                        recordActivity(
                            transaction, db,
                            reservation.listingId,
                            ListingActivityType.PURCHASE_CANCELLED,
                            auth.uid, "buyer",
                            summary, metadata
                        );
                    }
                    transaction.update(resDoc.ref, { status: "RELEASED", updatedAt: now });
                }

                transaction.update(orderDoc.ref, {
                    status: mopaySession.transactionStatus,
                    paymentStatus: mopaySession.transactionStatus,
                    inventoryStatus: "RELEASED",
                    updatedAt: now
                });
            });
        } else {
            await orderDoc.ref.update({
                paymentStatus: mopaySession.transactionStatus,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        }

        return { status: mopaySession.transactionStatus, orderId: order.id };

    } catch (error: any) {
        console.error("Payment verification failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});

// ─── cancelOrder ──────────────────────────────────────────────────────────────
// Engine integration:
//   releaseInventory() replaces inline reservedQuantity-- .
//   activityPurchaseCancelled recorded atomically.
//   Wallet refund and ledger logic unchanged.

export const cancelOrder = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, reason } = request.data;
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            if (order.buyerId !== auth.uid && order.sellerId !== auth.uid && !auth.token.admin) {
                throw new Error("Unauthorized");
            }

            if (order.status === "CANCELLED" || order.status === "REFUNDED") return;

            const cancelReason = reason || "User requested";
            const now = admin.firestore.FieldValue.serverTimestamp();

            // Wallet refund for confirmed wallet orders (unchanged)
            if (order.paymentMethod === "SWIFT_WALLET" && order.status === "CONFIRMED") {
                const walletRef = db.collection("wallets").doc(order.buyerId);
                const walletDoc = await transaction.get(walletRef);
                if (walletDoc.exists) {
                    const currentBalance = walletDoc.data()?.availableBalanceMinorUnits || 0;
                    transaction.update(walletRef, {
                        availableBalanceMinorUnits: currentBalance + order.totalMinorUnits,
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });
                    const ledgerId = db.collection("ledgerEntries").doc().id;
                    transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                        id: ledgerId,
                        debitAccount: "system_order_escrow",
                        creditAccount: `user_${order.buyerId}`,
                        amountMinorUnits: order.totalMinorUnits,
                        currency: "LSL",
                        reference: `ORDER_REFUND_${orderId}`,
                        timestamp: admin.firestore.FieldValue.serverTimestamp()
                    });
                }
            }

            // ENGINE: release reservations
            if (order.status === "RESERVED") {
                const cancelResQuery = db.collection("reservations").where("orderId", "==", orderId);
                const cancelResSnap = await transaction.get(cancelResQuery);

                for (const resDoc of cancelResSnap.docs) {
                    const reservation = resDoc.data();
                    if (reservation.status !== "ACTIVE") continue;

                    const listingRef = db.collection("listings").doc(reservation.listingId);
                    const listingSnap = await transaction.get(listingRef);

                    if (listingSnap.exists) {
                        releaseInventory(
                            transaction, listingRef,
                            listingSnap.data()!, reservation.quantity, now
                        );

                        // ENGINE: activity
                        const listing = listingSnap.data()!;
                        const { summary, metadata } = activityPurchaseCancelled(
                            listing.title, orderId, cancelReason
                        );
                        recordActivity(
                            transaction, db,
                            reservation.listingId,
                            ListingActivityType.PURCHASE_CANCELLED,
                            auth.uid,
                            order.buyerId === auth.uid ? "buyer" : "seller",
                            summary, metadata
                        );
                    }
                    transaction.update(resDoc.ref, { status: "RELEASED", updatedAt: now });
                }
            }

            transaction.update(orderRef, {
                status: "CANCELLED",
                cancelReason: cancelReason,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

// ─── createListing ────────────────────────────────────────────────────────────
// Engine integration:
//   validateCreateInput() + sanitiseClientPayload() replace ad-hoc key allowlist.
//   validateCreateSemantic() replaces inline shop ownership check.
//   resolveListingType() + inventoryModeForType() set server-authoritative type/mode.
//   generateUniqueListingSlug() replaces any future naive slug attempt.
//   applyCreationCounters() replaces inline listingCount / activeListingCount updates.
//   recordActivity(CREATED + PUBLISHED) written atomically.
//   Tier quota logic unchanged.

export const createListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    const rawInput = request.data;

    // ENGINE: fast-fail before any DB reads
    try {
        validateCreateInput(rawInput);
    } catch (e: any) {
        throw new HttpsError("invalid-argument", e.message);
    }

    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const shopRef = db.collection("shops").doc(rawInput.shopId);
            const shopDoc = await transaction.get(shopRef);
            if (!shopDoc.exists) throw new Error("Shop not found");
            const shop = shopDoc.data()!;

            // ENGINE: semantic ownership + shop-active check
            validateCreateSemantic(rawInput, shop, uid);

            const userDoc = await transaction.get(db.collection("users").doc(uid));
            if (!userDoc.exists) throw new Error("User not found");
            const tier = userDoc.data()!.tier || "BASIC";

            const profileRef = db.collection("profiles").doc(uid);
            const profileDoc = await transaction.get(profileRef);
            if (!profileDoc.exists) throw new Error("Profile not found");

            // Tier quota checks (unchanged)
            const entitlement = resolveEntitlement(tier);
            if (entitlement.includedListingsPerShop !== -1) {
                const shopListingsQuery = db.collection("listings")
                    .where("shopId", "==", rawInput.shopId)
                    .where("sellerId", "==", uid);
                const shopListingsSnapshot = await transaction.get(shopListingsQuery);
                if (shopListingsSnapshot.size >= entitlement.includedListingsPerShop) {
                    throw new Error("ADDITIONAL_FEE_REQUIRED");
                }
            }
            if (entitlement.totalIncludedListings !== -1) {
                const allListingsQuery = db.collection("listings").where("sellerId", "==", uid);
                const allListingsSnapshot = await transaction.get(allListingsQuery);
                if (allListingsSnapshot.size >= entitlement.totalIncludedListings) {
                    throw new Error("ADDITIONAL_FEE_REQUIRED");
                }
            }

            // ENGINE: sanitise + resolve type/mode
            const clientFields = sanitiseClientPayload(rawInput as Record<string, unknown>);
            const listingType  = resolveListingType(rawInput.listingType);
            const inventoryMode = inventoryModeForType(listingType);
            const stockQuantity = typeof rawInput.stockQuantity === "number"
                ? rawInput.stockQuantity
                : defaultStockForMode(inventoryMode);

            // ENGINE: collision-safe slug (async, before transaction close)
            const listingId = db.collection("listings").doc().id;
            const shareSlug = await generateUniqueListingSlug(db, rawInput.shopId, rawInput.title, listingId);

            // Lifecycle: new listings start ACTIVE (published immediately)
            // A future "save as draft" flow would pass status: DRAFT explicitly.
            const status    = ListingStatus.ACTIVE;
            const isAvailable = isAvailableFromStatus(status);
            const now       = admin.firestore.FieldValue.serverTimestamp();

            const newListing = {
                ...clientFields,
                id:               listingId,
                sellerId:         uid,
                listingType:      listingType,
                inventoryMode:    inventoryMode,
                stockQuantity:    stockQuantity,
                reservedQuantity: 0,
                status:           status,
                isAvailable:      isAvailable,
                isSponsored:      false,
                shareSlug:        shareSlug,
                slugAliases:      [],
                title_lowercase:  rawInput.title.toLowerCase(),
                commitmentCount:  0,
                likeCount:        0,
                bookmarkCount:    0,
                commentCount:     0,
                shareCount:       0,
                viewCount:        0,
                rankingScore:     0,
                publishedAt:      now,  // set immediately since we publish on creation
                createdAt:        now,
                updatedAt:        now,
            };

            transaction.set(db.collection("listings").doc(listingId), newListing);

            // ENGINE: counters
            applyCreationCounters(
                transaction, db,
                rawInput.shopId, uid,
                shop, profileDoc.data()!,
                status, now
            );

            // ENGINE: activity — CREATED and PUBLISHED in same transaction
            const { summary: cSummary, metadata: cMeta } = activityCreated(rawInput.title, listingType, rawInput.shopId);
            recordActivity(transaction, db, listingId, ListingActivityType.CREATED, uid, "seller", cSummary, cMeta);

            const { summary: pSummary, metadata: pMeta } = activityPublished(rawInput.title);
            recordActivity(transaction, db, listingId, ListingActivityType.PUBLISHED, uid, "seller", pSummary, pMeta);

            return listingId;
        });
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

// ─── updateListing ────────────────────────────────────────────────────────────
// Engine integration:
//   validateUpdateInput() + sanitiseClientPayload() replace ad-hoc key allowlist.
//   validateUpdateSemantic() replaces inline ownership + shop-transfer checks.
//   buildSlugUpdatePayload() handles slug rotation + alias retention on title change.
//   applyUpdateCounters() fixes the shop-transfer counter drift bug.
//   Activity recorded for price change, shop transfer, and general update.

export const updateListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId, updates } = request.data;
    const db = admin.firestore();

    try {
        validateUpdateInput(updates || {});
    } catch (e: any) {
        throw new HttpsError("invalid-argument", e.message);
    }

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(listingId);
            const listingDoc = await transaction.get(listingRef);
            if (!listingDoc.exists) throw new Error("Listing not found");
            const listing = listingDoc.data()!;

            const isAdmin = auth.token.admin === true;

            // Fetch new shop doc if a transfer is requested
            let newShopData: FirebaseFirestore.DocumentData | undefined;
            if (updates.shopId && updates.shopId !== listing.shopId) {
                const newShopDoc = await transaction.get(db.collection("shops").doc(updates.shopId));
                if (!newShopDoc.exists) throw new Error("Target shop not found");
                newShopData = newShopDoc.data();
            }

            // ENGINE: semantic validation (ownership, status, shop transfer auth)
            validateUpdateSemantic(updates, listing, auth.uid, isAdmin, newShopData);

            // ENGINE: sanitise client payload
            const filteredUpdates = sanitiseClientPayload(updates as Record<string, unknown>);

            // ENGINE: slug rotation if title changed
            const now = admin.firestore.FieldValue.serverTimestamp();
            if (filteredUpdates.title && filteredUpdates.title !== listing.title) {
                const slugPayload = await buildSlugUpdatePayload(
                    db, listingId,
                    updates.shopId || listing.shopId,
                    filteredUpdates.title as string,
                    listing.shareSlug || "",
                    listing.slugAliases || []
                );
                Object.assign(filteredUpdates, slugPayload);
            }

            // Derive new status from isAvailable if client passed it (legacy compat)
            // The engine's status is authoritative; isAvailable is derived from it.
            // If the client passes isAvailable, interpret it as a PAUSED/ACTIVE toggle.
            const oldStatus = listing.status as ListingStatus || (listing.isAvailable ? ListingStatus.ACTIVE : ListingStatus.PAUSED);
            let newStatus   = oldStatus;
            if (filteredUpdates.isAvailable === true  && oldStatus === ListingStatus.PAUSED) newStatus = ListingStatus.ACTIVE;
            if (filteredUpdates.isAvailable === false && oldStatus === ListingStatus.ACTIVE)  newStatus = ListingStatus.PAUSED;
            if (newStatus !== oldStatus) {
                filteredUpdates.status      = newStatus;
                filteredUpdates.isAvailable = isAvailableFromStatus(newStatus);
            }

            transaction.update(listingRef, {
                ...filteredUpdates,
                updatedAt: now
            });

            // ENGINE: counter corrections
            const oldShopId  = listing.shopId;
            const newShopId  = (updates.shopId as string) || oldShopId;
            let oldShopData: FirebaseFirestore.DocumentData | null = null;
            let profileData: FirebaseFirestore.DocumentData | null = null;

            if (newShopId !== oldShopId || newStatus !== oldStatus) {
                const oldShopDoc    = await transaction.get(db.collection("shops").doc(oldShopId));
                const profileDoc    = await transaction.get(db.collection("profiles").doc(listing.sellerId));
                oldShopData = oldShopDoc.exists ? oldShopDoc.data()! : null;
                profileData = profileDoc.exists ? profileDoc.data()! : null;
            }

            applyUpdateCounters(
                transaction, db, listing,
                oldStatus, newStatus,
                oldShopId, newShopId,
                oldShopData,
                newShopData || null,
                profileData,
                now
            );

            // ENGINE: activities
            if (
                updates.priceMinorUnits !== undefined &&
                updates.priceMinorUnits !== listing.priceMinorUnits
            ) {
                const { summary, metadata } = activityPriceChanged(
                    listing.title,
                    listing.priceMinorUnits || 0,
                    updates.priceMinorUnits,
                    "LSL"
                );
                recordActivity(transaction, db, listingId, ListingActivityType.PRICE_CHANGED, auth.uid, "seller", summary, metadata);
            }

            if (newShopId !== oldShopId) {
                const { summary, metadata } = activityShopTransferred(listing.title, oldShopId, newShopId);
                recordActivity(transaction, db, listingId, ListingActivityType.SHOP_TRANSFERRED, auth.uid, "seller", summary, metadata);
            }

            // General update activity (always)
            recordActivity(transaction, db, listingId, ListingActivityType.UPDATED, auth.uid, isAdmin ? "admin" : "seller", `Listing "${listing.title}" updated`, { updatedFields: Object.keys(filteredUpdates) });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

// ─── deleteListing ────────────────────────────────────────────────────────────
// Engine integration:
//   applyDeletionCounters() replaces inline listingCount / activeListingCount
//   mutations. Activity uses ARCHIVED (soft delete) rather than hard delete
//   semantics since the document is physically removed.

export const deleteListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId } = request.data;
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(listingId);
            const listingDoc = await transaction.get(listingRef);
            if (!listingDoc.exists) throw new Error("Listing not found");
            const listing = listingDoc.data()!;

            if (listing.sellerId !== auth.uid && !auth.token.admin) {
                throw new Error("Unauthorized");
            }

            const shopDoc  = await transaction.get(db.collection("shops").doc(listing.shopId));
            const profDoc  = await transaction.get(db.collection("profiles").doc(listing.sellerId));
            const now      = admin.firestore.FieldValue.serverTimestamp();

            // ENGINE: counter corrections before deleting the doc
            applyDeletionCounters(
                transaction, db, listing,
                shopDoc.exists ? shopDoc.data()! : null,
                profDoc.exists ? profDoc.data()! : null,
                now
            );

            // ENGINE: activity before deletion (doc is gone after this)
            recordActivity(
                transaction, db, listingId,
                ListingActivityType.ARCHIVED,
                auth.uid,
                auth.token.admin ? "admin" : "seller",
                `Listing "${listing.title}" deleted`,
                { shopId: listing.shopId }
            );

            transaction.delete(listingRef);
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

// ─── restockListing (NEW) ─────────────────────────────────────────────────────
// First implementation of the Seller Listing Action pattern.
// Validates → restocks → records activity, all in one transaction.

export const restockListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const input = request.data;
    try {
        validateRestockInput(input);
    } catch (e: any) {
        throw new HttpsError("invalid-argument", e.message);
    }

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const listingRef = db.collection("listings").doc(input.listingId);
            const listingDoc = await transaction.get(listingRef);
            if (!listingDoc.exists) throw new Error("Listing not found");
            const listing = listingDoc.data()!;

            const isAdmin = auth.token.admin === true;

            // ENGINE: semantic guard
            validateRestockSemantic(listing, auth.uid, isAdmin);

            const now        = admin.firestore.FieldValue.serverTimestamp();
            const currentStock = listing.stockQuantity || 0;

            // ENGINE: restock (auto-promotes OUT_OF_STOCK → ACTIVE if stock > 0)
            restockInventory(transaction, listingRef, listing, input.quantity, now);

            // ENGINE: activity
            const { summary, metadata } = activityRestocked(
                listing.title,
                input.quantity,
                currentStock + input.quantity
            );
            recordActivity(
                transaction, db, input.listingId,
                ListingActivityType.RESTOCKED,
                auth.uid, isAdmin ? "admin" : "seller",
                summary, metadata
            );
        });
        return { success: true };
    } catch (error: any) {
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
    const shop = request.data;
    const db   = admin.firestore();

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
                ...shop,
                id: shopId,
                ownerId: uid,
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
