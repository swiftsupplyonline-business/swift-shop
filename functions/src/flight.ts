import { onCall, HttpsError, CallableRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { assertAccountActive } from "./accountGuard";

if (!admin.apps.length) {
  admin.initializeApp();
}

const db = admin.firestore();

export const REDEMPTION_TARGET_POINTS = 100000;
export const FEED_POINT_COST = 20;
export const FEED_ALTITUDE_BOOST = 1;

export interface FlightState {
  uid: string;
  flightNumber: number;
  points: number;
  currentAltitude: number;
  highestAltitude: number;
  milestonesCompleted: number[];
  isCompleted: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface FlightRewardRule {
  basePoints: number;
  maxMultiplier: number;
}

export const REWARD_RULES: Record<string, FlightRewardRule> = {
  FOLLOW: { basePoints: 10, maxMultiplier: 3 },
  NEW_FOLLOWER: { basePoints: 10, maxMultiplier: 3 },
  INVITE: { basePoints: 20, maxMultiplier: 4 },
  SHARE_SMART_LINK: { basePoints: 40, maxMultiplier: 4 },
  CREATE_POST: { basePoints: 80, maxMultiplier: 5 },
  CREATE_LISTING: { basePoints: 150, maxMultiplier: 5 },
  SUCCESSFUL_SALE: { basePoints: 800, maxMultiplier: 5 }
};

/**
 * Reward types that have a server-side record the backend can verify.
 *
 * INVITE and SHARE_SMART_LINK are deliberately NOT here: no invite or share record exists on the
 * server today, so there is nothing to verify and awarding them would mean trusting the client.
 * They fail closed until a verifiable record (and its eligibility rules) is built.
 */
export const VERIFIABLE_REWARD_TYPES: ReadonlySet<string> = new Set([
  "FOLLOW",
  "NEW_FOLLOWER",
  "CREATE_POST",
  "CREATE_LISTING",
  "SUCCESSFUL_SALE"
]);

/** Listing statuses that mean the listing is not (or no longer) a real published listing. */
const NON_REWARDABLE_LISTING_STATUSES: ReadonlySet<string> = new Set(["DRAFT", "ARCHIVED", "SUSPENDED", "DELETED"]);

/** Order states written by confirmDelivery / settlement: the only point at which a sale is real. */
const SALE_COMPLETE_STATUSES: ReadonlySet<string> = new Set(["DELIVERED", "COMPLETED"]);
const SALE_PAID_STATUSES: ReadonlySet<string> = new Set(["PAID", "SUCCESS"]);

const SOURCE_ID_PATTERN = /^[A-Za-z0-9_-]{1,128}$/;

export function calculateAltitudeBand(altitude: number): { band: string; multiplier: number } {
  if (altitude < 3) return { band: "Ground / Rooftops", multiplier: 1.0 };
  if (altitude < 5) return { band: "Above Rooftops", multiplier: 1.5 };
  if (altitude < 7) return { band: "Clouds", multiplier: 2.0 };
  if (altitude < 9) return { band: "Storm", multiplier: 3.0 };
  return { band: "Open Sky", multiplier: 5.0 };
}

export function calculateAltitudeFromPoints(points: number): number {
  return Math.min(10, Math.floor(points / 10000) + 1);
}

/** Points for one event: base points x altitude multiplier, never above the rule's own cap. */
export function calculateAwardedPoints(rule: FlightRewardRule, altitude: number): number {
  const { multiplier } = calculateAltitudeBand(altitude);
  return Math.round(rule.basePoints * Math.min(multiplier, rule.maxMultiplier));
}

/**
 * Idempotency key for one reward event. Always scoped to the user, so one user's claim can never
 * collide with, suppress or duplicate another user's. `dedupeKey` identifies the underlying action
 * (the order, the post, or the other party of a follow), not a client-chosen document id.
 */
export function flightEventId(uid: string, sourceType: string, dedupeKey: string): string {
  return `${uid}__${sourceType}__${dedupeKey}`;
}

/** The pre-fix event id, shared by every user. Read only, to honour rewards already granted under it. */
function legacyFlightEventId(sourceType: string, sourceId: string): string {
  return `${sourceType}:${sourceId}`;
}

function checkAuth(request: CallableRequest<any>): string {
  const auth = request.auth;
  if (!auth || !auth.uid) {
    throw new HttpsError("unauthenticated", "Authentication required for Swift Flight.");
  }
  const token = auth.token as Record<string, any> | undefined;
  const isAnon = token?.firebase?.sign_in_provider === "anonymous" || token?.firebase?.is_anonymous === true || token?.is_anonymous === true;
  if (isAnon) {
    throw new HttpsError("permission-denied", "Anonymous users cannot participate in Swift Flight.");
  }
  return auth.uid;
}

function newFlightState(uid: string): FlightState {
  const now = Date.now();
  return {
    uid,
    flightNumber: 1,
    points: 0,
    currentAltitude: 1,
    highestAltitude: 1,
    milestonesCompleted: [],
    isCompleted: false,
    createdAt: now,
    updatedAt: now
  };
}

export async function getOrCreateFlightState(uid: string): Promise<FlightState> {
  const ref = db.collection("flightState").doc(uid);
  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (snap.exists) {
      return snap.data() as FlightState;
    }
    const created = newFlightState(uid);
    tx.set(ref, created);
    return created;
  });
}

