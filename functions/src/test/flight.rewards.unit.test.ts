// In-memory Firestore fake. Like the real SDK it (a) throws on any read issued after a write in a
// transaction and (b) retries a transaction whose reads were changed by a concurrent commit, so the
// concurrency tests below exercise real optimistic-contention behaviour.
const store: Record<string, any> = {};
const versions: Record<string, number> = {};
jest.mock("firebase-admin", () => {
  const clone = (v: any) => (v === undefined ? undefined : structuredClone(v));
  const ref = (col: string, id: string) => {
    const path = `${col}/${id}`;
    return {
      path,
      id,
      get: async () => ({ exists: path in store, data: () => clone(store[path]) }),
    };
  };
  const bump = (p: string) => { versions[p] = (versions[p] ?? 0) + 1; };
  const db = {
    collection: (col: string) => ({ doc: (id: string) => ref(col, id) }),
    runTransaction: async (fn: any) => {
      for (let attempt = 0; attempt < 8; attempt++) {
        const reads = new Map<string, number>();
        const writes: Array<() => void> = [];
        const tx = {
          get: async (r: any) => {
            if (writes.length) throw new Error("Firestore transactions require all reads to be executed before all writes.");
            reads.set(r.path, versions[r.path] ?? 0);
            await Promise.resolve(); // yield so concurrent calls genuinely interleave
            return { exists: r.path in store, data: () => clone(store[r.path]) };
          },
          set: (r: any, d: any, o?: any) => writes.push(() => {
            store[r.path] = o?.merge ? { ...store[r.path], ...clone(d) } : clone(d);
            bump(r.path);
          }),
          update: (r: any, u: any) => writes.push(() => {
            if (!(r.path in store)) throw new Error(`NOT_FOUND: ${r.path}`);
            store[r.path] = { ...store[r.path], ...clone(u) };
            bump(r.path);
          }),
        };
        const out = await fn(tx);
        const conflict = [...reads].some(([p, v]) => (versions[p] ?? 0) !== v);
        if (conflict) continue; // another commit touched something we read: retry
        writes.forEach((w) => w());
        return out;
      }
      throw new Error("transaction contention: gave up");
    },
  };
  const firestore: any = () => db;
  firestore.FieldValue = { serverTimestamp: () => "TS", increment: (n: number) => n };
  return { firestore, apps: [1], initializeApp: jest.fn() };
});

import {
  processFlightEvent,
  claimFlightRedemption,
  getFlightState,
  feedBird,
  flightEventId,
  calculateAwardedPoints,
  REWARD_RULES,
  REDEMPTION_TARGET_POINTS,
} from "../flight";

type Auth = { uid: string; token: Record<string, any> } | undefined;
const asUser = (uid: string): Auth => ({ uid, token: {} });

const process = (uid: string | undefined, data: any, auth?: Auth) =>
  (processFlightEvent as any).run({ data, auth: auth === undefined && uid ? asUser(uid) : auth });
const redeem = (uid: string) => (claimFlightRedemption as any).run({ data: {}, auth: asUser(uid) });

const stateOf = (uid: string) => store[`flightState/${uid}`];
const eventDocs = () => Object.keys(store).filter((k) => k.startsWith("flightEvents/"));

const settledOrder = (over: Record<string, any> = {}) => ({
  buyerId: "buyer1",
  sellerId: "seller1",
  status: "DELIVERED",
  paymentStatus: "SUCCESS",
  settlementStatus: "SETTLED",
  ...over,
});

beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  for (const k of Object.keys(versions)) delete versions[k];
  store["profiles/seller1"] = { uid: "seller1" };
  store["profiles/buyer1"] = { uid: "buyer1" };
  store["profiles/alice"] = { uid: "alice" };
  store["profiles/bob"] = { uid: "bob" };
});

