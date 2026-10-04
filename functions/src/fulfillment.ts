import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as admin from "firebase-admin";
import { isDeliveryStatus, canTransition } from "./fulfillmentStates";
import { assertAccountActive } from "./accountGuard";
import {
    DELIVERY_ESCROW_ACCOUNT, DEFAULT_DELIVERY_PLATFORM_FEE_PER_MILLE,
    isValidFee, sanitizeDriverShareBps, refundsOnRouteStatus, walletAfterRefund,
} from "./deliveryEconomics";

const DELIVERY_REQUEST_WINDOW_MS = 120_000;

// ─── Delivery escrow (buyer wallet -> system_delivery_escrow -> refund | release) ─────────────────
// Transaction discipline: every helper is split into a READ phase and a WRITE phase so callers can
// perform ALL reads before ANY write (Firestore rejects a read issued after a write).

interface RefundContext { walletRef: FirebaseFirestore.DocumentReference; balance: number; }

/** READ phase. Returns null when the request has no HELD escrow (nothing to refund). */
async function readRefundContext(
    tx: FirebaseFirestore.Transaction, db: FirebaseFirestore.Firestore, dr: FirebaseFirestore.DocumentData
): Promise<RefundContext | null> {
    if (dr.escrowStatus !== "HELD") return null;
    const walletRef = db.collection("wallets").doc(dr.requesterId);
    const snap = await tx.get(walletRef);
    return { walletRef, balance: snap.exists ? (snap.data()?.availableBalanceMinorUnits || 0) : 0 };
}

/** WRITE phase. Refunds the full escrow to the buyer's wallet. Idempotent via deterministic ledger id + escrowStatus. */
function writeRefund(
    tx: FirebaseFirestore.Transaction, db: FirebaseFirestore.Firestore,
    requestRef: FirebaseFirestore.DocumentReference, dr: FirebaseFirestore.DocumentData,
    ctx: RefundContext, reason: string, requestUpdate: Record<string, unknown>
) {
    const amount = dr.escrowAmountMinorUnits || 0;
    const now = Date.now();
    tx.set(ctx.walletRef, { availableBalanceMinorUnits: walletAfterRefund(ctx.balance, amount), updatedAt: now }, { merge: true });
    const ledgerId = `delivery_refund_${requestRef.id}`;
    tx.set(db.collection("ledgerEntries").doc(ledgerId), {
        id: ledgerId,
        debitAccount: DELIVERY_ESCROW_ACCOUNT,
        creditAccount: `user_${dr.requesterId}`,
        amountMinorUnits: amount,
        currency: dr.escrowCurrency || "LSL",
        reference: `DELIVERY_REFUND_${requestRef.id}`,
        reason,
        timestamp: now
    });
    tx.update(requestRef, { ...requestUpdate, escrowStatus: "REFUNDED", escrowRefundReason: reason, updatedAt: now });
}

/** Releases the order's one-active-request lock so the buyer can request delivery again. */
function clearOrderLock(tx: FirebaseFirestore.Transaction, db: FirebaseFirestore.Firestore, dr: FirebaseFirestore.DocumentData) {
    if (dr.relatedOrderId) {
        tx.update(db.collection("orders").doc(dr.relatedOrderId), { activeDeliveryRequestId: null, updatedAt: Date.now() });
    }
}

/** Returns delivery-provider listings without requiring an order or payment. */
export const getDeliveryOptions = onCall(async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Auth required");

    const db = admin.firestore();
    const snap = await db.collection("listings")
        .where("listingType", "==", "DELIVER")
        .limit(50)
        .get();

    return {
        options: snap.docs.filter(doc => doc.data().isAvailable === true).map(doc => {
            const d = doc.data();
            return {
                listingId: doc.id,
                shopId: d.shopId || "",
                sellerId: d.sellerId || "",
                title: d.title || "",
                priceMinorUnits: d.priceMinorUnits || 0,
                priceCurrency: d.priceCurrency || "LSL",
                deliveryEstimateDays: d.deliveryEstimateDays || 0
            };
        })
    };
});

/**
 * Creates a fulfillment request AFTER the product purchase exists.
 * It never changes the product order total, payment, inventory or settlement.
 */