export const getFlightState = onCall(async (request) => {
  const uid = checkAuth(request);
  const state = await getOrCreateFlightState(uid);
  return { ok: true, state };
});

export const feedBird = onCall(async (request) => {
  const uid = checkAuth(request);
  const ref = db.collection("flightState").doc(uid);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new HttpsError("not-found", "Flight state not found.");
    }

    const state = snap.data() as FlightState;
    if (state.points < FEED_POINT_COST) {
      throw new HttpsError("failed-precondition", `Insufficient points to feed bird. Required: ${FEED_POINT_COST}`);
    }

    const newPoints = state.points - FEED_POINT_COST;
    const newAltitude = Math.min(10, state.currentAltitude + FEED_ALTITUDE_BOOST);
    const newHighest = Math.max(state.highestAltitude, newAltitude);

    const updated: Partial<FlightState> = {
      points: newPoints,
      currentAltitude: newAltitude,
      highestAltitude: newHighest,
      updatedAt: Date.now()
    };

    tx.update(ref, updated);
    return { ok: true, state: { ...state, ...updated } };
  });
});

type Snap = FirebaseFirestore.DocumentSnapshot;

/**
 * Checks that a qualifying server-side record exists for this user and returns the user-scoped
 * dedupe key for the underlying action. Throws if the claim is not backed by a real record.
 * Pure over already-read snapshots, so every Firestore read stays before every write.
 */
