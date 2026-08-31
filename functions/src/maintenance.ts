import { onCall, HttpsError } from "firebase-functions/v2/https";
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
