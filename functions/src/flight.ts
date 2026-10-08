/**
 * SWIFT FLIGHT ENGINE — flight.ts
 *
 * Server-authoritative game engine. The client NEVER controls:
 *   points / altitude / flightNumber / redemption / event eligibility
 *
 * Security model:
 *   - Every callable verifies: (a) user is authenticated, (b) user is NOT
 *     anonymous, (c) the claimed marketplace event actually exists in
 *     Firestore and belongs to this user.
 *   - Anonymous detection uses sign_in_provider, not is_anonymous flag.
 *   - All state mutations happen inside Firestore transactions.
 *   - Event idempotency key = `{uid}:{sourceType}:{sourceId}` so the same
 *     event can only reward the same user once.
 *
 * Firestore collections:
 *   flightState/{uid}          — live game state (server-owned)
 *   flightEvents/{eventKey}    — processed-event ledger (idempotency)
 *   flightHistory/{historyId}  — completed flight records
 */

import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

const db = admin.firestore();

// ─── Constants ────────────────────────────────────────────────────────────────

const REDEMPTION_THRESHOLD = 100_000;
const REDEMPTION_AMOUNT_MINOR = 20_000; // M200 in lisente
const FEED_COST = 20;
const STARTING_POINTS = 10;

/** Base point values per verified event type. */
const BASE_REWARDS: Record<string, number> = {
    FOLLOW:          10,
    NEW_FOLLOWER:    10,
    SMART_LINK:      40,
    CREATE_POST:     80,
    CREATE_LISTING: 150,
    SUCCESSFUL_SALE: 800,
};

/**
 * Altitude levels 1–10 with multiplier and display label.
 * Level is derived from totalPointsEarned lifetime (not spendable points),
 * so feeding the bird doesn't reset the player's progression tier.
 */
const ALTITUDE_LEVELS = [
    { level: 1,  minLifetime: 0,       multiplier: 1.0, label: "Ground",       env: "Rooftops" },
    { level: 2,  minLifetime: 2_000,   multiplier: 1.0, label: "Rooftops",     env: "Rooftops" },
    { level: 3,  minLifetime: 6_000,   multiplier: 1.5, label: "Low sky",      env: "Above rooftops" },
    { level: 4,  minLifetime: 14_000,  multiplier: 1.5, label: "Rising",       env: "Above rooftops" },
    { level: 5,  minLifetime: 28_000,  multiplier: 2.0, label: "Clouds",       env: "Clouds" },
    { level: 6,  minLifetime: 48_000,  multiplier: 2.0, label: "High clouds",  env: "Clouds" },
    { level: 7,  minLifetime: 65_000,  multiplier: 3.0, label: "Storm",        env: "Storm" },
    { level: 8,  minLifetime: 80_000,  multiplier: 3.0, label: "Thunder",      env: "Storm" },
    { level: 9,  minLifetime: 92_000,  multiplier: 5.0, label: "Open sky",     env: "Open sky" },
    { level: 10, minLifetime: 98_000,  multiplier: 5.0, label: "Summit",       env: "Open sky" },
];

function altitudeForLifetime(lifetime: number) {
    let result = ALTITUDE_LEVELS[0];
    for (const lvl of ALTITUDE_LEVELS) {
        if (lifetime >= lvl.minLifetime) result = lvl;
    }
    return result;
}

// ─── Auth guard ───────────────────────────────────────────────────────────────

/**
 * Asserts the caller is a real, non-anonymous Firebase user.
 * Uses sign_in_provider — the correct field — not the is_anonymous flag
 * which can be absent or unreliable on custom tokens.
 */
function assertRealUser(auth: { uid: string; token: admin.auth.DecodedIdToken } | undefined): string {
    if (!auth?.uid) {
        throw new HttpsError("unauthenticated", "Authentication required for Swift Flight.");
    }
    const provider = auth.token?.firebase?.sign_in_provider;
    if (!provider || provider === "anonymous") {
        throw new HttpsError(
            "permission-denied",
            "Swift Flight requires a real account. Please sign in or create an account."
        );
    }
    return auth.uid;
}

// ─── State initialiser ────────────────────────────────────────────────────────

