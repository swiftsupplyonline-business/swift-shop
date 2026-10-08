import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

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

function checkAuth(context: functions.https.CallableContext): string {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Authentication required for Swift Flight.");
  }
  if (context.auth.token?.firebase?.is_anonymous === true) {
    throw new functions.https.HttpsError("permission-denied", "Anonymous users cannot participate in Swift Flight.");
  }
  return context.auth.uid;
}

export async function getOrCreateFlightState(uid: string): Promise<FlightState> {
  const ref = db.collection("flightState").doc(uid);
  const snap = await ref.get();
  if (snap.exists) {
    return snap.data() as FlightState;
  }

  const newState: FlightState = {
    uid,
    flightNumber: 1,
    points: 0,
    currentAltitude: 1,
    highestAltitude: 1,
    milestonesCompleted: [],
    isCompleted: false,
    createdAt: Date.now(),
    updatedAt: Date.now()
  };

  await ref.set(newState);
  return newState;
}

export const getFlightState = functions.https.onCall(async (data, context) => {
  const uid = checkAuth(context);
  const state = await getOrCreateFlightState(uid);
  return { ok: true, state };
});

export const feedBird = functions.https.onCall(async (data, context) => {
  const uid = checkAuth(context);
  const ref = db.collection("flightState").doc(uid);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new functions.https.HttpsError("not-found", "Flight state not found.");
    }

    const state = snap.data() as FlightState;
    if (state.points < FEED_POINT_COST) {
      throw new functions.https.HttpsError("failed-precondition", `Insufficient points to feed bird. Required: ${FEED_POINT_COST}`);
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

export const processFlightEvent = functions.https.onCall(async (data, context) => {
  const uid = checkAuth(context);
  const { sourceType, sourceId } = data || {};

  if (!sourceType || !sourceId) {
    throw new functions.https.HttpsError("invalid-argument", "sourceType and sourceId are required.");
  }

  const rule = REWARD_RULES[sourceType];
  if (!rule) {
    throw new functions.https.HttpsError("invalid-argument", `Unknown reward sourceType: ${sourceType}`);
  }

  const eventId = `${sourceType}:${sourceId}`;
  const eventRef = db.collection("flightEvents").doc(eventId);
  const stateRef = db.collection("flightState").doc(uid);

  return db.runTransaction(async (tx) => {
    const eventSnap = await tx.get(eventRef);
    if (eventSnap.exists) {
      const existingStateSnap = await tx.get(stateRef);
      return { ok: true, awardedPoints: 0, duplicate: true, state: existingStateSnap.data() };
    }

    let state = (await tx.get(stateRef)).data() as FlightState;
    if (!state) {
      state = {
        uid,
        flightNumber: 1,
        points: 0,
        currentAltitude: 1,
        highestAltitude: 1,
        milestonesCompleted: [],
        isCompleted: false,
        createdAt: Date.now(),
        updatedAt: Date.now()
      };
      tx.set(stateRef, state);
    }

    const { multiplier } = calculateAltitudeBand(state.currentAltitude);
    const awardedPoints = Math.round(rule.basePoints * multiplier);
    const newPoints = state.points + awardedPoints;
    const computedAltitude = calculateAltitudeFromPoints(newPoints);
    const newAltitude = Math.max(state.currentAltitude, computedAltitude);
    const newHighest = Math.max(state.highestAltitude, newAltitude);
    const isCompleted = newPoints >= REDEMPTION_TARGET_POINTS;

    const updatedState: Partial<FlightState> = {
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
      awardedPoints,
      createdAt: Date.now()
    });

    tx.update(stateRef, updatedState);

    return {
      ok: true,
      awardedPoints,
      duplicate: false,
      state: { ...state, ...updatedState }
    };
  });
});

export const claimFlightRedemption = functions.https.onCall(async (data, context) => {
  const uid = checkAuth(context);
  const stateRef = db.collection("flightState").doc(uid);

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(stateRef);
    if (!snap.exists) {
      throw new functions.https.HttpsError("not-found", "Flight state not found.");
    }

    const state = snap.data() as FlightState;
    if (state.points < REDEMPTION_TARGET_POINTS) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        `Flight completion target not reached. Required: ${REDEMPTION_TARGET_POINTS}, Current: ${state.points}`
      );
    }

    const completedFlightNumber = state.flightNumber;
    const nextFlightNumber = completedFlightNumber + 1;

    const historyRef = db.collection("flightHistory").doc(`${uid}_flight_${completedFlightNumber}`);
    tx.set(historyRef, {
      uid,
      flightNumber: completedFlightNumber,
      finalPoints: state.points,
      highestAltitude: state.highestAltitude,
      reward: "M200",
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
      completedFlightNumber,
      nextFlightNumber,
      state: { ...state, ...resetState }
    };
  });
});