describe("calculateAwardedPoints: per-type multiplier caps", () => {
  test("matches the ranges advertised in the Flight UI", () => {
    expect(calculateAwardedPoints(REWARD_RULES.FOLLOW, 1)).toBe(10);
    expect(calculateAwardedPoints(REWARD_RULES.FOLLOW, 10)).toBe(30); // capped at 3x, not the 5x band
    expect(calculateAwardedPoints(REWARD_RULES.SHARE_SMART_LINK, 10)).toBe(160); // 40 x 4, not 200
    expect(calculateAwardedPoints(REWARD_RULES.CREATE_LISTING, 10)).toBe(750);
    expect(calculateAwardedPoints(REWARD_RULES.SUCCESSFUL_SALE, 10)).toBe(4000);
  });
});

describe("processFlightEvent: authentication", () => {
  test("rejects unauthenticated callers", async () => {
    await expect(process(undefined, { sourceType: "CREATE_POST", sourceId: "p1" }, undefined)).rejects.toMatchObject({ code: "unauthenticated" });
  });

  test("rejects anonymous callers", async () => {
    store["posts/p1"] = { authorId: "alice" };
    const anon = { uid: "alice", token: { firebase: { sign_in_provider: "anonymous" } } };
    await expect(process("alice", { sourceType: "CREATE_POST", sourceId: "p1" }, anon)).rejects.toMatchObject({ code: "permission-denied" });
    expect(stateOf("alice")).toBeUndefined();
  });

  test("rejects suspended accounts", async () => {
    store["users/alice"] = { accountStatus: "SUSPENDED" };
    store["posts/p1"] = { authorId: "alice" };
    await expect(process("alice", { sourceType: "CREATE_POST", sourceId: "p1" })).rejects.toMatchObject({ code: "permission-denied" });
    expect(eventDocs()).toHaveLength(0);
  });
});

describe("processFlightEvent: input validation (forged events)", () => {
  test.each([
    [{ sourceType: "CREATE_POST" }],
    [{ sourceId: "p1" }],
    [{ sourceType: 5, sourceId: "p1" }],
    [{ sourceType: "CREATE_POST", sourceId: { $ne: "x" } }],
    [{ sourceType: "CREATE_POST", sourceId: "a/b" }],
    [{ sourceType: "CREATE_POST", sourceId: "x".repeat(200) }],
    [undefined],
  ])("rejects malformed payload %j", async (payload) => {
    await expect(process("alice", payload)).rejects.toMatchObject({ code: "invalid-argument" });
  });

  test.each(["NOPE", "__proto__", "constructor", "toString", "hasOwnProperty"])("rejects unknown sourceType %s", async (t) => {
    await expect(process("alice", { sourceType: t, sourceId: "x" })).rejects.toMatchObject({ code: "invalid-argument" });
  });

  test("INVITE and SHARE_SMART_LINK fail closed: no server record exists to verify them", async () => {
    for (const sourceType of ["INVITE", "SHARE_SMART_LINK"]) {
      await expect(process("alice", { sourceType, sourceId: "anything" })).rejects.toMatchObject({ code: "failed-precondition" });
    }
    expect(stateOf("alice")).toBeUndefined();
    expect(eventDocs()).toHaveLength(0);
  });
});