export const createDeliveryRequest = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, listingId, dropoff } = request.data || {};
    if (typeof orderId !== "string" || !orderId || typeof listingId !== "string" || !listingId || !dropoff ||
        typeof dropoff.lat !== "number" || typeof dropoff.lng !== "number" ||
        !Number.isFinite(dropoff.lat) || !Number.isFinite(dropoff.lng) ||
        Math.abs(dropoff.lat) > 90 || Math.abs(dropoff.lng) > 180) {
        throw new HttpsError("invalid-argument", "orderId, listingId and dropoff ({lat, lng}) are required");
    }

    await assertAccountActive(auth.uid);

    const db = admin.firestore();
    const orderRef = db.collection("orders").doc(orderId);
    const listingRef = db.collection("listings").doc(listingId);
    const walletRef = db.collection("wallets").doc(auth.uid);
    const requestRef = db.collection("deliveryRequests").doc();

    try {
        return await db.runTransaction(async (tx) => {
            // ── ALL READS ─────────────────────────────────────────────────────────────
            const orderDoc = await tx.get(orderRef);
            const listingDoc = await tx.get(listingRef);
            const walletDoc = await tx.get(walletRef);
            if (!orderDoc.exists) throw new HttpsError("not-found", "Purchase order not found");
            if (!listingDoc.exists) throw new HttpsError("not-found", "Delivery listing not found");
            const order = orderDoc.data()!;
            const listing = listingDoc.data()!;

            let activeDoc: FirebaseFirestore.DocumentSnapshot | null = null;
            if (order.activeDeliveryRequestId) {
                activeDoc = await tx.get(db.collection("deliveryRequests").doc(order.activeDeliveryRequestId));
            }
            // Backward compatibility: requests created before the order lock existed.
            const legacyActive = await tx.get(
                db.collection("deliveryRequests").where("relatedOrderId", "==", orderId)
                    .where("status", "in", ["PENDING", "ACCEPTED"]).limit(1)
            );

            // ── VALIDATION (server is the only authority on fee / provider eligibility) ──
            if (order.buyerId !== auth.uid) throw new HttpsError("permission-denied", "Only the buyer can request fulfillment");
            if (order.paymentStatus !== "PAID" && order.paymentStatus !== "SUCCESS" && order.status !== "CONFIRMED") {
                throw new HttpsError("failed-precondition", "Product purchase must be paid before delivery is requested");
            }
            if (["CANCELLED", "REFUNDED", "FAILED"].includes(order.status)) {
                throw new HttpsError("failed-precondition", "Purchase order is no longer active");
            }
            if (order.fulfillmentId) throw new HttpsError("failed-precondition", "This purchase already has a delivery job");
            if (listing.listingType !== "DELIVER" || listing.isAvailable !== true) {
                throw new HttpsError("failed-precondition", "Delivery provider listing is unavailable");
            }
            if (!listing.sellerId) throw new HttpsError("failed-precondition", "Delivery listing has no provider");

            const fee = listing.priceMinorUnits ?? 0;
            if (!isValidFee(fee)) throw new HttpsError("failed-precondition", "Delivery listing has an invalid fee");
            const currency = listing.priceCurrency || "LSL";
            if (currency !== "LSL") throw new HttpsError("failed-precondition", "Delivery fee currency is not supported");

            const pickup = order.pickupSnapshot;
            if (!pickup || typeof pickup.lat !== "number" || typeof pickup.lng !== "number") {
                throw new HttpsError("failed-precondition", "Purchase order has no valid pickup location");
            }

            const hasActive = (activeDoc?.exists && ["PENDING", "ACCEPTED"].includes(activeDoc.data()!.status)) || !legacyActive.empty;
            if (hasActive) throw new HttpsError("failed-precondition", "This purchase already has an active delivery request");

            const balance = walletDoc.exists ? (walletDoc.data()?.availableBalanceMinorUnits || 0) : 0;
            if (fee > 0 && balance < fee) {
                throw new HttpsError("failed-precondition", `Insufficient wallet balance for the delivery fee. Required: ${fee}, Available: ${balance}`);
            }

            // ── WRITES ────────────────────────────────────────────────────────────────
            const now = Date.now();
            if (fee > 0) {
                tx.update(walletRef, { availableBalanceMinorUnits: balance - fee, updatedAt: now });
                const ledgerId = `delivery_hold_${requestRef.id}`;
                tx.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    debitAccount: `user_${auth.uid}`,
                    creditAccount: DELIVERY_ESCROW_ACCOUNT,
                    amountMinorUnits: fee,
                    currency,
                    reference: `DELIVERY_HOLD_${requestRef.id}`,
                    timestamp: now
                });
            }

            tx.set(requestRef, {
                id: requestRef.id,
                listingId,
                merchantId: listing.sellerId,          // provider = delivery listing author
                providerId: listing.sellerId,
                merchandiseShopId: order.shopId || "",
                requesterId: auth.uid,
                relatedOrderId: orderId,
                pickup,
                dropoff,
                pickupLabel: pickup.shopName || "Merchant Shop",
                dropoffLabel: "",
                deliveryFeeMinorUnits: fee,
                deliveryFeeCurrency: currency,
                paymentMethod: "SWIFT_WALLET",
                escrowStatus: fee > 0 ? "HELD" : "NONE",
                escrowAmountMinorUnits: fee,
                escrowCurrency: currency,
                driverShareBps: sanitizeDriverShareBps(listing.driverShareBps),
                platformFeePerMille: DEFAULT_DELIVERY_PLATFORM_FEE_PER_MILLE,
                status: "PENDING",
                expiresAt: now + DELIVERY_REQUEST_WINDOW_MS,
                createdAt: now,
                updatedAt: now
            });
            // Single-document lock: two concurrent requests for one order contend on this write.
            tx.update(orderRef, { activeDeliveryRequestId: requestRef.id, updatedAt: now });

            return { requestId: requestRef.id, deliveryFeeMinorUnits: fee, currency, escrowStatus: fee > 0 ? "HELD" : "NONE" };
        });
    } catch (e: any) {
        if (e instanceof HttpsError) throw e;
        throw new HttpsError("failed-precondition", e.message);
    }
});

