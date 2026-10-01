import { onSchedule } from "firebase-functions/v2/scheduler";
import * as admin from "firebase-admin";
import { releaseInventory } from "./listing";

/**
 * Scheduled task to clean up expired inventory reservations.
 * Runs every 5 minutes.
 */
export const cleanupExpiredReservations = onSchedule("every 5 minutes", async (event) => {
    const db = admin.firestore();
    const now = admin.firestore.Timestamp.now();

    const expiredSnap = await db.collection("reservations")
        .where("status", "==", "ACTIVE")
        .where("expiresAt", "<", now)
        .limit(100)
        .get();

    if (expiredSnap.empty) return;

    for (const resDoc of expiredSnap.docs) {
        const res = resDoc.data();

        try {
            await db.runTransaction(async (transaction) => {
                const freshResDoc = await transaction.get(resDoc.ref);
                if (!freshResDoc.exists || freshResDoc.data()?.status !== "ACTIVE") return;

                const listingRef = db.collection("listings").doc(res.listingId);
                const listingSnap = await transaction.get(listingRef);

                if (listingSnap.exists) {
                    // Listing Engine is the sole authority for inventory changes. It also
                    // re-derives status (e.g. OUT_OF_STOCK -> ACTIVE) when stock frees up.
                    releaseInventory(transaction, listingRef, listingSnap.data()!, res.quantity, now);
                }

                transaction.update(resDoc.ref, {
                    status: "EXPIRED",
                    updatedAt: now
                });

                // Also update the order status if it was still RESERVED
                const orderRef = db.collection("orders").doc(res.orderId);
                const orderDoc = await transaction.get(orderRef);
                if (orderDoc.exists && orderDoc.data()?.status === "RESERVED") {
                    transaction.update(orderRef, {
                        status: "CANCELLED",
                        cancelReason: "Reservation expired",
                        updatedAt: now
                    });
                }
            });
        } catch (error) {
            console.error(`Failed to cleanup reservation ${resDoc.id}:`, error);
        }
    }
});
