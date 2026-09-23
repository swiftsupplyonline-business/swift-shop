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
 * Implements a durable state machine with per-device accounting.
 */
async function sendNotification(userId: string, payload: { notification: { title: string, body: string }, data: Record<string, string> }, eventId: string) {
    const db = admin.firestore();
    const eventRef = db.collection("notificationEvents").doc(`${eventId}_${userId}`);
    const leaseTime = 30 * 1000; // 30 second lease

    // 1. Atomic Claim & Device Resolution
    const result = await db.runTransaction(async (transaction) => {
        const doc = await transaction.get(eventRef);
        const eventData = doc.data();

        if (doc.exists) {
            if (eventData?.status === "SENT") return { type: "SKIP", reason: "ALREADY_SENT" };
            if (eventData?.status === "FAILED_PERMANENT") return { type: "SKIP", reason: "FAILED_PERMANENT" };

            // Concurrency protection: active lease
            const lastUpdated = eventData?.updatedAt?.toMillis() || 0;
            if (eventData?.status === "CLAIMED" && (Date.now() - lastUpdated < leaseTime)) {
                return { type: "SKIP", reason: "LOCKED" };
            }
        }

        // Fetch target devices inside transaction to ensure consistency
        const devicesSnap = await transaction.get(db.collection("users").doc(userId).collection("devices").where("isActive", "==", true));
        if (devicesSnap.empty) {
            return { type: "SKIP", reason: "NO_ACTIVE_DEVICES" };
        }

        const devices = devicesSnap.docs.map(d => ({ id: d.id, token: d.data().token }));
        const deviceAccounting = eventData?.deviceAccounting || {};

        // Filter for devices that haven't succeeded yet
        const targetDevices = devices.filter(d => deviceAccounting[d.id]?.status !== "SENT");

        if (targetDevices.length === 0) {
            return { type: "SKIP", reason: "ALL_DEVICES_ACCOUNTED" };
        }

        const now = admin.firestore.FieldValue.serverTimestamp();
        transaction.set(eventRef, {
            userId,
            payload,
            status: "CLAIMED",
            updatedAt: now,
            deviceAccounting: deviceAccounting, // Keep existing accounting
            attemptCount: (eventData?.attemptCount || 0) + 1
        }, { merge: true });

        return { type: "PROCEED", targetDevices };
    });

    if (result.type === "SKIP") {
        console.log(`Notification ${eventId} for user ${userId} skip: ${result.reason}`);
        if (result.reason === "NO_ACTIVE_DEVICES") {
            await eventRef.set({ status: "FAILED_PERMANENT", updatedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
        }
        return;
    }

    const { targetDevices } = result as { targetDevices: { id: string, token: string }[] };
    const tokens = targetDevices.map(d => d.token);

    // 2. Dispatch
    try {
        const response = await admin.messaging().sendEachForMulticast({
            ...payload,
            tokens: tokens
        });

        console.log(`Sent notification ${eventId} to ${response.successCount}/${tokens.length} devices for user ${userId}.`);

        const deviceAccountingUpdate: Record<string, any> = {};
        const batch = db.batch();

        response.responses.forEach((resp, idx) => {
            const device = targetDevices[idx];
            if (resp.success) {
                deviceAccountingUpdate[`deviceAccounting.${device.id}`] = {
                    status: "SENT",
                    messageId: resp.messageId,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                };
            } else {
                const errorCode = resp.error?.code;
                const isPermanent = errorCode === "messaging/registration-token-not-registered" ||
                                   errorCode === "messaging/invalid-argument";

                deviceAccountingUpdate[`deviceAccounting.${device.id}`] = {
                    status: isPermanent ? "FAILED_PERMANENT" : "FAILED_RETRYABLE",
                    error: resp.error?.message,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                };

                if (isPermanent) {
                    console.log(`Deactivating invalid token for device ${device.id}`);
                    batch.update(db.collection("users").doc(userId).collection("devices").doc(device.id), {
                        isActive: false,
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });
                }
            }
        });

        // 3. Update Accounting & Determine Terminal State
        await eventRef.update(deviceAccountingUpdate);
        await batch.commit();

        const finalDoc = await eventRef.get();
        const finalData = finalDoc.data()!;
        const allAccounted = targetDevices.every(d => finalData.deviceAccounting[d.id].status === "SENT" || finalData.deviceAccounting[d.id].status === "FAILED_PERMANENT");

        if (allAccounted) {
            await eventRef.update({
                status: "SENT",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        } else {
            await eventRef.update({
                status: "PENDING", // Allow retry for retryable device failures
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        }

    } catch (error: any) {
        console.error(`Fatal dispatch error for event ${eventId}:`, error);
        await eventRef.update({
            status: "PENDING", // Back to pending for full retry if entire multicast failed
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
    if (!conversation || !conversation.participantIds) return;

    // SWIFT-022: Multi-recipient resolution (don't assume 2 people)
    const recipients = conversation.participantIds.filter((id: string) => id !== message.senderId);
    if (recipients.length === 0) return;

    // Fetch sender name from their profile
    const senderDoc = await db.collection("profiles").doc(message.senderId).get();
    const senderName = senderDoc.data()?.displayName || "Someone";

    const payload = {
        notification: {
            title: `New message from ${senderName}`,
            body: message.text,
        },
        data: {
            type: "MESSAGE",
            targetId: message.conversationId,
        }
    };

    // Fan-out to all recipients
    await Promise.all(recipients.map((recipientId: string) =>
        sendNotification(recipientId, payload, event.id)
    ));
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
