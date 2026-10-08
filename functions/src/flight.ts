/**
 * Swift Flight — server-authoritative game engine.
 *
 * The client can request a marketplace event be evaluated, but cannot
 * manufacture the event, points, altitude, redemption or wallet credit.
 *
 * Game-owned collections:
 *   flightState/{uid}
 *   flightEvents/{eventKey}
 *   flightRateLimits/{uid}:{sourceType}
 *   flightHistory/{historyId}
 */

import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

const db = admin.firestore();

export const REDEMPTION_THRESHOLD = 100_000;
export const REDEMPTION_AMOUNT_MINOR = 20_000; // M200
export const FEED_COST = 20;
export const STARTING_POINTS = 10;

export const BASE_REWARDS: Record<string, number> = {
    FOLLOW: 10,
    NEW_FOLLOWER: 10,
    CREATE_POST: 80,
    CREATE_LISTING: 150,
    SUCCESSFUL_SALE: 800,
};

/**
 * These are economic guardrails, not the source of truth for whether an
 * event is genuine. verifyEvent() remains authoritative for that.
 *
 * FOLLOW and NEW_FOLLOWER also get a stable relationship idempotency key,
 * so unfollow/refollow cannot create a fresh reward.
 */
export const ANTI_FARMING: Record<string, { maxPerDay: number }> = {
    FOLLOW: { maxPerDay: 20 },
    NEW_FOLLOWER: { maxPerDay: 200 },
    CREATE_POST: { maxPerDay: 5 },
    CREATE_LISTING: { maxPerDay: 10 },
    SUCCESSFUL_SALE: { maxPerDay: 500 },
};

export const ALTITUDE_LEVELS = [
    { level: 1, minFlight: 0, multiplier: 1.0, label: "Ground", env: "Rooftops" },
    { level: 2, minFlight: 2_000, multiplier: 1.0, label: "Rooftops", env: "Rooftops" },
    { level: 3, minFlight: 6_000, multiplier: 1.5, label: "Low sky", env: "Above rooftops" },
    { level: 4, minFlight: 14_000, multiplier: 1.5, label: "Rising", env: "Above rooftops" },
    { level: 5, minFlight: 28_000, multiplier: 2.0, label: "Clouds", env: "Clouds" },
    { level: 6, minFlight: 48_000, multiplier: 2.0, label: "High clouds", env: "Clouds" },
    { level: 7, minFlight: 65_000, multiplier: 3.0, label: "Storm", env: "Storm" },
    { level: 8, minFlight: 80_000, multiplier: 3.0, label: "Thunder", env: "Storm" },
    { level: 9, minFlight: 92_000, multiplier: 5.0, label: "Open sky", env: "Open sky" },
    { level: 10, minFlight: 98_000, multiplier: 5.0, label: "Summit", env: "Open sky" },
] as const;

export function altitudeForFlight(flightPoints: number): (typeof ALTITUDE_LEVELS)[number] {
    let result: (typeof ALTITUDE_LEVELS)[number] = ALTITUDE_LEVELS[0];
    for (const level of ALTITUDE_LEVELS) {
        if (flightPoints >= level.minFlight) result = level;
    }
    return result;
}

export function eventIdempotencyKey(
    uid: string,
    sourceType: string,
    sourceId: string,
    relationshipId?: string,
): string {
    // Relationship rewards use a stable target/follower identity rather than
    // a disposable follows/{id}, preventing unfollow/refollow farming.
    return relationshipId
        ? `${uid}:${sourceType}:relationship:${relationshipId}`
        : `${uid}:${sourceType}:${sourceId}`;
}

function assertRealUser(
    auth: { uid: string; token: admin.auth.DecodedIdToken } | undefined,
): string {
    if (!auth?.uid) {
        throw new HttpsError("unauthenticated", "Authentication required for Swift Flight.");
    }

    const provider = auth.token?.firebase?.sign_in_provider;
    if (!provider || provider === "anonymous") {
        throw new HttpsError(
            "permission-denied",
            "Swift Flight requires a real account. Please sign in or create an account.",
        );
    }

    return auth.uid;
}