describe("processFlightEvent: CREATE_LISTING / CREATE_POST", () => {
  test("awards the owner once and treats a repeat as a duplicate", async () => {
    store["listings/l1"] = { sellerId: "alice", status: "ACTIVE" };
    const first = await process("alice", { sourceType: "CREATE_LISTING", sourceId: "l1" });
    expect(first).toMatchObject({ ok: true, awardedPoints: 150, duplicate: false });
    const second = await process("alice", { sourceType: "CREATE_LISTING", sourceId: "l1" });
    expect(second).toMatchObject({ awardedPoints: 0, duplicate: true });
    expect(stateOf("alice").points).toBe(150);
    expect(eventDocs()).toEqual([`flightEvents/${flightEventId("alice", "CREATE_LISTING", "l1")}`]);
  });

  test("rejects a listing the caller does not own, or that does not exist", async () => {
    store["listings/l1"] = { sellerId: "bob", status: "ACTIVE" };
    await expect(process("alice", { sourceType: "CREATE_LISTING", sourceId: "l1" })).rejects.toMatchObject({ code: "permission-denied" });
    await expect(process("alice", { sourceType: "CREATE_LISTING", sourceId: "ghost" })).rejects.toMatchObject({ code: "permission-denied" });
    expect(stateOf("alice")).toBeUndefined();
  });

  test.each(["DRAFT", "ARCHIVED", "SUSPENDED", "DELETED"])("rejects a %s listing", async (status) => {
    store["listings/l1"] = { sellerId: "alice", status };
    await expect(process("alice", { sourceType: "CREATE_LISTING", sourceId: "l1" })).rejects.toMatchObject({ code: "failed-precondition" });
  });

  test.each(["ACTIVE", "PAUSED", "OUT_OF_STOCK"])("rewards a %s listing", async (status) => {
    store["listings/l1"] = { sellerId: "alice", status };
    await expect(process("alice", { sourceType: "CREATE_LISTING", sourceId: "l1" })).resolves.toMatchObject({ awardedPoints: 150 });
  });

  test("post: owner is rewarded once; another user's post is refused", async () => {
    store["posts/p1"] = { authorId: "alice" };
    await expect(process("alice", { sourceType: "CREATE_POST", sourceId: "p1" })).resolves.toMatchObject({ awardedPoints: 80 });
    await expect(process("bob", { sourceType: "CREATE_POST", sourceId: "p1" })).rejects.toMatchObject({ code: "permission-denied" });
  });
});

