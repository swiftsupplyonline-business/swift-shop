import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";

/**
 * Registers or updates the FCM token for the authenticated user.
 */
export const updateFcmToken = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { token } = request.data;
    if (!token) throw new HttpsError("invalid-argument", "Token required");

    const db = admin.firestore();
    // Using { merge: true } to avoid overwriting other user data if stored in the same doc.
    await db.collection("users").doc(auth.uid).set({
        fcmToken: token,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    return { success: true };
});

/**
 * Helper to send a notification to a specific user.
 */
async function sendNotification(userId: string, payload: { notification: { title: string, body: string }, data: Record<string, string> }) {
    const db = admin.firestore();
    const userDoc = await db.collection("users").doc(userId).get();
    const fcmToken = userDoc.data()?.fcmToken;

    if (!fcmToken) {
        console.log(`No FCM token found for user ${userId}, skipping notification.`);
        return;
    }

    try {
        await admin.messaging().send({
            ...payload,
            token: fcmToken
        });
        console.log(`Successfully sent notification to user ${userId}`);
    } catch (error) {
        console.error(`Error sending notification to user ${userId}:`, error);
    }
}

/**
 * Trigger: New message in a conversation.
 */
export const notifyOnMessage = onDocumentCreated("messages/{messageId}", async (event) => {
    const message = event.data?.data();
    if (!message) return;

    const db = admin.firestore();
    const conversationDoc = await db.collection("conversations").doc(message.conversationId).get();
    const conversation = conversationDoc.data();
    if (!conversation) return;

    const recipientId = conversation.participantIds.find((id: string) => id !== message.senderId);
    if (!recipientId) return;

    // Fetch sender name from their profile
    const senderDoc = await db.collection("profiles").doc(message.senderId).get();
    const senderName = senderDoc.data()?.displayName || "Someone";

    await sendNotification(recipientId, {
        notification: {
            title: `New message from ${senderName}`,
            body: message.text,
        },
        data: {
            type: "MESSAGE",
            targetId: message.conversationId,
        }
    });
});

/**
 * Trigger: Order status change.
 */
export const notifyOnOrderStatusChange = onDocumentUpdated("orders/{orderId}", async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after || before.status === after.status) return;

    // Notify buyer of the status update.
    await sendNotification(after.buyerId, {
        notification: {
            title: "Order Update",
            body: `Your order #${event.params.orderId.substring(0, 8)} status is now ${after.status}.`,
        },
        data: {
            type: "ORDER",
            targetId: event.params.orderId,
        }
    });
});

/**
 * Trigger: New delivery job request.
 */
export const notifyOnDeliveryRequestCreated = onDocumentCreated("deliveryRequests/{requestId}", async (event) => {
    const request = event.data?.data();
    if (!request) return;

    // Notify the merchant that a delivery is being requested.
    await sendNotification(request.merchantId, {
        notification: {
            title: "New Delivery Request",
            body: "A customer is requesting delivery for a new order.",
        },
        data: {
            type: "DELIVERY",
            targetId: event.params.requestId,
        }
    });
});

/**
 * Trigger: Delivery request accepted or declined by merchant.
 */
export const notifyOnDeliveryRequestResponded = onDocumentUpdated("deliveryRequests/{requestId}", async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after || before.status === after.status) return;

    // Notify the requester (customer) of the merchant's response.
    await sendNotification(after.requesterId, {
        notification: {
            title: "Delivery Request Update",
            body: `Your delivery request was ${after.status}.`,
        },
        data: {
            type: "DELIVERY",
            targetId: event.params.requestId,
        }
    });
});

/**
 * Trigger: Delivery route status change.
 */
export const notifyOnDeliveryStatusChange = onDocumentUpdated("deliveryRoutes/{routeId}", async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after || before.status === after.status) return;

    // Notify buyer of the delivery update.
    await sendNotification(after.buyerId, {
        notification: {
            title: "Delivery Update",
            body: `Your delivery status is now ${after.status}.`,
        },
        data: {
            type: "DELIVERY",
            targetId: after.id,
        }
    });
});
