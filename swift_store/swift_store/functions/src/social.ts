import { onDocumentCreated, onDocumentDeleted } from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";

/**
 * Triggered when a new follow is recorded.
 * Atomically increments followingCount for the follower and followerCount for the followed.
 */
export const onFollowCreated = onDocumentCreated("follows/{followId}", async (event) => {
    const data = event.data?.data();
    if (!data) return;

    const { followerId, followedId } = data;
    const db = admin.firestore();

    const batch = db.batch();

    // Increment follower's following count
    batch.update(db.collection("profiles").doc(followerId), {
        followingCount: admin.firestore.FieldValue.increment(1),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // Increment followed user's follower count
    batch.update(db.collection("profiles").doc(followedId), {
        followerCount: admin.firestore.FieldValue.increment(1),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    await batch.commit();
});

/**
 * Triggered when a follow is removed.
 */
export const onFollowDeleted = onDocumentDeleted("follows/{followId}", async (event) => {
    const data = event.data?.data();
    if (!data) return;

    const { followerId, followedId } = data;
    const db = admin.firestore();

    const batch = db.batch();

    batch.update(db.collection("profiles").doc(followerId), {
        followingCount: admin.firestore.FieldValue.increment(-1),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    batch.update(db.collection("profiles").doc(followedId), {
        followerCount: admin.firestore.FieldValue.increment(-1),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    await batch.commit();
});
