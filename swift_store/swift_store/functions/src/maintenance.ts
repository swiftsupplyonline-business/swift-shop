import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as admin from "firebase-admin";

/**
 * ADMIN ONLY: Maintenance function to reconcile profile counters with actual document counts.
 * Repairs: activeListingCount, shopCount, followerCount, followingCount.
 */
export const syncProfileCounters = onCall(async (request) => {
    // 1. Authorization Gate
    const auth = request.auth;
    if (!auth || !auth.token.admin) {
        throw new HttpsError("permission-denied", "Admin authorization required for maintenance.");
    }

    const { targetUid } = request.data;
    if (!targetUid) throw new HttpsError("invalid-argument", "targetUid required");

    const db = admin.firestore();

    try {
        // Fetch canonical counts
        const [activeListings, userShops, followers, following] = await Promise.all([
            db.collection("listings")
                .where("sellerId", "==", targetUid)
                .where("isAvailable", "==", true)
                .count().get(),
            db.collection("shops")
                .where("ownerId", "==", targetUid)
                .count().get(),
            db.collection("follows")
                .where("followedId", "==", targetUid)
                .count().get(),
            db.collection("follows")
                .where("followerId", "==", targetUid)
                .count().get()
        ]);

        const counts = {
            activeListingCount: activeListings.data().count,
            shopCount: userShops.data().count,
            followerCount: followers.data().count,
            followingCount: following.data().count,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        };

        // Update authoritative profile
        await db.collection("profiles").doc(targetUid).update(counts);

        return {
            success: true,
            uid: targetUid,
            reconciled: counts
        };

    } catch (error: any) {
        console.error("Maintenance failed:", error);
        throw new HttpsError("internal", error.message);
    }
});

/**
 * SCHEDULED: Cleans up expired ACTIVE reservations and releases inventory.
 * Runs every 5 minutes.
 */
export const cleanupExpiredReservations = onSchedule("every 5 minutes", async (event) => {
    const db = admin.firestore();
    const now = admin.firestore.Timestamp.now();

    try {
        const expiredQuery = await db.collection("reservations")
            .where("status", "==", "ACTIVE")
            .where("expiresAt", "<", now)
            .limit(100) // Batch processing
            .get();

        if (expiredQuery.empty) return;

        console.log(`Processing ${expiredQuery.size} expired reservations...`);

        for (const resDoc of expiredQuery.docs) {
            await db.runTransaction(async (transaction) => {
                const freshResDoc = await transaction.get(resDoc.ref);
                const freshRes = freshResDoc.data()!;

                if (freshRes.status !== "ACTIVE") return; // Race winner (Payment Success)

                const listingRef = db.collection("listings").doc(freshRes.listingId);
                const listingSnap = await transaction.get(listingRef);
                if (!listingSnap.exists) {
                    transaction.update(resDoc.ref, { status: "EXPIRED", updatedAt: now });
                    return;
                }

                const lData = listingSnap.data()!;
                const currentTotal = lData.totalQuantity;
                const currentReserved = lData.reservedQuantity;

                // REAPER SAFETY: If listing is legacy/malformed, skip mutation to prevent corruption.
                if (currentTotal === undefined || currentReserved === undefined) {
                    console.error(`[REAPER ABORT] Res ${freshRes.id} points to legacy listing ${freshRes.listingId}. Manual reconciliation required.`);
                    transaction.update(resDoc.ref, { status: "EXPIRED", updatedAt: now });
                    return;
                }

                const newReserved = Math.max(0, currentReserved - freshRes.quantity);

                transaction.update(resDoc.ref, { status: "EXPIRED", updatedAt: now });
                transaction.update(listingRef, {
                    reservedQuantity: newReserved,
                    stockQuantity: currentTotal - newReserved,
                    updatedAt: now
                });

                // Also update the order status if it's still RESERVED
                const orderRef = db.collection("orders").doc(freshRes.orderId);
                const orderSnap = await transaction.get(orderRef);
                if (orderSnap.exists && (orderSnap.data()?.status === "RESERVED" || orderSnap.data()?.status === "PENDING")) {
                    transaction.update(orderRef, { status: "CANCELLED", cancelReason: "TTL_EXPIRED", updatedAt: now });
                }
            });
        }
    } catch (error) {
        console.error("Cleanup failed:", error);
    }
});