describe("processFlightEvent: SUCCESSFUL_SALE requires a paid, delivered, settled order", () => {
  test("rewards a completed sale", async () => {
    store["orders/o1"] = settledOrder();
    await expect(process("seller1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" })).resolves.toMatchObject({ awardedPoints: 800, duplicate: false });
  });

  test.each([
    ["reserved, unpaid", { status: "RESERVED", paymentStatus: "PENDING", settlementStatus: "PENDING" }],
    ["confirmed, in escrow", { status: "CONFIRMED", paymentStatus: "SUCCESS", settlementStatus: "ESCROW_HOLD" }],
    ["dispatched, in escrow", { status: "DISPATCHED", paymentStatus: "SUCCESS", settlementStatus: "ESCROW_HOLD" }],
    ["delivered but not settled", { settlementStatus: "ESCROW_HOLD" }],
    ["delivered and settled but payment pending", { paymentStatus: "PENDING" }],
    ["cancelled", { status: "CANCELLED" }],
    ["refunded", { status: "REFUNDED" }],
    ["failed", { status: "FAILED" }],
  ])("refuses an incomplete order: %s", async (_label, over) => {
    store["orders/o1"] = settledOrder(over as any);
    for (const uid of ["buyer1", "seller1"]) {
      await expect(process(uid, { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" })).rejects.toMatchObject({ code: "failed-precondition" });
    }
    expect(eventDocs()).toHaveLength(0);
    expect(stateOf("seller1")).toBeUndefined();
  });

  test("refuses a user who is neither buyer nor seller, and a missing order", async () => {
    store["orders/o1"] = settledOrder();
    await expect(process("alice", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" })).rejects.toMatchObject({ code: "permission-denied" });
    await expect(process("buyer1", { sourceType: "SUCCESSFUL_SALE", sourceId: "nope" })).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("refuses an order whose buyer and seller are the same account", async () => {
    store["orders/o1"] = settledOrder({ buyerId: "alice", sellerId: "alice" });
    await expect(process("alice", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" })).rejects.toMatchObject({ code: "permission-denied" });
  });
});

describe("processFlightEvent: idempotency is scoped to the user", () => {
  test("one participant claiming a sale does not suppress the other's legitimate reward", async () => {
    store["orders/o1"] = settledOrder();
    const buyer = await process("buyer1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" });
    const seller = await process("seller1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" });
    expect(buyer).toMatchObject({ awardedPoints: 800, duplicate: false });
    expect(seller).toMatchObject({ awardedPoints: 800, duplicate: false });
    // ...and neither can claim the same order twice.
    await expect(process("buyer1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" })).resolves.toMatchObject({ duplicate: true, awardedPoints: 0 });
    expect(stateOf("buyer1").points).toBe(800);
    expect(stateOf("seller1").points).toBe(800);
  });

  test("a legacy shared-key event written by ANOTHER user no longer suppresses the caller", async () => {
    store["orders/o1"] = settledOrder();
    store["flightEvents/SUCCESSFUL_SALE:o1"] = { uid: "buyer1", sourceType: "SUCCESSFUL_SALE", sourceId: "o1", awardedPoints: 800 };
    await expect(process("seller1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" })).resolves.toMatchObject({ awardedPoints: 800, duplicate: false });
  });

  test("a legacy shared-key event written by the SAME user still counts as already rewarded", async () => {
    store["orders/o1"] = settledOrder();
    store["flightEvents/SUCCESSFUL_SALE:o1"] = { uid: "buyer1", sourceType: "SUCCESSFUL_SALE", sourceId: "o1", awardedPoints: 800 };
    await expect(process("buyer1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" })).resolves.toMatchObject({ awardedPoints: 0, duplicate: true });
    expect(stateOf("buyer1")).toBeUndefined();
  });
});

describe("processFlightEvent: follows", () => {
  test("FOLLOW rewards the follower", async () => {
    store["follows/alice_bob"] = { followerId: "alice", followedId: "bob" };
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "alice_bob" })).resolves.toMatchObject({ awardedPoints: 10 });
  });

  test("FOLLOW is refused for the person being followed, and for a stranger", async () => {
    store["follows/alice_bob"] = { followerId: "alice", followedId: "bob" };
    await expect(process("bob", { sourceType: "FOLLOW", sourceId: "alice_bob" })).rejects.toMatchObject({ code: "permission-denied" });
    await expect(process("buyer1", { sourceType: "FOLLOW", sourceId: "alice_bob" })).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("NEW_FOLLOWER rewards the person followed, not the follower", async () => {
    store["follows/alice_bob"] = { followerId: "alice", followedId: "bob" };
    await expect(process("alice", { sourceType: "NEW_FOLLOWER", sourceId: "alice_bob" })).rejects.toMatchObject({ code: "permission-denied" });
    await expect(process("bob", { sourceType: "NEW_FOLLOWER", sourceId: "alice_bob" })).resolves.toMatchObject({ awardedPoints: 10 });
  });

  test("a self-follow earns nothing", async () => {
    store["follows/alice_alice"] = { followerId: "alice", followedId: "alice" };
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "alice_alice" })).rejects.toMatchObject({ code: "permission-denied" });
    await expect(process("alice", { sourceType: "NEW_FOLLOWER", sourceId: "alice_alice" })).rejects.toMatchObject({ code: "permission-denied" });
  });

  test("follow-farming: duplicate follow documents for one pair earn once", async () => {
    store["follows/alice_bob"] = { followerId: "alice", followedId: "bob" };
    store["follows/second-copy"] = { followerId: "alice", followedId: "bob" };
    store["follows/third-copy"] = { followerId: "alice", followedId: "bob" };
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "alice_bob" })).resolves.toMatchObject({ awardedPoints: 10 });
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "second-copy" })).resolves.toMatchObject({ awardedPoints: 0, duplicate: true });
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "third-copy" })).resolves.toMatchObject({ awardedPoints: 0, duplicate: true });
    expect(stateOf("alice").points).toBe(10);
  });

  test("follow records pointing at a non-existent account earn nothing", async () => {
    store["follows/alice_ghost"] = { followerId: "alice", followedId: "ghost" };
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "alice_ghost" })).rejects.toMatchObject({ code: "failed-precondition" });
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "missing" })).rejects.toMatchObject({ code: "permission-denied" });
  });
});

