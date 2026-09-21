import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";

const DELIVERY_REQUEST_WINDOW_MS = 120_000; // 120 seconds, server-authoritative

/**
 * Creates a delivery route for a paid order.
 *
 * Server-authoritative: resolves pickup from order's shop.
 *
 * Contract (matches FirebaseDeliveryRepository.requestDelivery on Android):
 * - Request: { orderId: string, dropoff: {lat, lng} }
 * - Response: routeId (string)
 */
export const requestDelivery = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, dropoff } = request.data;
    if (
        !orderId || !dropoff ||
        typeof dropoff.lat !== "number" || typeof dropoff.lng !== "number"
    ) {
        throw new HttpsError("invalid-argument", "orderId and dropoff ({lat, lng}) are required");
    }

    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const orderRef = db.collection("orders").doc(orderId);
            const orderDoc = await transaction.get(orderRef);
            if (!orderDoc.exists) throw new Error("Order not found");
            const order = orderDoc.data()!;

            const isAdmin = auth.token.admin === true;
            if (order.buyerId !== auth.uid && order.sellerId !== auth.uid && !isAdmin) {
                throw new Error("Unauthorized");
            }

            // A route only makes sense once payment is confirmed.
            if (order.status !== "CONFIRMED") {
                throw new Error(`Order must be CONFIRMED before requesting delivery (current status: ${order.status})`);
            }

            // Idempotent: one active route per order.
            if (order.deliveryRouteId) {
                return order.deliveryRouteId as string;
            }

            // Resolve pickup from the shop associated with the order.
            const shopRef = db.collection("shops").doc(order.shopId);
            const shopDoc = await transaction.get(shopRef);
            if (!shopDoc.exists) throw new Error("Order's shop not found");
            const shopData = shopDoc.data()!;

            const pickup = {
                lat: shopData.locationLat,
                lng: shopData.locationLng
            };

            if (typeof pickup.lat !== "number" || typeof pickup.lng !== "number" || (pickup.lat === 0 && pickup.lng === 0)) {
                throw new Error("Shop location not configured correctly for pickup");
            }

            const routeId = db.collection("deliveryRoutes").doc().id;
            const newRoute = {
                id: routeId,
                orderId,
                buyerId: order.buyerId,
                sellerId: order.sellerId,
                providerId: shopData.ownerId, // SWIFT-019: Canonical provider is Merchant UID
                driverId: "",
                pickupLat: pickup.lat,
                pickupLng: pickup.lng,
                dropoffLat: dropoff.lat,
                dropoffLng: dropoff.lng,
                status: "REQUESTED",
                distanceMeters: 0,
                estimatedMinutes: 0,
                conversationId: "",
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            };

            transaction.set(db.collection("deliveryRoutes").doc(routeId), newRoute);
            transaction.update(orderRef, {
                deliveryRouteId: routeId,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            return routeId;
        });
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

const VALID_DELIVERY_STATUSES = ["REQUESTED", "ASSIGNED", "PICKUP", "IN_TRANSIT", "DELIVERED", "FAILED", "CANCELLED"];

// Linear, conservative transition map. Driver dispatch/matching isn't designed yet
// (blueprint §27 lists this as an architectural unknown) — revisit once it is.
const ALLOWED_TRANSITIONS: Record<string, string[]> = {
    REQUESTED: ["ASSIGNED", "CANCELLED"],
    ASSIGNED: ["PICKUP", "CANCELLED"],
    PICKUP: ["IN_TRANSIT", "FAILED"],
    IN_TRANSIT: ["DELIVERED", "FAILED"],
    DELIVERED: [],
    FAILED: [],
    CANCELLED: []
};

/**
 * Updates delivery status authoritatively server-side.
 *
 * Contract (matches FirebaseDeliveryRepository.updateDeliveryStatus on Android):
 * - Request: { routeId: string, status: string, driverId?: string }
 */
