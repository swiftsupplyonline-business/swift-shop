import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as admin from "firebase-admin";
import { isDeliveryStatus, canTransition } from "./fulfillmentStates";
import { readWallet, isEscrowed, writeEscrow, writeRefund } from "./deliveryFee";

const DELIVERY_REQUEST_WINDOW_MS = 120_000;
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

    const { orderId, listingId, dropoff } = request.data;
    if (!orderId || !listingId || !dropoff ||
        typeof dropoff.lat !== "number" || typeof dropoff.lng !== "number") {
        throw new HttpsError("invalid-argument", "orderId, listingId and dropoff ({lat, lng}) are required");
    }

    if (!Number.isFinite(dropoff.lat) || !Number.isFinite(dropoff.lng) ||
        Math.abs(dropoff.lat) > 90 || Math.abs(dropoff.lng) > 180) {
        throw new HttpsError("invalid-argument", "Invalid dropoff coordinates");
    }

    const db = admin.firestore();

    try {
        return await db.runTransaction(async tx => {
            // ── Phase 1: all reads ──
            const orderDoc = await tx.get(db.collection("orders").doc(orderId));
            const listingDoc = await tx.get(db.collection("listings").doc(listingId));
            if (!orderDoc.exists) throw new HttpsError("not-found", "Purchase order not found");
            if (!listingDoc.exists) throw new HttpsError("not-found", "Delivery listing not found");

            const order = orderDoc.data()!;
            const listing = listingDoc.data()!;

            if (order.buyerId !== auth.uid) throw new HttpsError("permission-denied", "Only the buyer can request fulfillment");
            if (order.paymentStatus !== "PAID" && order.status !== "CONFIRMED") {
                throw new HttpsError("failed-precondition", "Product purchase must be paid before delivery is requested");
            }
            if (["CANCELLED", "REFUNDED", "FAILED", "DELIVERED", "COMPLETED"].includes(order.status)) {
                throw new HttpsError("failed-precondition", "This purchase can no longer be delivered");
            }
            if (listing.listingType !== "DELIVER" || listing.isAvailable !== true) {
                throw new HttpsError("failed-precondition", "Delivery provider listing is unavailable");
            }
            if ((listing.priceCurrency || "LSL") !== "LSL") {
                throw new HttpsError("failed-precondition", "Delivery listing is not priced in LSL");
            }
            const fee = listing.priceMinorUnits || 0;
            if (!Number.isSafeInteger(fee) || fee < 0) {
                throw new HttpsError("failed-precondition", "Delivery listing has an invalid price");
            }

            const pickup = order.pickupSnapshot;
            if (!pickup || typeof pickup.lat !== "number" || typeof pickup.lng !== "number") {
                throw new HttpsError("failed-precondition", "Purchase order has no valid pickup location");
            }

            const existing = await tx.get(db.collection("deliveryRequests")
                .where("relatedOrderId", "==", orderId)
                .where("status", "in", ["PENDING", "ACCEPTED"])
                .limit(1));
            if (!existing.empty) throw new HttpsError("failed-precondition", "This purchase already has an active delivery request");

            const wallet = fee > 0 ? await readWallet(tx, auth.uid) : null;

            // ── Phase 2: writes ──
            const ref = db.collection("deliveryRequests").doc();
            const now = Date.now();
            if (wallet) writeEscrow(tx, wallet, auth.uid, fee, ref.id);
            tx.set(ref, {
                id: ref.id,
                listingId,
                merchantId: listing.sellerId || "",
                merchandiseShopId: order.shopId || "",
                requesterId: auth.uid,
                relatedOrderId: orderId,
                pickup,
                dropoff,
                pickupLabel: pickup.shopName || "Merchant Shop",
                dropoffLabel: "",
                deliveryFeeMinorUnits: fee,
                deliveryFeeCurrency: "LSL",
                feePaymentStatus: fee > 0 ? "ESCROWED" : "NONE",
                status: "PENDING",
                expiresAt: now + DELIVERY_REQUEST_WINDOW_MS,
                createdAt: now,
                updatedAt: now
            });

            return { requestId: ref.id, deliveryFeeMinorUnits: fee, currency: "LSL" };
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
            if (d.expiresAt <= Date.now()) throw new Error("Delivery request has expired");

            // Read the buyer's wallet BEFORE any write (only needed when a decline refunds the fee).
            const wallet = !accept && isEscrowed(d) ? await readWallet(tx, d.requesterId) : null;

            tx.update(ref, {
                status: accept ? "ACCEPTED" : "DECLINED",
                updatedAt: Date.now()
            });
            if (!accept) writeRefund(tx, ref, d, wallet, "DECLINED");
        });
        return { success: true, status: accept ? "ACCEPTED" : "DECLINED" };
    } catch (e: any) {
        throw new HttpsError("failed-precondition", e.message);
    }
}