describe("processFlightEvent: concurrency", () => {
  test("simultaneous identical claims credit exactly once", async () => {
    store["listings/l1"] = { sellerId: "alice", status: "ACTIVE" };
    const results = await Promise.all(
      Array.from({ length: 6 }, () => process("alice", { sourceType: "CREATE_LISTING", sourceId: "l1" }))
    );
    expect(results.filter((r: any) => !r.duplicate)).toHaveLength(1);
    expect(stateOf("alice").points).toBe(150);
    expect(eventDocs()).toHaveLength(1);
  });

  test("simultaneous claims by buyer and seller both succeed, once each", async () => {
    store["orders/o1"] = settledOrder();
    const [b, s, b2, s2] = await Promise.all([
      process("buyer1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" }),
      process("seller1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" }),
      process("buyer1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" }),
      process("seller1", { sourceType: "SUCCESSFUL_SALE", sourceId: "o1" }),
    ]);
    expect([b, b2].filter((r: any) => !r.duplicate)).toHaveLength(1);
    expect([s, s2].filter((r: any) => !r.duplicate)).toHaveLength(1);
    expect(stateOf("buyer1").points).toBe(800);
    expect(stateOf("seller1").points).toBe(800);
  });

  test("different events for one user in parallel all land without losing points", async () => {
    store["listings/l1"] = { sellerId: "alice", status: "ACTIVE" };
    store["posts/p1"] = { authorId: "alice" };
    store["posts/p2"] = { authorId: "alice" };
    await Promise.all([
      process("alice", { sourceType: "CREATE_LISTING", sourceId: "l1" }),
      process("alice", { sourceType: "CREATE_POST", sourceId: "p1" }),
      process("alice", { sourceType: "CREATE_POST", sourceId: "p2" }),
    ]);
    expect(stateOf("alice").points).toBe(150 + 80 + 80);
  });
});

describe("processFlightEvent: points and altitude", () => {
  test("awarded points respect the per-type cap at high altitude", async () => {
    store["flightState/alice"] = {
      uid: "alice", flightNumber: 1, points: 90000, currentAltitude: 10, highestAltitude: 10,
      milestonesCompleted: [], isCompleted: false, createdAt: 1, updatedAt: 1,
    };
    store["follows/alice_bob"] = { followerId: "alice", followedId: "bob" };
    await expect(process("alice", { sourceType: "FOLLOW", sourceId: "alice_bob" })).resolves.toMatchObject({ awardedPoints: 30 });
  });

  test("reaching the target marks the flight completed", async () => {
    store["flightState/alice"] = {
      uid: "alice", flightNumber: 1, points: REDEMPTION_TARGET_POINTS - 10, currentAltitude: 1, highestAltitude: 1,
      milestonesCompleted: [], isCompleted: false, createdAt: 1, updatedAt: 1,
    };
    store["posts/p1"] = { authorId: "alice" };
    const res = await process("alice", { sourceType: "CREATE_POST", sourceId: "p1" });
    expect(res.state.isCompleted).toBe(true);
    expect(stateOf("alice").isCompleted).toBe(true);
  });
});

