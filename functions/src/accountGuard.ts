import { HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/** Rejects suspended/banned accounts from moving money (users/{uid}.accountStatus, default ACTIVE). */
export async function assertAccountActive(uid: string): Promise<void> {
    const snap = await admin.firestore().collection("users").doc(uid).get();
    const status = snap.exists ? (snap.data()!.accountStatus ?? "ACTIVE") : "ACTIVE";
    if (status !== "ACTIVE") throw new HttpsError("permission-denied", "Account is not active.");
}