function initialState(uid: string, now: FirebaseFirestore.FieldValue) {
    return {
        uid,
        currentPoints:     STARTING_POINTS,
        totalPointsEarned: STARTING_POINTS,
        altitudeLevel:     1,
        altitudeLabel:     "Ground",
        altitudeEnv:       "Rooftops",
        multiplier:        1.0,
        flightNumber:      1,
        highestLevel:      1,
        isEligibleToRedeem: false,
        createdAt:         now,
        updatedAt:         now,
    };
}

// ─── getFlightState ───────────────────────────────────────────────────────────

export const getFlightState = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const ref = db.collection("flightState").doc(uid);
    const now = admin.firestore.FieldValue.serverTimestamp();

    const snap = await ref.get();
    if (!snap.exists) {
        // First visit: create initial state
        const state = initialState(uid, now);
        await ref.set(state);
        return { ...state, currentPoints: STARTING_POINTS, isNew: true };
    }
    return snap.data();
});

// ─── processFlightEvent ───────────────────────────────────────────────────────
//
// The ONLY way points are awarded. The client names an event; the server
// independently verifies the event in Firestore before awarding anything.
//
// sourceType: "SUCCESSFUL_SALE" | "CREATE_LISTING" | "CREATE_POST" |
//             "FOLLOW" | "NEW_FOLLOWER" | "SMART_LINK"
// sourceId:   the Firestore document ID of the actual event

export const processFlightEvent = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const { sourceType, sourceId } = request.data;

    if (!sourceType || !sourceId || typeof sourceType !== "string" || typeof sourceId !== "string") {
        throw new HttpsError("invalid-argument", "sourceType and sourceId are required.");
    }
    if (!BASE_REWARDS[sourceType]) {
        throw new HttpsError("invalid-argument", `Unknown event type: ${sourceType}`);
    }

    // Idempotency key scoped to this user — prevents cross-user claims
    const eventKey = `${uid}:${sourceType}:${sourceId}`;
    const eventRef  = db.collection("flightEvents").doc(eventKey);
    const stateRef  = db.collection("flightState").doc(uid);
    const now       = admin.firestore.FieldValue.serverTimestamp();

    return db.runTransaction(async (tx) => {
        // 1. Idempotency check
        const eventSnap = await tx.get(eventRef);
        if (eventSnap.exists) {
            return { duplicate: true, awardedPoints: 0 };
        }

        // 2. Server-side event verification — the client claim must match Firestore reality
        await verifyEvent(tx, uid, sourceType, sourceId);

        // 3. Load or initialise flight state
        const stateSnap = await tx.get(stateRef);
        const state = stateSnap.exists
            ? stateSnap.data()!
            : initialState(uid, now);

        // 4. Calculate reward
        const altitude   = altitudeForLifetime(state.totalPointsEarned || 0);
        const basePoints = BASE_REWARDS[sourceType];
        const awarded    = Math.round(basePoints * altitude.multiplier);

        const newCurrent   = (state.currentPoints  || 0) + awarded;
        const newLifetime  = (state.totalPointsEarned || 0) + awarded;
        const newAltitude  = altitudeForLifetime(newLifetime);
        const newHighest   = Math.max(state.highestLevel || 1, newAltitude.level);
        const eligible     = newCurrent >= REDEMPTION_THRESHOLD;

        const newState = {
            ...state,
            currentPoints:      newCurrent,
            totalPointsEarned:  newLifetime,
            altitudeLevel:      newAltitude.level,
            altitudeLabel:      newAltitude.label,
            altitudeEnv:        newAltitude.env,
            multiplier:         newAltitude.multiplier,
            highestLevel:       newHighest,
            isEligibleToRedeem: eligible,
            updatedAt:          now,
        };

        if (!stateSnap.exists) {
            tx.set(stateRef, newState);
        } else {
            tx.update(stateRef, {
                currentPoints:      newCurrent,
                totalPointsEarned:  newLifetime,
                altitudeLevel:      newAltitude.level,
                altitudeLabel:      newAltitude.label,
                altitudeEnv:        newAltitude.env,
                multiplier:         newAltitude.multiplier,
                highestLevel:       newHighest,
                isEligibleToRedeem: eligible,
                updatedAt:          now,
            });
        }

        // 5. Mark event processed
        tx.set(eventRef, {
            uid,
            sourceType,
            sourceId,
            awardedPoints: awarded,
            altitudeAtEvent: newAltitude.level,
            processedAt: now,
        });

        return {
            duplicate:      false,
            awardedPoints:  awarded,
            currentPoints:  newCurrent,
            altitudeLevel:  newAltitude.level,
            altitudeLabel:  newAltitude.label,
            multiplier:     newAltitude.multiplier,
            eligible,
        };
    });
});