export function initialFlightState(uid: string, now: FirebaseFirestore.FieldValue) {
    return {
        uid,
        currentPoints: STARTING_POINTS,
        flightPointsEarned: STARTING_POINTS,
        altitudeLevel: 1,
        altitudeLabel: "Ground",
        altitudeEnv: "Rooftops",
        multiplier: 1.0,
        flightNumber: 1,
        highestLevel: 1,
        isEligibleToRedeem: false,
        createdAt: now,
        updatedAt: now,
    };
}

/**
 * Atomically enforces a rolling 24-hour reward cap without requiring a
 * Firestore composite index. The counter belongs to Flight and is never
 * client-writable.
 */
async function assertWithinDailyLimit(
    tx: FirebaseFirestore.Transaction,
    uid: string,
    sourceType: string,
): Promise<void> {
    const rule = ANTI_FARMING[sourceType];
    if (!rule) return;

    const ref = db.collection("flightRateLimits").doc(`${uid}:${sourceType}`);
    const snap = await tx.get(ref);
    const nowMs = Date.now();

    if (!snap.exists) {
        tx.set(ref, { uid, sourceType, windowStartMs: nowMs, count: 1 });
        return;
    }

    const data = snap.data()!;
    const windowStartMs = Number(data.windowStartMs || 0);
    const count = Number(data.count || 0);

    if (nowMs - windowStartMs >= 24 * 60 * 60 * 1000) {
        tx.set(ref, { uid, sourceType, windowStartMs: nowMs, count: 1 });
        return;
    }

    if (count >= rule.maxPerDay) {
        throw new HttpsError(
            "resource-exhausted",
            `Daily limit reached for ${sourceType} rewards (${rule.maxPerDay}/24h).`,
        );
    }

    tx.update(ref, { count: count + 1 });
}

export const getFlightState = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const ref = db.collection("flightState").doc(uid);
    const now = admin.firestore.FieldValue.serverTimestamp();

    const snap = await ref.get();
    if (!snap.exists) {
        const state = initialFlightState(uid, now);
        await ref.set(state);
        return { ...state, isNew: true };
    }

    return snap.data();
});

export const processFlightEvent = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const { sourceType, sourceId } = request.data ?? {};

    if (
        typeof sourceType !== "string" ||
        typeof sourceId !== "string" ||
        !sourceType ||
        !sourceId
    ) {
        throw new HttpsError("invalid-argument", "sourceType and sourceId are required.");
    }

    if (!Object.prototype.hasOwnProperty.call(BASE_REWARDS, sourceType)) {
        throw new HttpsError(
            "invalid-argument",
            `Unknown or unsupported event type: ${sourceType}`,
        );
    }

    const stateRef = db.collection("flightState").doc(uid);
    const now = admin.firestore.FieldValue.serverTimestamp();

    return db.runTransaction(async (tx) => {
        /*
         * Verify the marketplace event first. The returned relationshipId is
         * used to make FOLLOW/NEW_FOLLOWER rewards relationship-scoped.
         */
        const relationshipId = await verifyEvent(tx, uid, sourceType, sourceId);
        const eventKey = eventIdempotencyKey(uid, sourceType, sourceId, relationshipId);
        const eventRef = db.collection("flightEvents").doc(eventKey);

        const eventSnap = await tx.get(eventRef);
        if (eventSnap.exists) {
            return { duplicate: true, awardedPoints: 0 };
        }

        await assertWithinDailyLimit(tx, uid, sourceType);

        const stateSnap = await tx.get(stateRef);
        const state = stateSnap.exists
            ? stateSnap.data()!
            : initialFlightState(uid, now);

        const flightPoints = Number(state.flightPointsEarned || 0);
        const altitude = altitudeForFlight(flightPoints);
        const awarded = Math.round(BASE_REWARDS[sourceType] * altitude.multiplier);

        const newCurrent = Number(state.currentPoints || 0) + awarded;
        const newFlightPoints = flightPoints + awarded;
        const newAltitude = altitudeForFlight(newFlightPoints);
        const newHighest = Math.max(Number(state.highestLevel || 1), newAltitude.level);

        const updates = {
            currentPoints: newCurrent,
            flightPointsEarned: newFlightPoints,
            altitudeLevel: newAltitude.level,
            altitudeLabel: newAltitude.label,
            altitudeEnv: newAltitude.env,
            multiplier: newAltitude.multiplier,
            highestLevel: newHighest,
            isEligibleToRedeem: newFlightPoints >= REDEMPTION_THRESHOLD,
            updatedAt: now,
        };

        if (stateSnap.exists) {
            tx.update(stateRef, updates);
        } else {
            tx.set(stateRef, {
                ...state,
                ...updates,
            });
        }

        tx.set(eventRef, {
            uid,
            sourceType,
            sourceId,
            relationshipId: relationshipId ?? null,
            awardedPoints: awarded,
            altitudeAtEvent: newAltitude.level,
            processedAt: now,
        });

        return {
            duplicate: false,
            awardedPoints: awarded,
            currentPoints: newCurrent,
            altitudeLevel: newAltitude.level,
            altitudeLabel: newAltitude.label,
            multiplier: newAltitude.multiplier,
            eligible: newCurrent >= REDEMPTION_THRESHOLD,
        };
    });
});