describe("claimFlightRedemption", () => {
  const seedAtTarget = (uid = "alice", points = REDEMPTION_TARGET_POINTS) => {
    store[`flightState/${uid}`] = {
      uid, flightNumber: 1, points, currentAltitude: 10, highestAltitude: 10,
      milestonesCompleted: [], isCompleted: true, createdAt: 1, updatedAt: 1,
    };
  };

  test("refuses below the target and changes nothing", async () => {
    seedAtTarget("alice", REDEMPTION_TARGET_POINTS - 1);
    await expect(redeem("alice")).rejects.toMatchObject({ code: "failed-precondition" });
    expect(stateOf("alice").points).toBe(REDEMPTION_TARGET_POINTS - 1);
    expect(store["flightHistory/alice_flight_1"]).toBeUndefined();
  });

  test("refuses when there is no flight state", async () => {
    await expect(redeem("alice")).rejects.toMatchObject({ code: "not-found" });
  });

  test("redeems once: records history marked pending fulfilment and starts the next flight", async () => {
    seedAtTarget();
    const res = await redeem("alice");
    expect(res).toMatchObject({ ok: true, redeemedAmount: "M200", fulfillmentStatus: "PENDING", completedFlightNumber: 1, nextFlightNumber: 2 });
    expect(store["flightHistory/alice_flight_1"]).toMatchObject({
      uid: "alice", flightNumber: 1, finalPoints: REDEMPTION_TARGET_POINTS, reward: "M200", fulfillmentStatus: "PENDING",
    });
    expect(stateOf("alice")).toMatchObject({ flightNumber: 2, points: 0, currentAltitude: 1, isCompleted: false });
  });

  test("a second claim straight after cannot redeem again", async () => {
    seedAtTarget();
    await redeem("alice");
    await expect(redeem("alice")).rejects.toMatchObject({ code: "failed-precondition" });
    expect(Object.keys(store).filter((k) => k.startsWith("flightHistory/"))).toHaveLength(1);
  });

  test("simultaneous claims redeem exactly once", async () => {
    seedAtTarget();
    const outcomes = await Promise.allSettled([redeem("alice"), redeem("alice"), redeem("alice"), redeem("alice")]);
    expect(outcomes.filter((o) => o.status === "fulfilled")).toHaveLength(1);
    expect(outcomes.filter((o) => o.status === "rejected")).toHaveLength(3);
    expect(Object.keys(store).filter((k) => k.startsWith("flightHistory/"))).toHaveLength(1);
    expect(stateOf("alice").flightNumber).toBe(2);
  });

  test("never overwrites an existing history record for the flight", async () => {
    seedAtTarget();
    store["flightHistory/alice_flight_1"] = { uid: "alice", flightNumber: 1, reward: "M200", fulfillmentStatus: "PAID" };
    await expect(redeem("alice")).rejects.toMatchObject({ code: "already-exists" });
    expect(store["flightHistory/alice_flight_1"].fulfillmentStatus).toBe("PAID");
    expect(stateOf("alice").flightNumber).toBe(1);
  });

  test("users cannot redeem on each other's flight", async () => {
    seedAtTarget("alice");
    await expect(redeem("bob")).rejects.toMatchObject({ code: "not-found" });
    expect(stateOf("alice").flightNumber).toBe(1);
  });

  test("rejects anonymous callers", async () => {
    seedAtTarget();
    const anon = { uid: "alice", token: { firebase: { sign_in_provider: "anonymous" } } };
    await expect((claimFlightRedemption as any).run({ data: {}, auth: anon })).rejects.toMatchObject({ code: "permission-denied" });
  });
});

describe("getFlightState / feedBird", () => {
  test("getFlightState creates state once and returns it consistently", async () => {
    const a = await (getFlightState as any).run({ data: {}, auth: asUser("alice") });
    const b = await (getFlightState as any).run({ data: {}, auth: asUser("alice") });
    expect(a.state).toMatchObject({ uid: "alice", points: 0, flightNumber: 1 });
    expect(b.state.createdAt).toBe(a.state.createdAt);
  });

  test("feedBird spends points for altitude and refuses when short", async () => {
    store["flightState/alice"] = {
      uid: "alice", flightNumber: 1, points: 20, currentAltitude: 1, highestAltitude: 1,
      milestonesCompleted: [], isCompleted: false, createdAt: 1, updatedAt: 1,
    };
    const ok = await (feedBird as any).run({ data: {}, auth: asUser("alice") });
    expect(ok.state).toMatchObject({ points: 0, currentAltitude: 2 });
    await expect((feedBird as any).run({ data: {}, auth: asUser("alice") })).rejects.toMatchObject({ code: "failed-precondition" });
  });
});