function verifyAndKey(
  uid: string,
  sourceType: string,
  sourceId: string,
  sourceSnap: Snap,
  counterpartProfileSnap: Snap | null
): string {
  const data = sourceSnap.exists ? (sourceSnap.data() as Record<string, any>) : undefined;

  switch (sourceType) {
    case "CREATE_LISTING": {
      if (!data || data.sellerId !== uid) {
        throw new HttpsError("permission-denied", "Unverified or unauthorized listing event.");
      }
      if (NON_REWARDABLE_LISTING_STATUSES.has(String(data.status))) {
        throw new HttpsError("failed-precondition", "Listing is not published.");
      }
      return sourceId;
    }

    case "CREATE_POST": {
      if (!data || data.authorId !== uid) {
        throw new HttpsError("permission-denied", "Unverified or unauthorized post event.");
      }
      return sourceId;
    }

    case "SUCCESSFUL_SALE": {
      if (!data) {
        throw new HttpsError("permission-denied", "Unverified or unauthorized order sale event.");
      }
      const isParticipant = data.buyerId === uid || data.sellerId === uid;
      if (!isParticipant || data.buyerId === data.sellerId) {
        throw new HttpsError("permission-denied", "Unverified or unauthorized order sale event.");
      }
      // A sale only counts once the order is paid, delivered and settled by the backend.
      if (
        !SALE_COMPLETE_STATUSES.has(String(data.status)) ||
        data.settlementStatus !== "SETTLED" ||
        !SALE_PAID_STATUSES.has(String(data.paymentStatus))
      ) {
        throw new HttpsError("failed-precondition", "Sale is not complete: the order must be paid, delivered and settled.");
      }
      return sourceId;
    }

    case "FOLLOW": {
      // The caller must be the follower. The key is the person followed, so re-creating the same
      // follow under a different document id cannot earn twice.
      if (!data || data.followerId !== uid || typeof data.followedId !== "string" || data.followedId === uid) {
        throw new HttpsError("permission-denied", "Unverified follow event.");
      }
      if (!counterpartProfileSnap || !counterpartProfileSnap.exists) {
        throw new HttpsError("failed-precondition", "Followed account does not exist.");
      }
      return data.followedId;
    }

    case "NEW_FOLLOWER": {
      // The caller must be the person being followed. The key is the follower.
      if (!data || data.followedId !== uid || typeof data.followerId !== "string" || data.followerId === uid) {
        throw new HttpsError("permission-denied", "Unverified new-follower event.");
      }
      if (!counterpartProfileSnap || !counterpartProfileSnap.exists) {
        throw new HttpsError("failed-precondition", "Follower account does not exist.");
      }
      return data.followerId;
    }

    default:
      throw new HttpsError("failed-precondition", `Reward type ${sourceType} cannot be verified yet.`);
  }
}

export const processFlightEvent = onCall(async (request) => {
  const uid = checkAuth(request);
  const { sourceType, sourceId } = (request.data || {}) as { sourceType?: unknown; sourceId?: unknown };

  if (typeof sourceType !== "string" || typeof sourceId !== "string" || !sourceType || !sourceId) {
    throw new HttpsError("invalid-argument", "sourceType and sourceId are required.");
  }
  if (!SOURCE_ID_PATTERN.test(sourceId)) {
    throw new HttpsError("invalid-argument", "sourceId is not a valid record id.");
  }

  const rule = Object.prototype.hasOwnProperty.call(REWARD_RULES, sourceType) ? REWARD_RULES[sourceType] : undefined;
  if (!rule) {
    throw new HttpsError("invalid-argument", `Unknown reward sourceType: ${sourceType}`);
  }
  if (!VERIFIABLE_REWARD_TYPES.has(sourceType)) {
    throw new HttpsError("failed-precondition", `Reward type ${sourceType} is not available yet.`);
  }

  await assertAccountActive(uid);

  const sourceCollection =
    sourceType === "CREATE_LISTING" ? "listings" :
    sourceType === "CREATE_POST" ? "posts" :
    sourceType === "SUCCESSFUL_SALE" ? "orders" :
    "follows";
  const sourceRef = db.collection(sourceCollection).doc(sourceId);
  const stateRef = db.collection("flightState").doc(uid);
  const legacyEventRef = db.collection("flightEvents").doc(legacyFlightEventId(sourceType, sourceId));

  return db.runTransaction(async (tx) => {
    // ── All reads first (Firestore requires every read before any write) ──────────────────────
    const sourceSnap = await tx.get(sourceRef);
    const stateSnap = await tx.get(stateRef);
    const legacySnap = await tx.get(legacyEventRef);

    let counterpartProfileSnap: Snap | null = null;
    if (sourceType === "FOLLOW" || sourceType === "NEW_FOLLOWER") {
      const follow = sourceSnap.exists ? (sourceSnap.data() as Record<string, any>) : undefined;
      const counterpartId = sourceType === "FOLLOW" ? follow?.followedId : follow?.followerId;
      if (typeof counterpartId === "string" && SOURCE_ID_PATTERN.test(counterpartId)) {
        counterpartProfileSnap = await tx.get(db.collection("profiles").doc(counterpartId));
      }
    }

    // Verify the claim is backed by a real, qualifying record owned by this user.
    const dedupeKey = verifyAndKey(uid, sourceType, sourceId, sourceSnap, counterpartProfileSnap);

    const eventId = flightEventId(uid, sourceType, dedupeKey);
    const eventRef = db.collection("flightEvents").doc(eventId);
    const eventSnap = await tx.get(eventRef);

    const existingState = stateSnap.exists ? (stateSnap.data() as FlightState) : undefined;

    // Already rewarded: under the user-scoped key, or under the old shared key by this same user.
    // An old-key event written by a DIFFERENT user is ignored; that was the cross-account bug.
    const legacyOwnedByCaller = legacySnap.exists && legacySnap.data()?.uid === uid;
    if (eventSnap.exists || legacyOwnedByCaller) {
      return { ok: true, awardedPoints: 0, duplicate: true, state: existingState ?? newFlightState(uid) };
    }

    // ── Writes ────────────────────────────────────────────────────────────────────────────────
    const state: FlightState = existingState ?? newFlightState(uid);
    const awardedPoints = calculateAwardedPoints(rule, state.currentAltitude);
    const newPoints = state.points + awardedPoints;
    const computedAltitude = calculateAltitudeFromPoints(newPoints);
    const newAltitude = Math.max(state.currentAltitude, computedAltitude);
    const newHighest = Math.max(state.highestAltitude, newAltitude);
    const isCompleted = newPoints >= REDEMPTION_TARGET_POINTS;

    const updatedState: FlightState = {
      ...state,
      points: newPoints,
      currentAltitude: newAltitude,
      highestAltitude: newHighest,
      isCompleted,
      updatedAt: Date.now()
    };

    tx.set(eventRef, {
      eventId,
      uid,
      sourceType,
      sourceId,
      dedupeKey,
      awardedPoints,
      createdAt: Date.now()
    });
    tx.set(stateRef, updatedState);

    return { ok: true, awardedPoints, duplicate: false, state: updatedState };
  });
});

