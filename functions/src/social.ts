import { onDocumentCreated, onDocumentDeleted } from "firebase-functions/v2/firestore";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * Hardened publishPost Cloud Function.
 * Authoritatively establishes author identity, timestamps, and initial counters.
 */
export const publishPost = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const postData = request.data;
    const db = admin.firestore();

    const postId = db.collection("posts").doc().id;
    const now = admin.firestore.FieldValue.serverTimestamp();

    const newPost = {
        // Client-owned content fields
        authorName: postData.authorName || "",
        authorAvatarUrl: postData.authorAvatarUrl || "",
        authorTier: postData.authorTier || "BASIC",
        shopId: postData.shopId || "",
        type: postData.type || "IMAGE",
        caption: postData.caption || "",
        mediaUrls: postData.mediaUrls || [],
        videoUrl: postData.videoUrl || "",
        thumbnailUrl: postData.thumbnailUrl || "",
        videoDurationMs: postData.videoDurationMs || 0,
        hashtags: postData.hashtags || [],
        mentions: postData.mentions || [],
        taggedListings: postData.taggedListings || [],
        taggedShops: postData.taggedShops || [],

        // Server-owned fields (Harden)
        id: postId,
        authorId: auth.uid,
        createdAt: now,
        updatedAt: now,
        likeCount: 0,
        commentCount: 0,
        bookmarkCount: 0,
        reshareCount: 0,
        isSponsored: false,
        rankingScore: 0
    };

    await db.collection("posts").doc(postId).set(newPost);
    return postId;
});

/**
 * Idempotent likePost Cloud Function.
 */
