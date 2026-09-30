import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { isDeliveryStatus, canTransition } from "./fulfillmentStates";

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

    const db = admin.firestore();

    const orderRef = db.collection("orders").doc(orderId);
    const listingRef = db.collection("listings").doc(listingId);
    const [orderDoc, listingDoc] = await Promise.all([orderRef.get(), listingRef.get()]);

    if (!orderDoc.exists) throw new HttpsError("not-found", "Purchase order not found");
    if (!listingDoc.exists) throw new HttpsError("not-found", "Delivery listing not found");

    const order = orderDoc.data()!;
    const listing = listingDoc.data()!;

    if (order.buyerId !== auth.uid) throw new HttpsError("permission-denied", "Only the buyer can request fulfillment");
    if (order.paymentStatus !== "PAID" && order.status !== "CONFIRMED") {
        throw new HttpsError("failed-precondition", "Product purchase must be paid before delivery is requested");
    }
    if (listing.listingType !== "DELIVER" || listing.isAvailable !== true) {
        throw new HttpsError("failed-precondition", "Delivery provider listing is unavailable");
    }

    const pickup = order.pickupSnapshot;
    if (!pickup || typeof pickup.lat !== "number" || typeof pickup.lng !== "number") {
        throw new HttpsError("failed-precondition", "Purchase order has no valid pickup location");
    }

    const existing = await db.collection("deliveryRequests")
        .where("relatedOrderId", "==", orderId)
        .where("status", "in", ["PENDING", "ACCEPTED"])
        .limit(1)
        .get();

    if (!existing.empty) throw new HttpsError("failed-precondition", "This purchase already has an active delivery request");

    const ref = db.collection("deliveryRequests").doc();
    const now = Date.now();
    await ref.set({
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
        deliveryFeeMinorUnits: listing.priceMinorUnits || 0,
        deliveryFeeCurrency: listing.priceCurrency || "LSL",
        status: "PENDING",
        expiresAt: now + DELIVERY_REQUEST_WINDOW_MS,
        createdAt: now,
        updatedAt: now
    });

    return {
        requestId: ref.id,
        deliveryFeeMinorUnits: listing.priceMinorUnits || 0,
        currency: listing.priceCurrency || "LSL"
    };
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

            tx.update(ref, {
                status: accept ? "ACCEPTED" : "DECLINED",
                updatedAt: Date.now()
            });
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
            if (d.status !== "PENDING") throw new Error(`Cannot cancel request in status ${d.status}`);
            tx.update(ref, { status: "CANCELLED", updatedAt: Date.now() });
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

            const existing = await db.collection("deliveryRoutes")
                .where("orderId", "==", dr.relatedOrderId)
                .limit(1)
                .get();
            if (!existing.empty) {
                return { routeId: existing.docs[0].id, alreadyExists: true };
            }

            const routeRef = db.collection("deliveryRoutes").doc();
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