// ─── Event verification (server reads Firestore — client cannot fake this) ────

async function verifyEvent(
    tx:         FirebaseFirestore.Transaction,
    uid:        string,
    sourceType: string,
    sourceId:   string,
): Promise<void> {
    switch (sourceType) {
        case "SUCCESSFUL_SALE": {
            // Order must exist, belong to this seller, be CONFIRMED and COMMITTED
            const snap = await tx.get(db.collection("orders").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Order not found.");
            const o = snap.data()!;
            if (o.sellerId !== uid)          throw new HttpsError("permission-denied", "Order does not belong to you.");
            if (o.status !== "CONFIRMED")    throw new HttpsError("failed-precondition", "Order is not confirmed.");
            if (o.inventoryStatus !== "COMMITTED") throw new HttpsError("failed-precondition", "Order inventory not committed.");
            break;
        }
        case "CREATE_LISTING": {
            // Listing must exist, belong to this seller, be ACTIVE
            const snap = await tx.get(db.collection("listings").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Listing not found.");
            const l = snap.data()!;
            if (l.sellerId !== uid) throw new HttpsError("permission-denied", "Listing does not belong to you.");
            if (l.status !== "ACTIVE") throw new HttpsError("failed-precondition", "Listing is not active.");
            break;
        }
        case "CREATE_POST": {
            // Post must exist and belong to this user
            const snap = await tx.get(db.collection("posts").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Post not found.");
            const p = snap.data()!;
            if (p.authorId !== uid) throw new HttpsError("permission-denied", "Post does not belong to you.");
            break;
        }
        case "FOLLOW": {
            // Follow document must exist and this user is the follower
            const snap = await tx.get(db.collection("follows").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Follow record not found.");
            const f = snap.data()!;
            if (f.followerId !== uid) throw new HttpsError("permission-denied", "Follow does not belong to you.");
            break;
        }
        case "NEW_FOLLOWER": {
            // Follow document must exist and this user is the one being followed
            const snap = await tx.get(db.collection("follows").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Follow record not found.");
            const f = snap.data()!;
            if (f.followingId !== uid) throw new HttpsError("permission-denied", "This follow is not for your account.");
            break;
        }
        case "SMART_LINK": {
            // Smart link share event must exist and belong to this user
            const snap = await tx.get(db.collection("smartLinkEvents").doc(sourceId));
            if (!snap.exists) throw new HttpsError("not-found", "Smart link event not found.");
            const e = snap.data()!;
            if (e.sharerId !== uid) throw new HttpsError("permission-denied", "Smart link event does not belong to you.");
            break;
        }
        default:
            throw new HttpsError("invalid-argument", `Unverifiable event type: ${sourceType}`);
    }
}

// ─── feedBird ─────────────────────────────────────────────────────────────────
//
// Spend FEED_COST points to explicitly feed the bird.
// Altitude is now derived from lifetime earnings, so feeding is a
// cosmetic/engagement action rather than a progression gate.
// We keep it as a deliberate player ritual.

export const feedBird = onCall(async (request) => {
    const uid      = assertRealUser(request.auth as any);
    const stateRef = db.collection("flightState").doc(uid);
    const now      = admin.firestore.FieldValue.serverTimestamp();

    return db.runTransaction(async (tx) => {
        const snap = await tx.get(stateRef);
        if (!snap.exists) throw new HttpsError("not-found", "No active flight. Call getFlightState first.");

        const state = snap.data()!;
        if ((state.currentPoints || 0) < FEED_COST) {
            throw new HttpsError("failed-precondition", `Need at least ${FEED_COST} points to feed the bird.`);
        }
        if (state.isEligibleToRedeem) {
            throw new HttpsError("failed-precondition", "You have reached 100,000 points — redeem your flight first!");
        }

        const newPoints = (state.currentPoints || 0) - FEED_COST;
        tx.update(stateRef, { currentPoints: newPoints, updatedAt: now });

        return {
            fed:           true,
            currentPoints: newPoints,
            altitudeLevel: state.altitudeLevel,
            altitudeLabel: state.altitudeLabel,
        };
    });
});

// ─── claimFlightRedemption ───────────────────────────────────────────────────
//
// Atomic M200 redemption. Guards:
//   - user is real
//   - currentPoints >= REDEMPTION_THRESHOLD
//   - wallet exists
//   - entire operation is one transaction (no double-claim possible)

export const claimFlightRedemption = onCall(async (request) => {
    const uid       = assertRealUser(request.auth as any);
    const stateRef  = db.collection("flightState").doc(uid);
    const walletRef = db.collection("wallets").doc(uid);
    const now       = admin.firestore.FieldValue.serverTimestamp();
    const nowMs     = Date.now();

    return db.runTransaction(async (tx) => {
        const [stateSnap, walletSnap] = await Promise.all([
            tx.get(stateRef),
            tx.get(walletRef),
        ]);

        if (!stateSnap.exists) throw new HttpsError("not-found", "No active flight.");
        const state = stateSnap.data()!;

        // Guard: must have enough points
        if ((state.currentPoints || 0) < REDEMPTION_THRESHOLD) {
            throw new HttpsError(
                "failed-precondition",
                `Need ${REDEMPTION_THRESHOLD.toLocaleString()} points to redeem. ` +
                `You have ${(state.currentPoints || 0).toLocaleString()}.`
            );
        }

        // Guard: wallet must exist
        if (!walletSnap.exists) throw new HttpsError("not-found", "Wallet not found.");

        const flightNumber = state.flightNumber || 1;

        // 1. Write history record
        const historyRef = db.collection("flightHistory").doc();
        tx.set(historyRef, {
            id:               historyRef.id,
            uid,
            flightNumber,
            pointsAtRedemption: state.currentPoints,
            totalPointsEarned:  state.totalPointsEarned || 0,
            highestLevel:       state.highestLevel || 1,
            redemptionAmountMinorUnits: REDEMPTION_AMOUNT_MINOR,
            redeemedAt:        now,
        });

        // 2. Credit wallet — atomic with state reset
        const currentBal = walletSnap.data()!.availableBalanceMinorUnits || 0;
        tx.update(walletRef, {
            availableBalanceMinorUnits: currentBal + REDEMPTION_AMOUNT_MINOR,
            updatedAt: now,
        });

        // 3. Ledger entry
        const ledgerId = db.collection("ledgerEntries").doc().id;
        tx.set(db.collection("ledgerEntries").doc(ledgerId), {
            id:            ledgerId,
            debitAccount:  "system_flight_rewards",
            creditAccount: `user_${uid}`,
            amountMinorUnits: REDEMPTION_AMOUNT_MINOR,
            currency:      "LSL",
            reference:     `FLIGHT_REDEMPTION_${uid}_FLIGHT_${flightNumber}`,
            timestamp:     now,
        });

        // 4. Reset flight state for next flight
        tx.update(stateRef, {
            currentPoints:      0,
            totalPointsEarned:  0,        // lifetime resets per flight
            altitudeLevel:      1,
            altitudeLabel:      "Ground",
            altitudeEnv:        "Rooftops",
            multiplier:         1.0,
            highestLevel:       1,
            flightNumber:       flightNumber + 1,
            isEligibleToRedeem: false,
            lastRedeemedAt:     now,
            updatedAt:          now,
        });

        return {
            success:         true,
            flightNumber,
            nextFlightNumber: flightNumber + 1,
            redeemedAmountMinorUnits: REDEMPTION_AMOUNT_MINOR,
            newWalletBalance: currentBal + REDEMPTION_AMOUNT_MINOR,
        };
    });
});

// ─── getFlightHistory ────────────────────────────────────────────────────────

export const getFlightHistory = onCall(async (request) => {
    const uid = assertRealUser(request.auth as any);
    const snap = await db.collection("flightHistory")
        .where("uid", "==", uid)
        .orderBy("flightNumber", "desc")
        .limit(20)
        .get();
    return snap.docs.map(d => d.data());
});