export const acceptDeliveryRequest = onCall(async (request) => {
    return respond(request, true);
});

export const declineDeliveryRequest = onCall(async (request) => {
    return respond(request, false);
});

async function respond(request: any, accept: boolean) {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { requestId } = request.data;
    if (!requestId) throw new HttpsError("invalid-argument", "requestId is required");

    const db = admin.firestore();
    try {
        await db.runTransaction(async tx => {
            const ref = db.collection("deliveryRequests").doc(requestId);
            const snap = await tx.get(ref);
            if (!snap.exists) throw new Error("Delivery request not found");
            const d = snap.data()!;

            if (d.merchantId !== auth.uid) throw new Error("Only the requested provider can respond");
            if (d.status !== "PENDING") throw new Error(`Cannot respond to request in status ${d.status}`);
            if (accept && d.expiresAt <= Date.now()) throw new Error("Delivery request has expired");

            if (accept) {
                tx.update(ref, { status: "ACCEPTED", updatedAt: Date.now() });
                return;
            }
            // Decline: release the buyer's escrow and the order lock.
            const ctx = await readRefundContext(tx, db, d);
            if (ctx) writeRefund(tx, db, ref, d, ctx, "PROVIDER_DECLINED", { status: "DECLINED" });
            else tx.update(ref, { status: "DECLINED", updatedAt: Date.now() });
            clearOrderLock(tx, db, d);
        });
        return { success: true, status: accept ? "ACCEPTED" : "DECLINED" };
    } catch (e: any) {
        throw new HttpsError("failed-precondition", e.message);
    }
}

export const cancelDeliveryRequest = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { requestId } = request.data || {};
    if (typeof requestId !== "string" || !requestId) throw new HttpsError("invalid-argument", "requestId is required");

    const db = admin.firestore();
    try {
        await db.runTransaction(async tx => {
            const ref = db.collection("deliveryRequests").doc(requestId);
            const snap = await tx.get(ref);
            if (!snap.exists) throw new Error("Delivery request not found");
            const d = snap.data()!;
            if (d.requesterId !== auth.uid) throw new Error("Only the requester can cancel");
            // Cancellation rule: allowed until a fulfillment job exists; after that, use the job cancel path.
            const cancellable = d.status === "PENDING" || (d.status === "ACCEPTED" && d.assignmentStatus !== "JOB_CREATED");
            if (!cancellable) throw new Error(`Cannot cancel request in status ${d.status}`);

            const ctx = await readRefundContext(tx, db, d);
            if (ctx) writeRefund(tx, db, ref, d, ctx, "BUYER_CANCELLED", { status: "CANCELLED" });
            else tx.update(ref, { status: "CANCELLED", updatedAt: Date.now() });
            clearOrderLock(tx, db, d);
        });
        return { success: true };
    } catch (e: any) {
        throw new HttpsError("failed-precondition", e.message);
    }
});

/**
 * Converts an accepted delivery request into a fulfillment job.
 * This is deliberately separate from product purchase creation/payment.
 */
