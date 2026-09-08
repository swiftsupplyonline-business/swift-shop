import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * Creates a delivery route for a paid order.
 *
 * Contract (matches FirebaseDeliveryRepository.requestDelivery on Android):
 * - Request: { orderId: string, pickup: {lat, lng}, dropoff: {lat, lng} }
 * - Response: routeId (string)
 */
export const requestDelivery = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, pickup } = request.data;
    if (
        !orderId || !pickup ||
        typeof pickup.lat !== "number" || typeof pickup.lng !== "number"
    ) {
        throw new HttpsError(
            "invalid-argument",
            "orderId and pickup ({lat, lng}) are required"
        );
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

            if (order.status !== "CONFIRMED") {
                throw new Error(
                    `Order must be CONFIRMED before requesting delivery (current: ${order.status})`
                );
            }

            // ── Idempotent: one active route per order ──────────────────────
            if (order.deliveryRouteId) {
                return order.deliveryRouteId as string;
            }

            // ── Source dropoff from the order's buyer pin snapshot ──────────
            // destinationLocationSnapshot is written by placeOrder/confirmOrder
            // from the buyer's confirmed DeliveryAddress lat/lng.
            const dest = order.destinationLocationSnapshot;
            if (!dest || typeof dest.lat !== "number" || typeof dest.lng !== "number") {
                throw new Error(
                    "Order has no confirmed buyer location. " +
                    "destinationLocationSnapshot is missing or malformed."
                );
            }

            // ── Source pickup from the order's shop location snapshot ───────
            // originLocationSnapshot is written at order creation from the
            // shop document — never from the client.
            const origin = order.originLocationSnapshot;
            const resolvedPickupLat = (origin && typeof origin.lat === "number")
                ? origin.lat
                : pickup.lat;   // fallback to client only if shop has no geo
            const resolvedPickupLng = (origin && typeof origin.lng === "number")
                ? origin.lng
                : pickup.lng;

            const routeId = db.collection("deliveryRoutes").doc().id;
            const newRoute = {
                id: routeId,
                orderId,
                buyerId: order.buyerId,
                sellerId: order.sellerId,
                driverId: "",
                // Pickup = shop / origin (server-sourced)
                pickupLat: resolvedPickupLat,
                pickupLng: resolvedPickupLng,
                // Dropoff = buyer confirmed pin (server-sourced, immutable)
                dropoffLat: dest.lat,
                dropoffLng: dest.lng,
                dropoffAddressSnapshot: dest.addressSnapshot || "",
                dropoffInstructions: dest.instructions || "",
                status: "REQUESTED",
                distanceMeters: 0,
                estimatedMinutes: order.deliveryListingSnapshot?.estimatedMinutes || 0,
                conversationId: "",
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            };

            transaction.set(db.collection("deliveryRoutes").doc(routeId), newRoute);
            transaction.update(orderRef, {
                deliveryRouteId: routeId,
                status: "PROCESSING",           // advance order status
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

    const { routeId, status, driverId } = request.data;
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

            if (status === "ASSIGNED") {
                // A driver claims an unassigned request. Requires the 'DRIVER' role claim.
                if (route.driverId) throw new Error("Route already has an assigned driver");
                if (auth.token.role !== "DRIVER" && !isAdmin) throw new Error("Only a driver can accept a delivery");

                transaction.update(routeRef, {
                    driverId: driverId || auth.uid,
                    status,
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
