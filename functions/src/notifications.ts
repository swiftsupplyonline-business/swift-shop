import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";

/**
 * Registers or updates the FCM token for a specific device.
 */
export const updateFcmToken = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { token, deviceId, platform } = request.data;
    if (!token || !deviceId) throw new HttpsError("invalid-argument", "Token and deviceId required");

    const db = admin.firestore();

    // Store token in devices subcollection to support multiple devices per user.
    await db.collection("users").doc(auth.uid).collection("devices").doc(deviceId).set({
        token,
        platform: platform || "android",
        isActive: true,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return { success: true };
});

/**
 * Helper to claim and send a notification to all active devices of a user.
 * Implements a durable state machine: PENDING -> CLAIMED -> SENT/FAILED.
 */
async function sendNotification(userId: string, payload: { notification: { title: string, body: string }, data: Record<string, string> }, eventId: string) {
    const db = admin.firestore();
    const eventRef = db.collection("notificationEvents").doc(`${eventId}_${userId}`);
    const leaseTime = 30 * 1000; // 30 second lease

    // 1. Atomic Claim
    const claimResult = await db.runTransaction(async (transaction) => {
        const doc = await transaction.get(eventRef);
        const data = doc.data();

        if (doc.exists) {
            if (data?.status === "SENT") return "ALREADY_SENT";
            if (data?.status === "FAILED_PERMANENT") return "FAILED_PERMANENT";

            // If claimed but lease not expired, skip (concurrency protection)
            if (data?.status === "CLAIMED" && (Date.now() - data?.claimedAt?.toMillis() < leaseTime)) {
                return "LOCKED";
            }
        }

        const now = admin.firestore.FieldValue.serverTimestamp();
        transaction.set(eventRef, {
            userId,
            payload,
            status: "CLAIMED",
            claimedAt: now,
            updatedAt: now,
            attemptCount: (data?.attemptCount || 0) + 1
        }, { merge: true });

        return "OK";
    });

    if (claimResult !== "OK") {
        console.log(`Notification ${eventId} for user ${userId} skip: ${claimResult}`);
        return;
    }

    // 2. Fetch tokens
    const devicesSnap = await db.collection("users").doc(userId).collection("devices")
        .where("isActive", "==", true)
        .get();

    if (devicesSnap.empty) {
        console.log(`No active devices found for user ${userId}. Marking as FAILED_PERMANENT.`);
        await eventRef.update({ status: "FAILED_PERMANENT", updatedAt: admin.firestore.FieldValue.serverTimestamp() });
        return;
    }

    const tokens = devicesSnap.docs.map(doc => doc.data().token);

    // 3. Dispatch
    try {
        const response = await admin.messaging().sendEachForMulticast({
            ...payload,
            tokens: tokens
        });

        console.log(`Sent notification ${eventId} to ${response.successCount} devices for user ${userId}.`);

        // Handle invalid tokens
        if (response.failureCount > 0) {
            const batch = db.batch();
            response.responses.forEach((resp, idx) => {
                if (!resp.success) {
                    const errorCode = resp.error?.code;
                    if (errorCode === "messaging/registration-token-not-registered" ||
                        errorCode === "messaging/invalid-argument") {
                        const deviceDoc = devicesSnap.docs[idx].ref;
                        console.log(`Removing invalid token for device ${deviceDoc.id}`);
                        batch.update(deviceDoc, { isActive: false, updatedAt: admin.firestore.FieldValue.serverTimestamp() });
                    }
                }
            });
            await batch.commit();
        }

        // Terminal Success
        await eventRef.update({
            status: "SENT",
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

    } catch (error: any) {
        console.error(`Error sending notification ${eventId} to user ${userId}:`, error);

        // Decide if retryable
        const isPermanent = error?.code === "messaging/invalid-argument";
        await eventRef.update({
            status: isPermanent ? "FAILED_PERMANENT" : "PENDING", // PENDING allows retry after lease
            lastError: error.message,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
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
    }, event.id);
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
    }, event.id);
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
    }, event.id);
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
    }, event.id);
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
    }, event.id);
});