export const createDeliveryJob = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { requestId } = request.data || {};
    if (typeof requestId !== "string" || !requestId) throw new HttpsError("invalid-argument", "requestId is required");

    const db = admin.firestore();

    try {
        // The whole check-then-create runs in ONE transaction (reads first, then writes) so a concurrent
        // cancel/decline/expiry cannot slip between validation and job creation.
        return await db.runTransaction(async tx => {
            const requestRef = db.collection("deliveryRequests").doc(requestId);
            const requestSnap = await tx.get(requestRef);
            if (!requestSnap.exists) throw new HttpsError("not-found", "Delivery request not found");
            const dr = requestSnap.data()!;

            if (dr.requesterId !== auth.uid) throw new HttpsError("permission-denied", "Only the buyer can create this fulfillment job");
            if (!dr.relatedOrderId) throw new Error("Delivery request is not attached to a purchase order");

            // One order -> one job; the job id is the order id (deliveryRoutes/{orderId}).
            const routeRef = db.collection("deliveryRoutes").doc(dr.relatedOrderId);
            const orderRef = db.collection("orders").doc(dr.relatedOrderId);
            const [routeSnap, orderSnap, earlier] = await Promise.all([
                tx.get(routeRef),
                tx.get(orderRef),
                // Stored-data compatibility: jobs created before the order-id convention used random ids.
                tx.get(db.collection("deliveryRoutes").where("orderId", "==", dr.relatedOrderId).limit(1)),
            ]);
            if (routeSnap.exists) return { routeId: routeRef.id, alreadyExists: true };
            if (!earlier.empty) return { routeId: earlier.docs[0].id, alreadyExists: true };

            if (dr.status !== "ACCEPTED") throw new Error("Delivery request must be accepted first");
            if (!orderSnap.exists) throw new Error("Purchase order not found");
            const order = orderSnap.data()!;
            if (order.buyerId !== auth.uid) throw new Error("Purchase order does not belong to this buyer");
            if (["CANCELLED", "REFUNDED", "FAILED"].includes(order.status)) throw new Error("Purchase order is no longer active");
            // The delivery fee must be escrowed before a job can exist (free deliveries have escrowStatus NONE).
            if ((dr.deliveryFeeMinorUnits || 0) > 0 && dr.escrowStatus !== "HELD") {
                throw new Error("Delivery fee has not been escrowed for this request");
            }

            tx.set(routeRef, {
                id: routeRef.id,
                orderId: dr.relatedOrderId,
                buyerId: order.buyerId,
                sellerId: order.sellerId,
                providerId: dr.merchantId,
                driverId: "",
                pickupLat: dr.pickup.lat,
                pickupLng: dr.pickup.lng,
                dropoffLat: dr.dropoff.lat,
                dropoffLng: dr.dropoff.lng,
                deliveryListingId: dr.listingId,
                deliveryRequestId: requestRef.id,
                deliveryFeeMinorUnits: dr.deliveryFeeMinorUnits || 0,
                status: "REQUESTED",
                distanceMeters: 0,
                estimatedMinutes: 0,
                conversationId: "",
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            tx.update(requestRef, {
                assignmentStatus: "JOB_CREATED",
                updatedAt: Date.now()
            });

            tx.update(orderRef, {
                fulfillmentId: routeRef.id,
                deliveryRequestId: requestRef.id,
                fulfillmentStatus: "REQUESTED",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            return { routeId: routeRef.id, alreadyExists: false };
        });
    } catch (e: any) {
        if (e instanceof HttpsError) throw e;
        throw new HttpsError("failed-precondition", e.message);
    }
});

export const updateDeliveryStatus = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { routeId, status } = request.data;
    if (!routeId || !isDeliveryStatus(status)) {
        throw new HttpsError("invalid-argument", "routeId and valid status are required");
    }

    const db = admin.firestore();
    try {
        await db.runTransaction(async tx => {
            const routeRef = db.collection("deliveryRoutes").doc(routeId);
            const snap = await tx.get(routeRef);
            if (!snap.exists) throw new Error("Fulfillment job not found");
            const route = snap.data()!;

            const isAdmin = auth.token.admin === true;
            const isDriver = route.driverId === auth.uid;
            const isBuyer = route.buyerId === auth.uid;
            const isSeller = route.sellerId === auth.uid;

            if (!canTransition(route.status, status)) {
                throw new Error(`Cannot transition delivery from ${route.status} to ${status}`);
            }

            if (status === "ASSIGNED" && route.status === "REQUESTED") {
                if (auth.token.role !== "DRIVER" && !isAdmin) throw new Error("Only a driver can claim a delivery");
                if (route.driverId) throw new Error("Delivery already assigned");

                if (!isAdmin && route.providerId !== auth.uid) {
                    const memberRef = db.collection("deliveryProviders").doc(route.providerId).collection("drivers").doc(auth.uid);
                    const member = await tx.get(memberRef);
                    if (!member.exists || member.data()?.authorized !== true) {
                        throw new Error("Driver is not authorized by this provider");
                    }
                }

                tx.update(routeRef, {
                    driverId: auth.uid,
                    status: "ASSIGNED",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
                return;
            }

            if (status === "CANCELLED") {
                if (!isBuyer && !isDriver && !isAdmin) throw new Error("Unauthorized");
                if (isBuyer && route.status !== "REQUESTED") throw new Error("Buyer can only cancel before assignment");
            } else if (status === "PICKUP_CONFIRMED") {
                // Physical handoff: confirmed by the assigned driver or the merchandise seller.
                if (!isDriver && !isSeller) throw new Error("Only driver or merchant can confirm pickup");
            } else if (!isDriver && !isAdmin) {
                throw new Error("Only the assigned driver can update this delivery");
            }

            // READ phase for escrow refund (a cancelled or failed job never earns the delivery fee).
            let refund: { reqRef: FirebaseFirestore.DocumentReference; dr: FirebaseFirestore.DocumentData; ctx: RefundContext } | null = null;
            if (refundsOnRouteStatus(status) && route.deliveryRequestId) {
                const reqRef = db.collection("deliveryRequests").doc(route.deliveryRequestId);
                const reqSnap = await tx.get(reqRef);
                if (reqSnap.exists) {
                    const ctx = await readRefundContext(tx, db, reqSnap.data()!);
                    if (ctx) refund = { reqRef, dr: reqSnap.data()!, ctx };
                }
            }

            tx.update(routeRef, {
                status,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            const fulfillmentStatus =
                status === "DELIVERED" ? "DELIVERED" :
                status === "FAILED" ? "FAILED" :
                status === "CANCELLED" ? "CANCELLED" :
                status;

            const orderUpdate: Record<string, unknown> = {
                fulfillmentStatus,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };
            // Keep the order lifecycle in step (buyer confirm-delivery depends on DISPATCHED/DELIVERED).
            if (status === "IN_TRANSIT") orderUpdate.status = "DISPATCHED";
            if (status === "DELIVERED") orderUpdate.status = "DELIVERED";
            tx.update(db.collection("orders").doc(route.orderId), orderUpdate);

            if (refund) {
                writeRefund(tx, db, refund.reqRef, refund.dr, refund.ctx,
                    status === "FAILED" ? "DELIVERY_FAILED" : "JOB_CANCELLED", {});
            }
        });

        return { success: true };
    } catch (e: any) {
        throw new HttpsError("failed-precondition", e.message);
    }
});

export const authorizeDriver = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { driverUid, authorized } = request.data;
    if (!driverUid || typeof authorized !== "boolean") {
        throw new HttpsError("invalid-argument", "driverUid and authorized are required");
    }

    await admin.firestore()
        .collection("deliveryProviders").doc(auth.uid)
        .collection("drivers").doc(driverUid)
        .set({
            authorized,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

    return { success: true };
});

/**
 * Scheduled: expire delivery requests that the provider never answered.
 * Canonical replacement for logistics.ts expireDeliveryRequests.
 * Processes at most 400 per run (Firestore batch limit is 500); the remainder is picked up next minute.
 */
/** Expires ONE stale request and refunds its escrow atomically. Exported for tests. */
export async function expireDeliveryRequest(db: FirebaseFirestore.Firestore, requestId: string): Promise<boolean> {
    return db.runTransaction(async (tx) => {
        const ref = db.collection("deliveryRequests").doc(requestId);
        const snap = await tx.get(ref);
        if (!snap.exists) return false;
        const d = snap.data()!;
        // Re-check inside the transaction: it may have been accepted/declined/cancelled meanwhile.
        if (d.status !== "PENDING" || !(d.expiresAt <= Date.now())) return false;
        const ctx = await readRefundContext(tx, db, d);
        if (ctx) writeRefund(tx, db, ref, d, ctx, "REQUEST_EXPIRED", { status: "EXPIRED" });
        else tx.update(ref, { status: "EXPIRED", updatedAt: Date.now() });
        clearOrderLock(tx, db, d);
        return true;
    });
}

/**
 * Scheduled: expire delivery requests the provider never answered and refund their escrow.
 * One transaction per request (so a failure cannot leave money held); at most 100 per run.
 */
export const expireDeliveryRequests = onSchedule("every 1 minutes", async () => {
    const db = admin.firestore();
    const stale = await db.collection("deliveryRequests")
        .where("status", "==", "PENDING")
        .where("expiresAt", "<=", Date.now())
        .limit(100)
        .get();
    for (const doc of stale.docs) {
        try { await expireDeliveryRequest(db, doc.id); }
        catch (e) { console.error(`Failed to expire delivery request ${doc.id}`, e); }
    }
});