export const cancelDeliveryRequest = onCall(async (request) => {
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
            if (d.requesterId !== auth.uid) throw new Error("Only the requester can cancel");
            const cancellable = d.status === "PENDING" || (d.status === "ACCEPTED" && d.assignmentStatus !== "JOB_CREATED");
            if (!cancellable) throw new Error(`Cannot cancel request in status ${d.status}`);
            const wallet = isEscrowed(d) ? await readWallet(tx, d.requesterId) : null;
            tx.update(ref, { status: "CANCELLED", updatedAt: Date.now() });
            writeRefund(tx, ref, d, wallet, "CANCELLED_BY_BUYER");
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

    const { requestId } = request.data;
    if (!requestId) throw new HttpsError("invalid-argument", "requestId is required");

    const db = admin.firestore();

    try {
        return await db.runTransaction(async tx => {
            const requestRef = db.collection("deliveryRequests").doc(requestId);
            const requestSnap = await tx.get(requestRef);
            if (!requestSnap.exists) throw new Error("Delivery request not found");
            const dr = requestSnap.data()!;

            if (dr.requesterId !== auth.uid) throw new Error("Only the buyer can create this fulfillment job");
            if (dr.status !== "ACCEPTED") throw new Error("Delivery request must be accepted first");
            if (!dr.relatedOrderId) throw new Error("Delivery request is not attached to a purchase order");

            const orderRef = db.collection("orders").doc(dr.relatedOrderId);
            const orderSnap = await tx.get(orderRef);
            if (!orderSnap.exists) throw new Error("Purchase order not found");
            const order = orderSnap.data()!;

            if (order.buyerId !== auth.uid) throw new Error("Purchase order does not belong to this buyer");
            if (["CANCELLED", "REFUNDED", "FAILED"].includes(order.status)) throw new Error("Purchase order is no longer active");

            // One order -> one job. The deterministic id makes this race-proof: two concurrent transactions
            // both read the same missing doc, and Firestore aborts/retries the loser, which then sees it exist.
            // The query covers jobs created earlier under random ids; it is read through the transaction.
            const routeRef = db.collection("deliveryRoutes").doc(`job_${dr.relatedOrderId}`);
            const routeSnap = await tx.get(routeRef);
            if (routeSnap.exists) return { routeId: routeRef.id, alreadyExists: true };
            const existing = await tx.get(
                db.collection("deliveryRoutes").where("orderId", "==", dr.relatedOrderId).limit(1)
            );
            if (!existing.empty) return { routeId: existing.docs[0].id, alreadyExists: true };

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
                requestId,
                deliveryListingId: dr.listingId,
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
                fulfillmentStatus: "REQUESTED",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            return { routeId: routeRef.id, alreadyExists: false };
        });
    } catch (e: any) {
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

            // An unsuccessful delivery returns the escrowed fee to the buyer. Reads first, then writes.
            let feeRequest: FirebaseFirestore.DocumentSnapshot | null = null;
            let feeWallet: Awaited<ReturnType<typeof readWallet>> | null = null;
            if (status === "FAILED" || status === "CANCELLED") {
                if (route.requestId) {
                    const reqSnap = await tx.get(db.collection("deliveryRequests").doc(route.requestId));
                    if (reqSnap.exists) feeRequest = reqSnap;
                } else {
                    const q = await tx.get(db.collection("deliveryRequests")
                        .where("relatedOrderId", "==", route.orderId)
                        .where("feePaymentStatus", "==", "ESCROWED")
                        .limit(1));
                    if (!q.empty) feeRequest = q.docs[0];
                }
                if (feeRequest && isEscrowed(feeRequest.data()!)) feeWallet = await readWallet(tx, feeRequest.data()!.requesterId);
            }

            tx.update(routeRef, {
                status,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            if (feeRequest) {
                writeRefund(tx, feeRequest.ref, feeRequest.data()!, feeWallet,
                    status === "FAILED" ? "DELIVERY_FAILED" : "DELIVERY_CANCELLED",
                    { status: "CANCELLED", failureReason: status === "FAILED" ? "DELIVERY_FAILED" : "DELIVERY_CANCELLED" });
            }

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
 * Processes at most 100 per run; the remainder is picked up next minute.
 */
export const expireDeliveryRequests = onSchedule("every 1 minutes", async () => {
    const db = admin.firestore();
    const now = Date.now();
    const stale = await db.collection("deliveryRequests")
        .where("status", "==", "PENDING")
        .where("expiresAt", "<=", now)
        .limit(100)
        .get();

    if (stale.empty) return;

    // One transaction per request: the status change and the fee refund succeed or fail together.
    for (const doc of stale.docs) {
        try {
            await db.runTransaction(async tx => {
                const snap = await tx.get(doc.ref);
                const d = snap.data();
                if (!d || d.status !== "PENDING" || d.expiresAt > Date.now()) return; // answered meanwhile
                const wallet = isEscrowed(d) ? await readWallet(tx, d.requesterId) : null;
                tx.update(doc.ref, { status: "EXPIRED", updatedAt: Date.now() });
                writeRefund(tx, doc.ref, d, wallet, "EXPIRED");
            });
        } catch (e) {
            console.error(`expireDeliveryRequests: failed for ${doc.id}`, e);
        }
    }
});