export const likePost = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { postId } = request.data;
    if (!postId) throw new HttpsError("invalid-argument", "Missing postId");

    const db = admin.firestore();
    const uid = auth.uid;
    const relationshipId = `post_${postId}`;

    try {
        await db.runTransaction(async (transaction) => {
            const postRef = db.collection("posts").doc(postId);
            const likeRef = db.collection("users").doc(uid).collection("likes").doc(relationshipId);

            const [postDoc, likeDoc] = await Promise.all([
                transaction.get(postRef),
                transaction.get(likeRef)
            ]);

            if (!postDoc.exists) throw new Error("Post not found");
            if (likeDoc.exists) return; // Idempotent: already liked

            transaction.set(likeRef, {
                uid,
                contentId: postId,
                type: "POST",
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(postRef, {
                likeCount: admin.firestore.FieldValue.increment(1),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Idempotent unlikePost Cloud Function.
 */
export const unlikePost = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { postId } = request.data;
    if (!postId) throw new HttpsError("invalid-argument", "Missing postId");

    const db = admin.firestore();
    const uid = auth.uid;
    const relationshipId = `post_${postId}`;

    try {
        await db.runTransaction(async (transaction) => {
            const postRef = db.collection("posts").doc(postId);
            const likeRef = db.collection("users").doc(uid).collection("likes").doc(relationshipId);

            const [postDoc, likeDoc] = await Promise.all([
                transaction.get(postRef),
                transaction.get(likeRef)
            ]);

            if (!postDoc.exists) throw new Error("Post not found");
            if (!likeDoc.exists) return; // Idempotent: not liked

            transaction.delete(likeRef);

            const currentLikeCount = postDoc.data()?.likeCount || 0;
            transaction.update(postRef, {
                likeCount: Math.max(0, currentLikeCount - 1),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Idempotent bookmarkPost Cloud Function.
 */
export const bookmarkPost = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { postId } = request.data;
    if (!postId) throw new HttpsError("invalid-argument", "Missing postId");

    const db = admin.firestore();
    const uid = auth.uid;
    const relationshipId = `post_${postId}`;

    try {
        await db.runTransaction(async (transaction) => {
            const postRef = db.collection("posts").doc(postId);
            const bookmarkRef = db.collection("users").doc(uid).collection("bookmarks").doc(relationshipId);

            const [postDoc, bookmarkDoc] = await Promise.all([
                transaction.get(postRef),
                transaction.get(bookmarkRef)
            ]);

            if (!postDoc.exists) throw new Error("Post not found");
            if (bookmarkDoc.exists) return; // Idempotent

            transaction.set(bookmarkRef, {
                uid,
                contentId: postId,
                type: "POST",
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(postRef, {
                bookmarkCount: admin.firestore.FieldValue.increment(1),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Idempotent unbookmarkPost Cloud Function.
 */
export const unbookmarkPost = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { postId } = request.data;
    if (!postId) throw new HttpsError("invalid-argument", "Missing postId");

    const db = admin.firestore();
    const uid = auth.uid;
    const relationshipId = `post_${postId}`;

    try {
        await db.runTransaction(async (transaction) => {
            const postRef = db.collection("posts").doc(postId);
            const bookmarkRef = db.collection("users").doc(uid).collection("bookmarks").doc(relationshipId);

            const [postDoc, bookmarkDoc] = await Promise.all([
                transaction.get(postRef),
                transaction.get(bookmarkRef)
            ]);

            if (!postDoc.exists) throw new Error("Post not found");
            if (!bookmarkDoc.exists) return; // Idempotent

            transaction.delete(bookmarkRef);

            const currentBookmarkCount = postDoc.data()?.bookmarkCount || 0;
            transaction.update(postRef, {
                bookmarkCount: Math.max(0, currentBookmarkCount - 1),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Transactional, server-authoritative creation of a Post comment.
 */
export const createPostComment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { postId, text, id: commentId } = request.data;
    if (!postId || !text) throw new HttpsError("invalid-argument", "Missing postId or text");

    const db = admin.firestore();
    const uid = auth.uid;

    try {
        await db.runTransaction(async (transaction) => {
            const postRef = db.collection("posts").doc(postId);
            const commentRef = db.collection("comments").doc(commentId || db.collection("comments").doc().id);

            const [postDoc, commentDoc] = await Promise.all([
                transaction.get(postRef),
                transaction.get(commentRef)
            ]);

            if (!postDoc.exists) throw new Error("Post not found");
            if (commentDoc.exists) return; // Idempotent

            const now = admin.firestore.FieldValue.serverTimestamp();

            // Server-authoritative comment document
            transaction.set(commentRef, {
                id: commentRef.id,
                postId,
                authorId: uid,
                authorName: request.data.authorName || "Anonymous", // Snapshots as per requirement 23
                authorAvatarUrl: request.data.authorAvatarUrl || "",
                text,
                parentCommentId: request.data.parentCommentId || "",
                replyCount: 0,
                likeCount: 0,
                createdAt: now,
                updatedAt: now
            });

            transaction.update(postRef, {
                commentCount: admin.firestore.FieldValue.increment(1),
                updatedAt: now
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Transactional, server-authoritative deletion of a Post comment.
 */
export const deletePostComment = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { commentId } = request.data;
    if (!commentId) throw new HttpsError("invalid-argument", "Missing commentId");

    const db = admin.firestore();
    const uid = auth.uid;

    try {
        await db.runTransaction(async (transaction) => {
            const commentRef = db.collection("comments").doc(commentId);
            const commentDoc = await transaction.get(commentRef);

            if (!commentDoc.exists) return; // Idempotent

            const commentData = commentDoc.data()!;
            if (commentData.authorId !== uid && auth.token.admin !== true) {
                throw new Error("Unauthorized");
            }

            const postId = commentData.postId;
            if (!postId) throw new Error("Comment is not associated with a Post");

            const postRef = db.collection("posts").doc(postId);
            const postDoc = await transaction.get(postRef);

            transaction.delete(commentRef);

            if (postDoc.exists) {
                const currentCount = postDoc.data()?.commentCount || 0;
                transaction.update(postRef, {
                    commentCount: Math.max(0, currentCount - 1),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

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