export const claimFlightRedemption = onCall(async (request) => {
  const uid = checkAuth(request);
  await assertAccountActive(uid);
  const stateRef = db.collection("flightState").doc(uid);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(stateRef);
    if (!snap.exists) {
      throw new HttpsError("not-found", "Flight state not found.");
    }

    const state = snap.data() as FlightState;
    const completedFlightNumber = state.flightNumber;
    const historyRef = db.collection("flightHistory").doc(`${uid}_flight_${completedFlightNumber}`);
    const historySnap = await tx.get(historyRef);

    if (state.points < REDEMPTION_TARGET_POINTS) {
      throw new HttpsError(
        "failed-precondition",
        `Flight completion target not reached. Required: ${REDEMPTION_TARGET_POINTS}, Current: ${state.points}`
      );
    }
    // Belt and braces: the flight number advances in this same transaction, so a second claim of
    // the same flight can only happen if something is already wrong. Refuse rather than overwrite.
    if (historySnap.exists) {
      throw new HttpsError("already-exists", "This flight has already been redeemed.");
    }

    const nextFlightNumber = completedFlightNumber + 1;

    tx.set(historyRef, {
      uid,
      flightNumber: completedFlightNumber,
      finalPoints: state.points,
      highestAltitude: state.highestAltitude,
      reward: "M200",
      // Recording the claim is not the payout. Fulfilment must be performed (and tracked) by a
      // funded settlement process; until then the claim is explicitly marked as not yet paid.
      fulfillmentStatus: "PENDING",
      completedAt: Date.now()
    });

    const resetState: Partial<FlightState> = {
      flightNumber: nextFlightNumber,
      points: 0,
      currentAltitude: 1,
      isCompleted: false,
      updatedAt: Date.now()
    };

    tx.update(stateRef, resetState);

    return {
      ok: true,
      redeemedAmount: "M200",
      fulfillmentStatus: "PENDING",
      completedFlightNumber,
      nextFlightNumber,
      state: { ...state, ...resetState }
    };
  });
});
