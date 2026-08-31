import { onDocumentCreated } from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";

/**
 * Triggered when a new user document is created in Firestore.
 * Provision a server-authoritative wallet document for the user.
 */
export const provisionNewUser = onDocumentCreated({
    document: "users/{uid}",
    region: "us-central1"
}, async (event) => {
    const uid = event.params.uid;
    if (!uid) return;

    const db = admin.firestore();
    const walletRef = db.collection("wallets").doc(uid);
    const profileRef = db.collection("profiles").doc(uid);
    const userData = event.data?.data();

    try {
        await db.runTransaction(async (transaction) => {
            const walletDoc = await transaction.get(walletRef);

            if (!walletDoc.exists) {
                console.log(`Provisioning initial wallet for user ${uid}.`);
                transaction.set(walletRef, {
                    id: uid,
                    userId: uid,
                    availableBalanceMinorUnits: 0,
                    pendingBalanceMinorUnits: 0,
                    currency: "LSL",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }

            const profileDoc = await transaction.get(profileRef);
            if (!profileDoc.exists) {
                console.log(`Provisioning initial profile for user ${uid}.`);
                transaction.set(profileRef, {
                    uid,
                    displayName: userData?.displayName || "",
                    avatarUrl: userData?.photoUrl || "",
                    tier: "BASIC",
                    followerCount: 0,
                    followingCount: 0,
                    shopCount: 0,
                    postCount: 0,
                    activeListingCount: 0,
                    totalDeliveries: 0,
                    reputationScore: 0,
                    createdAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
        });
    } catch (error) {
        console.error(`Failed to provision user resources for ${uid}:`, error);
    }
});