export const updateDeliveryStatus = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { routeId, status } = request.data;
    if (!routeId || !status || !VALID_DELIVERY_STATUSES.includes(status)) {
        throw new HttpsError("invalid-argument", "routeId and a valid status are required");
    }

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const routeRef = db.collection("deliveryRoutes").doc(routeId);
            const routeDoc = await transaction.get(routeRef);
            if (!routeDoc.exists) throw new Error("Delivery route not found");
            const route = routeDoc.data()!;

            const isAdmin = auth.token.admin === true;
            const isAssignedDriver = !!route.driverId && route.driverId === auth.uid;
            const isBuyer = route.buyerId === auth.uid;

            const allowedNext = ALLOWED_TRANSITIONS[route.status] || [];
            if (!allowedNext.includes(status)) {
                throw new Error(`Cannot transition delivery from ${route.status} to ${status}`);
            }

            // SWIFT-019: Implement Authoritative Driver Claiming
            if (status === "ASSIGNED" && route.status === "REQUESTED") {
                if (route.driverId) throw new Error("Route already has an assigned driver");

                const isAdmin = auth.token.admin === true;
                if (auth.token.role !== "DRIVER" && !isAdmin) {
                    throw new Error("Only a driver can claim a delivery");
                }

                // Membership check: Is driver authorized by this provider?
                const isProviderSelf = route.providerId === auth.uid;
                let isAuthorizedMember = false;

                if (!isProviderSelf && !isAdmin) {
                    const authRef = db.collection("deliveryProviders")
                        .doc(route.providerId)
                        .collection("drivers")
                        .doc(auth.uid);
                    const authDoc = await transaction.get(authRef);
                    isAuthorizedMember = authDoc.exists && authDoc.data()?.authorized === true;
                }

                if (!isProviderSelf && !isAuthorizedMember && !isAdmin) {
                   throw new Error("You are not an authorized driver for this provider.");
                }

                // SWIFT-019: Derive driverId from authenticated UID, never trust client parameter
                transaction.update(routeRef, {
                    driverId: auth.uid,
                    status: "ASSIGNED",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
                return;
            }

            if (status === "CANCELLED") {
                // Buyer can cancel only before a driver is assigned; the assigned driver/admin can cancel any time before completion.
                if (!isBuyer && !isAssignedDriver && !isAdmin) throw new Error("Unauthorized");
                if (isBuyer && route.status !== "REQUESTED") throw new Error("Buyer can only cancel before a driver is assigned");
            } else {
                if (!isAssignedDriver && !isAdmin) throw new Error("Only the assigned driver can update this delivery");
            }

            transaction.update(routeRef, {
                status,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            // Mirror terminal delivery states back onto the order.
            if (status === "IN_TRANSIT") {
                transaction.update(db.collection("orders").doc(route.orderId), {
                    status: "DISPATCHED",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            } else if (status === "DELIVERED") {
                transaction.update(db.collection("orders").doc(route.orderId), {
                    status: "DELIVERED",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Creates a delivery job request against a DELIVER-type listing.
 *
 * Server-authoritative: ignores client-supplied pickup coordinates.
 * Fetches the shop's stored location from Firestore.
 *
 * Contract (matches FirebaseDeliveryRepository.createDeliveryRequest on Android):
 * - Request: { listingId: string, dropoff: {lat, lng} }
 * - Response: requestId (string)
 */
export const createDeliveryRequest = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { listingId, dropoff } = request.data;
    if (
        !listingId || !dropoff ||
        typeof dropoff.lat !== "number" || typeof dropoff.lng !== "number"
    ) {
        throw new HttpsError("invalid-argument", "listingId and dropoff ({lat, lng}) are required");
    }

    const db = admin.firestore();
    const listingDoc = await db.collection("listings").doc(listingId).get();
    if (!listingDoc.exists) {
        throw new HttpsError("not-found", "Listing not found");
    }
    const listing = listingDoc.data()!;
    if (listing.listingType !== "DELIVER") {
        throw new HttpsError("failed-precondition", "Listing is not a delivery listing");
    }
    if (listing.isAvailable !== true) {
        throw new HttpsError("failed-precondition", "This delivery listing is not currently available");
    }

    // Resolve authoritative pickup from the merchant's shop
    const shopRef = db.collection("shops").doc(listing.shopId);
    const shopDoc = await shopRef.get();
    if (!shopDoc.exists) {
        throw new HttpsError("failed-precondition", "Merchant shop not found");
    }
    const shopData = shopDoc.data()!;
    const pickup = {
        lat: shopData.locationLat,
        lng: shopData.locationLng
    };

    if (typeof pickup.lat !== "number" || typeof pickup.lng !== "number" || (pickup.lat === 0 && pickup.lng === 0)) {
        throw new HttpsError("failed-precondition", "Merchant shop has no valid location configured");
    }

    const now = Date.now();
    const requestRef = db.collection("deliveryRequests").doc();
    const newRequest = {
        id: requestRef.id,
        listingId,
        merchantId: listing.sellerId,
        requesterId: auth.uid,
        pickup,
        dropoff,
        pickupLabel: shopData.name || "Merchant Shop",
        dropoffLabel: "",
        deliveryFeeMinorUnits: listing.priceMinorUnits ?? 0,
        deliveryFeeCurrency: listing.priceCurrency ?? "LSL",
        status: "PENDING",
        expiresAt: now + DELIVERY_REQUEST_WINDOW_MS,
        relatedOrderId: "",
        createdAt: now,
        updatedAt: now
    };

    await requestRef.set(newRequest);
    return requestRef.id;
});

/**
 * Merchant response (accept/decline) to a pending delivery job request.
 *
 * Contract (matches FirebaseDeliveryRepository.respondToDeliveryRequest on Android):
 * - Request: { requestId: string, accept: boolean }
 * - Response: { success: true, status: "ACCEPTED" | "DECLINED" }
 *
 * Scope note: ACCEPTED/DECLINED here means only that the merchant has
 * responded to the job request. This function intentionally does not
 * create an order, touch payment, wallet, ledger, inventory, escrow, or
 * deliveryRoutes — the handoff from an accepted request into those systems
 * is separate, not-yet-designed work.
 */
export const respondToDeliveryRequest = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { requestId, accept } = request.data;
    if (!requestId || typeof accept !== "boolean") {
        throw new HttpsError("invalid-argument", "requestId and accept (boolean) are required");
    }

    const db = admin.firestore();
    const newStatus = accept ? "ACCEPTED" : "DECLINED";

    try {
        await db.runTransaction(async (transaction) => {
            const requestRef = db.collection("deliveryRequests").doc(requestId);
            const requestDoc = await transaction.get(requestRef);
            if (!requestDoc.exists) throw new Error("Delivery request not found");
            const deliveryRequest = requestDoc.data()!;

            // Strict ownership check only — no admin bypass, no new role model.
            if (deliveryRequest.merchantId !== auth.uid) {
                throw new Error("Only the requested merchant can respond to this delivery request");
            }

            // Fail closed: any non-PENDING status (ACCEPTED, DECLINED, EXPIRED,
            // CANCELLED) rejects a second response, including a double-tap of
            // the same action or Accept racing the expiry sweep.
            if (deliveryRequest.status !== "PENDING") {
                throw new Error(`Cannot respond to a request that is already ${deliveryRequest.status}`);
            }

            if (deliveryRequest.expiresAt <= Date.now()) {
                throw new Error("This delivery request has expired");
            }

            transaction.update(requestRef, {
                status: newStatus,
                updatedAt: Date.now()
            });
        });
        return { success: true, status: newStatus };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Cancels a pending delivery request initiated by the requester.
 * Only the requester (customer) may cancel a PENDING request.
 * Requests in any terminal state (ACCEPTED, DECLINED, EXPIRED, CANCELLED)
 * cannot be cancelled — the client must handle those states in the UI.
 *
 * Contract:
 * - Request: { requestId: string }
 * - Response: { success: true }
 */
export const cancelDeliveryRequest = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { requestId } = request.data;
    if (!requestId) throw new HttpsError("invalid-argument", "requestId is required");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const requestRef = db.collection("deliveryRequests").doc(requestId);
            const requestDoc = await transaction.get(requestRef);
            if (!requestDoc.exists) throw new Error("Delivery request not found");
            const deliveryRequest = requestDoc.data()!;

            if (deliveryRequest.requesterId !== auth.uid) {
                throw new Error("Only the requester can cancel this delivery request");
            }

            // Only PENDING requests can be cancelled by the customer.
            // ACCEPTED requests need a different flow (post-acceptance cancellation).
            if (deliveryRequest.status !== "PENDING") {
                throw new Error(`Cannot cancel a request that is already ${deliveryRequest.status}`);
            }

            transaction.update(requestRef, {
                status: "CANCELLED",
                updatedAt: Date.now()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Scheduled sweep: flips any PENDING request whose window has elapsed to
 * EXPIRED. Runs every minute. This is what makes "merchant never responded"
 * actually resolve for the requester even if the merchant's device never
 * calls respondToDeliveryRequest at all.
 */
export const expireDeliveryRequests = onSchedule("every 1 minutes", async () => {
    const db = admin.firestore();
    const now = Date.now();
    const staleSnapshot = await db.collection("deliveryRequests")
        .where("status", "==", "PENDING")
        .where("expiresAt", "<=", now)
        .get();

    if (staleSnapshot.empty) return;

    const batch = db.batch();
    staleSnapshot.docs.forEach((doc) => {
        batch.update(doc.ref, { status: "EXPIRED", updatedAt: now });
    });
    await batch.commit();
});

/**
 * Trigger: Order confirmed.
 * Automatically creates a delivery route if delivery was requested and accepted.
 */
export const onOrderConfirmed = onDocumentUpdated("orders/{orderId}", async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after) return;

    if (before.status !== "CONFIRMED" && after.status === "CONFIRMED" && after.requiresDelivery && after.deliveryRequestId) {
        const db = admin.firestore();

        // Fetch the accepted delivery request
        const drDoc = await db.collection("deliveryRequests").doc(after.deliveryRequestId).get();
        if (!drDoc.exists) return;
        const dr = drDoc.data()!;

        if (dr.status !== "ACCEPTED") return;

        // Check if route already exists (idempotency)
        const routeQuery = await db.collection("deliveryRoutes").where("orderId", "==", event.params.orderId).get();
        if (!routeQuery.empty) return;

        const routeId = db.collection("deliveryRoutes").doc().id;
        const newRoute = {
            id: routeId,
            orderId: event.params.orderId,
            buyerId: after.buyerId,
            sellerId: after.sellerId,
            providerId: dr.merchantId, // Designated provider from the request
            driverId: "", // SWIFT-019: Unassigned initially
            pickupLat: dr.pickup.lat,
            pickupLng: dr.pickup.lng,
            dropoffLat: dr.dropoff.lat,
            dropoffLng: dr.dropoff.lng,
            status: "REQUESTED", // SWIFT-019: Waiting for a driver to claim
            distanceMeters: 0,
            estimatedMinutes: 0,
            conversationId: "",
            createdAt: admin.firestore.FieldValue.serverTimestamp()
        };

        await db.collection("deliveryRoutes").doc(routeId).set(newRoute);
        await db.collection("orders").doc(event.params.orderId).update({
            deliveryRouteId: routeId,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        console.log(`Auto-created delivery route ${routeId} for order ${event.params.orderId}`);
    }
});

/**
 * Merchant authorizes a driver to operate their routes.
 * Request: { driverUid: string, authorized: boolean }
 */
export const authorizeDriver = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { driverUid, authorized } = request.data;
    if (!driverUid || typeof authorized !== "boolean") {
        throw new HttpsError("invalid-argument", "driverUid and authorized (boolean) required");
    }

    const db = admin.firestore();
    const providerId = auth.uid;

    // Optional: verify provider actually has a shop or provider listing
    // Scoped to simple relationship record for now.

    await db.collection("deliveryProviders")
        .doc(providerId)
        .collection("drivers")
        .doc(driverUid)
        .set({
            authorized,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

    return { success: true };
});
