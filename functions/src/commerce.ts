import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { pickShopFields, ShopValidationError } from "./shopFields";
import { planDeliveryPayout, outcomeAtSettlement, DELIVERY_ESCROW_ACCOUNT, PLATFORM_FEES_ACCOUNT } from "./deliveryEconomics";
import { idempotencyDocId, parseOrderItems, OrderLine, MoneyValidationError } from "./moneyValidation";
import { assertAccountActive } from "./accountGuard";
import { resolveStatusUpdate } from "./orderTransitions";
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
    purchaseAndCommitInventory,
    returnCommittedInventory,
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

    const { items, paymentMethod, provider, customerEmail, customerName } = request.data;
    if (!items || !Array.isArray(items) || !request.data.idempotencyKey) {
        throw new HttpsError("invalid-argument", "Missing items or idempotencyKey");
    }
    if (paymentMethod !== "MOPAY" && paymentMethod !== "SWIFT_WALLET") {
        throw new HttpsError("invalid-argument", "Unsupported paymentMethod");
    }
    let orderLines: OrderLine[];
    try {
        orderLines = parseOrderItems(items);
    } catch (e: any) {
        if (e instanceof MoneyValidationError) throw new HttpsError("invalid-argument", e.message);
        throw e;
    }
    await assertAccountActive(auth.uid);
    // Namespaced per user: one buyer can no longer collide with, pre-claim, or read back another
    // buyer's order id by reusing their key.
    let idempotencyKey: string;
    try {
        idempotencyKey = idempotencyDocId(auth.uid, "purchase", request.data.idempotencyKey);
    } catch (e: any) {
        if (e instanceof MoneyValidationError) throw new HttpsError("invalid-argument", e.message);
        throw e;
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
            const isWallet = paymentMethod === "SWIFT_WALLET";

            // Phase 1: ALL reads. Firestore rejects any transaction read issued after a write.
            const listingRefs = orderLines.map((l) => db.collection("listings").doc(l.listingId));
            const listingSnaps = await transaction.getAll(...listingRefs);
            const walletRef = db.collection("wallets").doc(auth.uid);
            const walletDoc = isWallet ? await transaction.get(walletRef) : null;
            const firstShopId = listingSnaps[0] && listingSnaps[0].exists ? listingSnaps[0].data()!.shopId : undefined;
            const shopDoc = firstShopId ? await transaction.get(db.collection("shops").doc(firstShopId)) : null;

            // Phase 2: validate and write.
            for (let i = 0; i < orderLines.length; i++) {
                const { listingId, quantity: requestedQty } = orderLines[i];
                const listingRef = listingRefs[i];
                const listingDoc = listingSnaps[i];

                if (!listingDoc.exists) {
                    throw new Error("An item in your cart is no longer available. Please remove it from your cart.");
                }
                const listing = listingDoc.data()!;

                if (listing.sellerId === auth.uid) throw new Error("You cannot buy your own listing.");
                if ((listing.priceCurrency || "LSL") !== "LSL") throw new Error(`Listing "${listing.title}" is not priced in LSL.`);
                if (!Number.isSafeInteger(listing.priceMinorUnits) || listing.priceMinorUnits < 0) {
                    throw new Error(`Listing "${listing.title}" has an invalid price.`);
                }

                // ENGINE: single authoritative gate — checks type, status, and quantity-aware stock
                assertPurchasable(listing, requestedQty);

                // ENGINE: wallet orders are paid in this transaction, so stock is committed immediately
                // (nothing left ACTIVE for the expiry sweep to release); other orders reserve until paid.
                if (isWallet) {
                    purchaseAndCommitInventory(transaction, listingRef, listing, requestedQty, now);
                } else {
                    reserveInventory(transaction, listingRef, listing, requestedQty, now);
                }

                const resId = db.collection("reservations").doc().id;
                transaction.set(db.collection("reservations").doc(resId), {
                    id: resId,
                    orderId: newOrderId,
                    listingId,
                    quantity: requestedQty,
                    status: isWallet ? "COMMITTED" : "ACTIVE",
                    expiresAt: reservationExpiresAt,
                    createdAt: nowTimestamp
                });

                if (!shopId) {
                    shopId = listing.shopId;
                    sellerId = listing.sellerId;
                } else if (listing.shopId !== shopId) {
                    throw new Error("Multi-shop orders are not supported in this version.");
                }

                subtotal += listing.priceMinorUnits * requestedQty;

                validatedItems.push({
                    listingId,
                    title: listing.title,
                    quantity: requestedQty,
                    unitPriceMinorUnits: listing.priceMinorUnits,
                    unitPriceCurrency: "LSL"
                });
            }

            const platformFee = Math.floor((subtotal * 15) / 1000);
            const total = subtotal + platformFee;
            if (!Number.isSafeInteger(total) || total < 0) throw new Error("Invalid order total.");
            finalTotal = total;

            let orderStatus = "RESERVED";

            if (isWallet) {
                if (!walletDoc || !walletDoc.exists) throw new Error("Wallet not found");

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
            if (shopDoc && shopDoc.exists) {
                const shopData = shopDoc.data()!;
                if (typeof shopData.locationLat === "number" && typeof shopData.locationLng === "number") {
                    pickupSnapshot = { lat: shopData.locationLat, lng: shopData.locationLng, shopName: shopData.name || "" };
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
                inventoryStatus: paymentMethod === "SWIFT_WALLET" ? "COMMITTED" : "RESERVED",
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
            // A replayed key returns the existing order: reuse its payment session rather than opening
            // a second payable one (which let a user pay twice and be credited once).
            const existingSnap = await db.collection("orders").doc(orderId).get();
            const existing = existingSnap.data() || {};
            if (existing.buyerId !== auth.uid) throw new Error("Order not found");
            if (existing.mopaySessionId && existing.paymentUrl) {
                return { orderId, paymentUrl: existing.paymentUrl, mopaySessionId: existing.mopaySessionId };
            }
            if (existing.status !== "RESERVED") return { orderId };
            finalTotal = existing.totalMinorUnits;
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
            let lateRefund = false;
            await db.runTransaction(async (transaction) => {
                const freshOrderDoc = await transaction.get(orderDoc.ref);
                const freshOrder = freshOrderDoc.data()!;

                if (freshOrder.status === "CONFIRMED") return; // Idempotency
                if (freshOrder.status === "CANCELLED" || freshOrder.status === "FAILED") {
                    // Paid after the order was cancelled/expired: the order cannot be fulfilled (stock was
                    // released), so the money goes straight back to the buyer's Swift wallet.
                    if (freshOrder.refundStatus) { lateRefund = true; return; } // already refunded: repeat calls get the same message, never "success"
                    const buyerWalletRef = db.collection("wallets").doc(order.buyerId);
                    const buyerWalletDoc = await transaction.get(buyerWalletRef); // read before any write
                    const lateNow = admin.firestore.FieldValue.serverTimestamp();
                    const currentBalance = buyerWalletDoc.exists ? (buyerWalletDoc.data()?.availableBalanceMinorUnits || 0) : 0;
                    transaction.set(buyerWalletRef, {
                        availableBalanceMinorUnits: currentBalance + order.totalMinorUnits,
                        updatedAt: lateNow
                    }, { merge: true });
                    const lateLedgerId = db.collection("ledgerEntries").doc().id;
                    transaction.set(db.collection("ledgerEntries").doc(lateLedgerId), {
                        id: lateLedgerId,
                        transactionId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                        debitAccount: "system_mopay_clearing",
                        creditAccount: `user_${order.buyerId}`,
                        amountMinorUnits: order.totalMinorUnits,
                        currency: "LSL",
                        reference: `ORDER_LATE_PAYMENT_REFUND_${order.id}`,
                        timestamp: lateNow
                    });
                    transaction.set(db.collection("paymentRefunds").doc(order.id), {
                        orderId: order.id,
                        buyerId: order.buyerId,
                        amountMinorUnits: order.totalMinorUnits,
                        currency: "LSL",
                        mopaySessionId: sessionId,
                        gatewayTransactionId: mopaySession.transactionId || null,
                        reason: "PAID_AFTER_CANCEL",
                        status: "REFUNDED_TO_WALLET",
                        createdAt: lateNow
                    });
                    transaction.update(orderDoc.ref, {
                        paymentStatus: "SUCCESS_AFTER_CANCEL",
                        refundStatus: "REFUNDED_TO_WALLET",
                        gatewayTransactionId: mopaySession.transactionId || null,
                        updatedAt: lateNow
                    });
                    lateRefund = true;
                    return;
                }
                if (freshOrder.status !== "RESERVED") {
                    throw new Error(`Order is in state ${freshOrder.status}, cannot confirm.`);
                }

                const now = admin.firestore.FieldValue.serverTimestamp();

                const resQuery = db.collection("reservations").where("orderId", "==", order.id);
                const resSnap = await transaction.get(resQuery);
                if (resSnap.empty) throw new Error("No reservations found for this order.");

                const prefetchedListings = resSnap.docs.length ? await transaction.getAll(...resSnap.docs.map((d) => db.collection("listings").doc(d.data().listingId))) : [];

                for (let ri = 0; ri < resSnap.docs.length; ri++) {
                    const resDoc = resSnap.docs[ri];
                    const reservation = resDoc.data();
                    if (reservation.status !== "ACTIVE") {
                        throw new Error(`Reservation for ${reservation.listingId} is ${reservation.status}. Cannot commit.`);
                    }

                    const listingRef = db.collection("listings").doc(reservation.listingId);
                    const listingSnap = prefetchedListings[ri];

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

            if (lateRefund) {
                // The app treats a non-throwing result as "order placed", so tell the buyer plainly instead.
                throw new Error("Your payment arrived after the order expired, so the order was not placed. The full amount has been returned to your Swift wallet.");
            }
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

                const prefetchedListings = resSnap.docs.length ? await transaction.getAll(...resSnap.docs.map((d) => db.collection("listings").doc(d.data().listingId))) : [];

                for (let ri = 0; ri < resSnap.docs.length; ri++) {
                    const resDoc = resSnap.docs[ri];
                    const reservation = resDoc.data();
                    if (reservation.status !== "ACTIVE") continue;

                    const listingRef = db.collection("listings").doc(reservation.listingId);
                    const listingSnap = prefetchedListings[ri];

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
            throw new Error(`Payment ${String(mopaySession.transactionStatus).toLowerCase()}. No money was taken for this order.`);
        } else {
            await orderDoc.ref.update({
                paymentStatus: mopaySession.transactionStatus,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            throw new Error("Payment is still being processed. Please check again in a moment.");
        }

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
    if (typeof orderId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(orderId)) {
        throw new HttpsError("invalid-argument", "Invalid orderId");
    }
    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            const isAdmin = auth.token.admin === true;
            const isBuyer = order.buyerId === auth.uid;
            const isSeller = order.sellerId === auth.uid;
            if (!isBuyer && !isSeller && !isAdmin) throw new Error("Unauthorized");

            if (order.status === "CANCELLED" || order.status === "REFUNDED") return;

            // A settled order has already paid the seller: cancelling it would refund the buyer a
            // second time. Completed/failed orders are never cancellable.
            if (order.settlementStatus === "SETTLED" || order.fulfillmentStatus === "DELIVERED" ||
                ["DELIVERED", "COMPLETED", "FAILED"].includes(order.status)) {
                throw new Error("Orders cannot be cancelled after delivery");
            }
            // Successful payment ends normal cancellation. Post-payment problems use the complaint/dispute
            // mechanism rather than cancelOrder. This applies regardless of whether the order has settled.
            if (order.paymentStatus === "PAID" || order.paymentStatus === "SUCCESS") {
                throw new Error("Paid orders cannot be cancelled. Please use the complaint/dispute mechanism for post-payment problems.");
            }

            // Only unpaid orders in the normal pre-payment states may be cancelled by the buyer.
            if (isBuyer && !isSeller && !isAdmin && !["PENDING", "RESERVED"].includes(order.status)) {
                throw new Error("This order can no longer be cancelled by the buyer");
            }

            const refundWallet = false;
            const cancelReason = reason || "User requested";
            const now = admin.firestore.FieldValue.serverTimestamp();

            // Phase 1: ALL reads (Firestore rejects reads issued after a write).
            const resSnap = await transaction.get(db.collection("reservations").where("orderId", "==", orderId));
            const listingRefs = resSnap.docs.map((d) => db.collection("listings").doc(d.data().listingId));
            const listingSnaps = listingRefs.length ? await transaction.getAll(...listingRefs) : [];
            const walletRef = db.collection("wallets").doc(order.buyerId);
            const walletDoc = refundWallet ? await transaction.get(walletRef) : null;

            // Phase 2: writes.
            if (refundWallet) {
                const currentBalance = walletDoc && walletDoc.exists ? (walletDoc.data()?.availableBalanceMinorUnits || 0) : 0;
                // set+merge so a refund can never be dropped because the wallet doc is missing
                transaction.set(walletRef, {
                    availableBalanceMinorUnits: currentBalance + order.totalMinorUnits,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                }, { merge: true });
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

            for (let i = 0; i < resSnap.docs.length; i++) {
                const resDoc = resSnap.docs[i];
                const reservation = resDoc.data();
                if (reservation.status !== "ACTIVE" && reservation.status !== "COMMITTED") continue;

                const listingSnap = listingSnaps[i];
                if (listingSnap && listingSnap.exists) {
                    const listing = listingSnap.data()!;
                    if (reservation.status === "ACTIVE") {
                        // ENGINE: unpaid hold goes back to the available pool
                        releaseInventory(transaction, listingRefs[i], listing, reservation.quantity, now);
                    } else {
                        // ENGINE: paid stock goes back because the order is refunded
                        returnCommittedInventory(transaction, listingRefs[i], listing, reservation.quantity, now);
                    }
                    const { summary, metadata } = activityPurchaseCancelled(listing.title, orderId, cancelReason);
                    recordActivity(
                        transaction, db,
                        reservation.listingId,
                        ListingActivityType.PURCHASE_CANCELLED,
                        auth.uid,
                        isBuyer ? "buyer" : "seller",
                        summary, metadata
                    );
                }
                transaction.update(resDoc.ref, { status: "RELEASED", updatedAt: now });
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

            const now = admin.firestore.FieldValue.serverTimestamp();
            // ENGINE: sole assembler of the new listing document (server-owned fields)
            const newListing = buildNewListingDoc({
                clientFields, listingId, sellerId: uid, title: rawInput.title,
                listingType, inventoryMode, stockQuantity, shareSlug, now,
            });
            const status = newListing.status as ListingStatus;

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

            // ENGINE: legacy isAvailable flag -> PAUSED/ACTIVE toggle; status stays authoritative.
            const oldStatus = listing.status as ListingStatus || (listing.isAvailable ? ListingStatus.ACTIVE : ListingStatus.PAUSED);
            const newStatus = statusFromAvailabilityToggle(oldStatus, filteredUpdates.isAvailable);
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
            if (order.settlementStatus === "SETTLED") return; // idempotent repeat
            // Only a paid order can release escrow to the seller.
            if (!["SUCCESS", "PAID"].includes(order.paymentStatus)) throw new Error("Order has not been paid");
            if (order.settlementStatus !== "ESCROW_HOLD") throw new Error("Order has no escrow to release");
            if (!["READY", "DISPATCHED", "DELIVERED"].includes(order.status)) {
                throw new Error(`Order cannot be confirmed in state ${order.status}`);
            }

            const now = admin.firestore.Timestamp.now();
            const subtotal      = order.subtotalMinorUnits || 0;
            const deliveryFee   = order.deliveryFeeMinorUnits || 0;
            const platformFee   = order.platformFeeMinorUnits || 0;
            const total         = order.totalMinorUnits || 0;
            const sellerProceeds = subtotal;
            if (subtotal + deliveryFee + platformFee !== total) throw new Error("Order amounts are inconsistent; refusing to settle.");

            // The delivery fee belongs to the author of the delivery listing (snapshotted on the order).
            // Orders created before that snapshot existed fall back to paying the seller.
            const deliveryProviderId: string = deliveryFee > 0 ? (order.deliveryProviderId || order.sellerId) : "";
            const payouts = new Map<string, number>();
            payouts.set(order.sellerId, (payouts.get(order.sellerId) || 0) + sellerProceeds);
            if (deliveryFee > 0) payouts.set(deliveryProviderId, (payouts.get(deliveryProviderId) || 0) + deliveryFee);

            // ── Delivery-fee escrow (post-purchase delivery). READS ONLY in this block. ──────────
            // Released to provider/driver/platform if the job was DELIVERED, otherwise refunded to the buyer.
            let escrowRef: FirebaseFirestore.DocumentReference | null = null;
            let escrowDr: FirebaseFirestore.DocumentData | null = null;
            let escrowOutcome: "NONE" | "REFUND" | "RELEASE" = "NONE";
            let deliveryLines: ReturnType<typeof planDeliveryPayout> = [];
            if (order.deliveryRequestId) {
                const drRef = db.collection("deliveryRequests").doc(order.deliveryRequestId);
                const drSnap = await transaction.get(drRef);
                if (drSnap.exists) {
                    const routeSnap = order.fulfillmentId
                        ? await transaction.get(db.collection("deliveryRoutes").doc(order.fulfillmentId))
                        : null;
                    const dr = drSnap.data()!;
                    const route = routeSnap && routeSnap.exists ? routeSnap.data()! : null;
                    escrowOutcome = outcomeAtSettlement(dr.escrowStatus, route?.status);
                    if (escrowOutcome === "RELEASE") {
                        deliveryLines = planDeliveryPayout({
                            feeMinorUnits: dr.escrowAmountMinorUnits || 0,
                            providerId: dr.providerId || dr.merchantId,
                            driverId: route?.driverId || "",
                            driverShareBps: dr.driverShareBps,
                            platformFeePerMille: dr.platformFeePerMille,
                        });
                    }
                    if (escrowOutcome !== "NONE") { escrowRef = drRef; escrowDr = dr; }
                }
            }
            if (escrowOutcome === "REFUND" && escrowDr) {
                payouts.set(escrowDr.requesterId, (payouts.get(escrowDr.requesterId) || 0) + (escrowDr.escrowAmountMinorUnits || 0));
            }
            for (const line of deliveryLines) {
                if (line.uid) payouts.set(line.uid, (payouts.get(line.uid) || 0) + line.amountMinorUnits);
            }

            // All reads first: Firestore rejects any transaction read issued after a write.
            const walletRefs = new Map(Array.from(payouts.keys()).map((uid) => [uid, db.collection("wallets").doc(uid)]));
            const balances = new Map<string, number>();
            for (const [uid, ref] of walletRefs) {
                const snap = await transaction.get(ref);
                balances.set(uid, snap.exists ? (snap.data()?.availableBalanceMinorUnits || 0) : 0);
            }

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

            // set+merge: a recipient without a wallet doc yet must still get paid.
            for (const [uid, amount] of payouts) {
                transaction.set(walletRefs.get(uid)!, {
                    availableBalanceMinorUnits: (balances.get(uid) || 0) + amount,
                    updatedAt: now
                }, { merge: true });
            }

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

            if (deliveryFee > 0) {
                const deliveryLedgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(deliveryLedgerId), {
                    id: deliveryLedgerId,
                    debitAccount: "system_clearing",
                    creditAccount: `user_${deliveryProviderId}`,
                    amountMinorUnits: deliveryFee,
                    currency: "LSL",
                    reference: `DELIVERY_FEE_${orderId}`,
                    timestamp: now
                });
            }

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

            if (escrowRef && escrowDr) {
                const amount = escrowDr.escrowAmountMinorUnits || 0;
                if (escrowOutcome === "REFUND") {
                    const id = `delivery_refund_${escrowRef.id}`;
                    transaction.set(db.collection("ledgerEntries").doc(id), {
                        id, debitAccount: DELIVERY_ESCROW_ACCOUNT, creditAccount: `user_${escrowDr.requesterId}`,
                        amountMinorUnits: amount, currency: "LSL",
                        reference: `DELIVERY_REFUND_${escrowRef.id}`, reason: "DELIVERY_NOT_COMPLETED", timestamp: now
                    });
                    transaction.update(escrowRef, { escrowStatus: "REFUNDED", escrowRefundReason: "DELIVERY_NOT_COMPLETED", updatedAt: Date.now() });
                } else {
                    for (const line of deliveryLines) {
                        const id = `delivery_${line.kind.toLowerCase()}_${escrowRef.id}`;
                        transaction.set(db.collection("ledgerEntries").doc(id), {
                            id, debitAccount: DELIVERY_ESCROW_ACCOUNT,
                            creditAccount: line.uid ? `user_${line.uid}` : PLATFORM_FEES_ACCOUNT,
                            amountMinorUnits: line.amountMinorUnits, currency: "LSL",
                            reference: `${line.kind}_${escrowRef.id}`, timestamp: now
                        });
                    }
                    transaction.update(escrowRef, { escrowStatus: "RELEASED", escrowReleasedAt: Date.now(), updatedAt: Date.now() });
                }
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

export const updateOrderStatus = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, status } = request.data;
    if (typeof orderId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(orderId) || typeof status !== "string") {
        throw new HttpsError("invalid-argument", "Missing orderId or status");
    }

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            resolveStatusUpdate({
                isAdmin: auth.token.admin === true,
                isSeller: order.sellerId === auth.uid,
                isBuyer: order.buyerId === auth.uid,
                current: order.status,
                next: status,
                paymentStatus: order.paymentStatus,
                // Seller may hand the order over themselves for pickup orders or when they authored the
                // delivery listing; otherwise the courier's route sets DISPATCHED.
                sellerIsCourier: !order.requiresDelivery || order.deliveryProviderId === order.sellerId,
            });

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
                name_lowercase: String(shop.name ?? "").trim().toLowerCase(), // [DERIVED] shop search (Android searchShops)
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
        const derived = typeof updates.name === "string" ? { name_lowercase: updates.name.trim().toLowerCase() } : {};
        tx.update(ref, { ...updates, ...derived, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
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