/**
 * Returns a stable relationship identity for relationship rewards.
 *
 * FOLLOW: the person this user followed.
 * NEW_FOLLOWER: the person who followed this user.
 *
 * Other event types return undefined and remain source-document scoped.
 */
async function verifyEvent(
    tx: FirebaseFirestore.Transaction,
    uid: string,
    sourceType: string,
    sourceId: string,
): Promise<string | undefined> {
    switch (sourceType) {
        case "SUCCESSFUL_SALE": {
            const snap = await tx.get(db.collection("orders").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Order not found.");
            const order = snap.data()!;
            if (order.sellerId !== uid) {
                throw new HttpsError("permission-denied", "Order does not belong to you.");
            }
            if (order.status !== "CONFIRMED") {
                throw new HttpsError("failed-precondition", "Order is not confirmed.");
            }
            if (order.inventoryStatus !== "COMMITTED") {
                throw new HttpsError("failed-precondition", "Order inventory not committed.");
            }
            return undefined;
        }

        case "CREATE_LISTING": {
            const snap = await tx.get(db.collection("listings").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Listing not found.");
            const listing = snap.data()!;
            if (listing.sellerId !== uid) {
                throw new HttpsError("permission-denied", "Listing does not belong to you.");
            }
            if (listing.status !== "ACTIVE") {
                throw new HttpsError("failed-precondition", "Listing is not active.");
            }
            return undefined;
        }

        case "CREATE_POST": {
            const snap = await tx.get(db.collection("posts").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Post not found.");
            const post = snap.data()!;
            if (post.authorId !== uid) {
                throw new HttpsError("permission-denied", "Post does not belong to you.");
            }
            if (!post.createdAt) {
                throw new HttpsError("failed-precondition", "Post is not published.");
            }
            return undefined;
        }

        case "FOLLOW": {
            const snap = await tx.get(db.collection("follows").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Follow record not found.");
            const follow = snap.data()!;
            if (follow.followerId !== uid) {
                throw new HttpsError("permission-denied", "Follow does not belong to you.");
            }
            if (!follow.followedId || typeof follow.followedId !== "string") {
                throw new HttpsError("failed-precondition", "Follow target is missing.");
            }
            return follow.followedId;
        }

        case "NEW_FOLLOWER": {
            const snap = await tx.get(db.collection("follows").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Follow record not found.");
            const follow = snap.data()!;
            if (follow.followedId !== uid) {
                throw new HttpsError("permission-denied", "This follow is not for your account.");
            }
            if (!follow.followerId || typeof follow.followerId !== "string") {
                throw new HttpsError("failed-precondition", "Follower identity is missing.");
            }
            return follow.followerId;
        }

        /*
         * SMART_LINK intentionally has no reward path.
         * There is currently no verified marketplace smart-link event authority.
         * It must not be reintroduced until the backend records a trustworthy
         * share/reach event.
         */
        default:
            throw new HttpsError(
                "invalid-argument",
                `Unverifiable event type: ${sourceType}`,
            );
    }
}

export const feedBird = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const stateRef = db.collection("flightState").doc(uid);
    const now = admin.firestore.FieldValue.serverTimestamp();

    return db.runTransaction(async (tx) => {
        const snap = await tx.get(stateRef);
        if (!snap.exists) {
            throw new HttpsError("not-found", "No active flight. Call getFlightState first.");
        }

        const state = snap.data()!;
        if (Number(state.currentPoints || 0) < FEED_COST) {
            throw new HttpsError(
                "failed-precondition",
                `Need at least ${FEED_COST} points to feed the bird.`,
            );
        }
        if (state.isEligibleToRedeem) {
            throw new HttpsError(
                "failed-precondition",
                "You have reached 100,000 points — redeem your flight first.",
            );
        }

        const newPoints = Number(state.currentPoints || 0) - FEED_COST;
        tx.update(stateRef, {
            currentPoints: newPoints,
            updatedAt: now,
        });

        return {
            fed: true,
            currentPoints: newPoints,
            altitudeLevel: state.altitudeLevel,
            altitudeLabel: state.altitudeLabel,
        };
    });
});

export const claimFlightRedemption = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const stateRef = db.collection("flightState").doc(uid);
    const walletRef = db.collection("wallets").doc(uid);
    const now = admin.firestore.FieldValue.serverTimestamp();

    return db.runTransaction(async (tx) => {
        const stateSnap = await tx.get(stateRef);
        const walletSnap = await tx.get(walletRef);

        if (!stateSnap.exists) {
            throw new HttpsError("not-found", "No active flight.");
        }

        const state = stateSnap.data()!;
        if (Number(state.flightPointsEarned || 0) < REDEMPTION_THRESHOLD) {
            throw new HttpsError(
                "failed-precondition",
                `Need ${REDEMPTION_THRESHOLD.toLocaleString()} flight points to redeem. You have ${Number(state.flightPointsEarned || 0).toLocaleString()}.`,
            );
        }

        if (!walletSnap.exists) {
            throw new HttpsError("not-found", "Wallet not found.");
        }

        const flightNumber = Number(state.flightNumber || 1);
        const currentBalance = Number(
            walletSnap.data()!.availableBalanceMinorUnits || 0,
        );

        const historyRef = db.collection("flightHistory").doc();
        tx.set(historyRef, {
            id: historyRef.id,
            uid,
            flightNumber,
            pointsAtRedemption: state.currentPoints,
            flightPointsEarned: state.flightPointsEarned || 0,
            highestLevel: state.highestLevel || 1,
            redemptionAmountMinorUnits: REDEMPTION_AMOUNT_MINOR,
            redeemedAt: now,
        });

        tx.update(walletRef, {
            availableBalanceMinorUnits: currentBalance + REDEMPTION_AMOUNT_MINOR,
            updatedAt: now,
        });

        const ledgerRef = db.collection("ledgerEntries").doc();
        tx.set(ledgerRef, {
            id: ledgerRef.id,
            debitAccount: "system_flight_rewards",
            creditAccount: `user_${uid}`,
            amountMinorUnits: REDEMPTION_AMOUNT_MINOR,
            currency: "LSL",
            reference: `FLIGHT_REDEMPTION_${uid}_FLIGHT_${flightNumber}`,
            timestamp: now,
        });

        tx.update(stateRef, {
            currentPoints: 0,
            flightPointsEarned: 0,
            altitudeLevel: 1,
            altitudeLabel: "Ground",
            altitudeEnv: "Rooftops",
            multiplier: 1.0,
            highestLevel: 1,
            flightNumber: flightNumber + 1,
            isEligibleToRedeem: false,
            lastRedeemedAt: now,
            updatedAt: now,
        });

        return {
            success: true,
            flightNumber,
            nextFlightNumber: flightNumber + 1,
            redeemedAmountMinorUnits: REDEMPTION_AMOUNT_MINOR,
            newWalletBalance: currentBalance + REDEMPTION_AMOUNT_MINOR,
        };
    });
});

export const getFlightHistory = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const snap = await db.collection("flightHistory")
        .where("uid", "==", uid)
        .orderBy("flightNumber", "desc")
        .limit(20)
        .get();

    return snap.docs.map((doc) => doc.data());
});
