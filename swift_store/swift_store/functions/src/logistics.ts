import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * Creates a delivery route for a paid order.
 *
 * Contract (matches FirebaseDeliveryRepository.requestDelivery on Android):
 * - Request: { orderId: string, deliveryListingId: string }
 * - Response: routeId (string)
 */
export const requestDelivery = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { orderId, deliveryListingId } = request.data;
    if (!orderId || !deliveryListingId) {
        throw new HttpsError(
            "invalid-argument",
            "orderId and deliveryListingId are required"
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
            const participants = order.participants || {};
            const isRequester = participants["REQUESTER"] === auth.uid;
            const isSeller = participants["SELLER"] === auth.uid;
            const isProvider = participants["DELIVERY_PROVIDER"] === auth.uid;

            if (!isRequester && !isSeller && !isProvider && !isAdmin) {
                throw new Error("Unauthorized: Only order participants or admins can request delivery.");
            }


            if (order.status !== "CONFIRMED") {
                throw new Error(
                    `Order must be CONFIRMED before requesting delivery (current: ${order.status})`
                );
            }

            // ── Reconciliation: verify the provider matches the purchased one ──
            if (deliveryListingId !== order.selectedDeliveryListingId) {
                throw new Error(
                    "Selected delivery provider does not match the purchased option."
                );
            }

            // ── Idempotent: one active route per order ──────────────────────
            if (order.deliveryRouteId) {
                return order.deliveryRouteId as string;
            }

            // ── Source dropoff from the order's buyer pin snapshot ──────────
            const dest = order.destinationLocationSnapshot;
            if (!dest || typeof dest.lat !== "number" || typeof dest.lng !== "number") {
                throw new Error(
                    "Order has no confirmed buyer location snapshot."
                );
            }

            // ── Source pickup from the order's shop location snapshot ───────
            // originLocationSnapshot is written at order creation from the
            // shop document — never from the client.
            const origin = order.originLocationSnapshot;
            if (!origin || typeof origin.lat !== "number" || typeof origin.lng !== "number") {
                throw new Error(
                    "Order has no confirmed shop origin snapshot. " +
                    "Seller must set shop location before dispatch."
                );
            }

            const routeId = db.collection("deliveryRoutes").doc().id;
            const newRoute = {
                id: routeId,
                orderId,
                buyerId: order.buyerId,
                sellerId: order.sellerId,
                driverId: "",
                // Pickup = shop / origin (server-sourced, immutable snapshot)
                pickupLat: origin.lat,
                pickupLng: origin.lng,
                // Dropoff = buyer confirmed pin (server-sourced, immutable snapshot)
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

            const orderRef = db.collection("orders").doc(route.orderId);
            const orderDoc = await transaction.get(orderRef);
            const order = orderDoc.exists ? orderDoc.data() : null;
            const participants = order?.participants || {};

            const isAdmin = auth.token.admin === true;
            const isAssignedDriver = !!route.driverId && route.driverId === auth.uid;
            const isRequester = participants["REQUESTER"] === auth.uid;
            const isProvider = participants["DELIVERY_PROVIDER"] === auth.uid;

            const allowedNext = ALLOWED_TRANSITIONS[route.status] || [];
            if (!allowedNext.includes(status)) {
                throw new Error(`Cannot transition delivery from ${route.status} to ${status}`);
            }

            if (status === "ASSIGNED") {
                // A driver claims an unassigned request.
                // Authorization: Must have DRIVER role claim, OR be the DELIVERY_PROVIDER business assigning to themselves/staff.
                if (route.driverId) throw new Error("Route already has an assigned driver");

                const canAssign = auth.token.role === "DRIVER" || isProvider || isAdmin;
                if (!canAssign) throw new Error("Unauthorized to assign/accept this delivery");

                transaction.update(routeRef, {
                    driverId: driverId || auth.uid,
                    status,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
                return;
            }

            if (status === "CANCELLED") {
                // Requester can cancel only before a driver is assigned.
                // Provider/Driver/Admin can cancel before completion.
                if (!isRequester && !isAssignedDriver && !isProvider && !isAdmin) throw new Error("Unauthorized");
                if (isRequester && route.status !== "REQUESTED") throw new Error("Requester can only cancel before assignment");
            } else {
                // Operational updates (PICKUP, IN_TRANSIT, DELIVERED)
                if (!isAssignedDriver && !isAdmin) throw new Error("Only the assigned driver can update operational status");
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
