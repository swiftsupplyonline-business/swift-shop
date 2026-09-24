import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * Returns operational dashboard statistics for platform admins.
 */
export const getAdminDashboardStats = onCall(async (request) => {
    const auth = request.auth;
    if (!auth || auth.token?.admin !== true) {
        throw new HttpsError("permission-denied", "Admin authorization required");
    }

    const db = admin.firestore();

    const [usersSnap, shopsSnap, listingsSnap, ordersSnap] = await Promise.all([
        db.collection("users").count().get(),
        db.collection("shops").count().get(),
        db.collection("listings").count().get(),
        db.collection("orders").count().get()
    ]);

    return {
        success: true,
        stats: {
            totalUsers: usersSnap.data().count,
            totalShops: shopsSnap.data().count,
            totalListings: listingsSnap.data().count,
            totalOrders: ordersSnap.data().count,
            timestamp: Date.now()
        }
    };
});

/**
 * Moderates a marketplace listing (flag, archive, or unpublish).
 */
export const moderateListing = onCall(async (request) => {
    const auth = request.auth;
    if (!auth || auth.token?.admin !== true) {
        throw new HttpsError("permission-denied", "Admin authorization required");
    }

    const { listingId, action, reason } = request.data;
    if (!listingId || !action) {
        throw new HttpsError("invalid-argument", "listingId and action required");
    }

    const db = admin.firestore();
    const listingRef = db.collection("listings").doc(listingId);
    const listingDoc = await listingRef.get();

    if (!listingDoc.exists) {
        throw new HttpsError("not-found", "Listing not found");
    }

    let statusUpdate = "";
    if (action === "FLAG") statusUpdate = "FLAGGED";
    else if (action === "ARCHIVE") statusUpdate = "ARCHIVED";
    else if (action === "APPROVE") statusUpdate = "ACTIVE";
    else throw new HttpsError("invalid-argument", "Invalid moderation action");

    await listingRef.update({
        moderationStatus: statusUpdate,
        moderatedBy: auth.uid,
        moderationReason: reason || "",
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // Record audit log entry
    await db.collection("auditLogs").add({
        action: `LISTING_MODERATION_${action}`,
        targetId: listingId,
        performedBy: auth.uid,
        reason: reason || "",
        createdAt: admin.firestore.FieldValue.serverTimestamp()
    });

    return { success: true, listingId, moderationStatus: statusUpdate };
});
