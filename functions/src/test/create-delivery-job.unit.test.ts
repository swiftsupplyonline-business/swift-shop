// In-memory Firestore fake that enforces reads-before-writes and supports transactional queries.
const store: Record<string, any> = {};
let idCounter = 0;
jest.mock("firebase-admin", () => {
  const ref = (col: string, id: string) => ({ path: `${col}/${id}`, id });
  const query = (col: string, filters: Array<[string, any]>) => ({
    __query: true,
    where: (f: string, _o: string, v: any) => query(col, [...filters, [f, v]]),
    limit: () => query(col, filters),
    run: () => {
      const docs = Object.entries(store)
        .filter(([k, v]) => k.startsWith(col + "/") && filters.every(([f, val]) => v[f] === val))
        .map(([k]) => ({ id: k.split("/")[1], data: () => store[k] }));
      return { empty: docs.length === 0, docs };
    },
  });
  const db = {
    collection: (col: string) => ({ doc: (id?: string) => ref(col, id || `auto${++idCounter}`), ...query(col, []) }),
    runTransaction: async (fn: any) => {
      const writes: Array<() => void> = [];
      const tx = {
        get: async (r: any) => {
          if (writes.length) throw new Error("Firestore transactions require all reads to be executed before all writes.");
          if (r.__query) return r.run();
          return { exists: r.path in store, data: () => store[r.path] };
        },
        update: (r: any, u: any) => writes.push(() => { store[r.path] = { ...store[r.path], ...u }; }),
        set: (r: any, d: any) => writes.push(() => { store[r.path] = d; }),
      };
      const out = await fn(tx);
      writes.forEach((w) => w());
      return out;
    },
  };
  const firestore: any = () => db;
  firestore.FieldValue = { serverTimestamp: () => "TS" };
  return { firestore, apps: [1], initializeApp: jest.fn() };
});

import { createDeliveryJob } from "../fulfillment";

const call = (uid = "buyer1") => (createDeliveryJob as any).run({ data: { requestId: "r1" }, auth: { uid, token: {} } });
const routes = () => Object.keys(store).filter((k) => k.startsWith("deliveryRoutes/"));

beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  store["deliveryRequests/r1"] = { requesterId: "buyer1", status: "ACCEPTED", relatedOrderId: "o1", merchantId: "prov1",
    pickup: { lat: 1, lng: 2 }, dropoff: { lat: 3, lng: 4 }, listingId: "dl1", deliveryFeeMinorUnits: 500,
    // Requests now carry an escrowed fee (see delivery-escrow-flow.unit.test.ts); un-escrowed paid requests are refused.
    escrowStatus: "HELD", escrowAmountMinorUnits: 500 };
  store["orders/o1"] = { buyerId: "buyer1", sellerId: "seller1", status: "CONFIRMED" };
});

describe("createDeliveryJob", () => {
  test("creates exactly one job per order, with a deterministic id", async () => {
    const res = await call();
    expect(res).toEqual({ routeId: "job_o1", alreadyExists: false });
    expect(store["deliveryRoutes/job_o1"]).toMatchObject({ orderId: "o1", providerId: "prov1", status: "REQUESTED" });
    expect(store["orders/o1"]).toMatchObject({ fulfillmentId: "job_o1", fulfillmentStatus: "REQUESTED" });
  });
  test("a repeated call (double tap / retry) returns the same job and creates nothing new", async () => {
    await call();
    expect(await call()).toEqual({ routeId: "job_o1", alreadyExists: true });
    expect(routes()).toHaveLength(1);
  });
  test("finds a job created earlier under a random id", async () => {
    store["deliveryRoutes/legacy9"] = { orderId: "o1" };
    expect(await call()).toEqual({ routeId: "legacy9", alreadyExists: true });
    expect(routes()).toHaveLength(1);
  });
  test("refuses other users, unaccepted requests, and cancelled or foreign orders", async () => {
    await expect(call("intruder")).rejects.toThrow(/Only the buyer/);
    store["deliveryRequests/r1"].status = "PENDING";
    await expect(call()).rejects.toThrow(/accepted first/);
    store["deliveryRequests/r1"].status = "ACCEPTED";
    store["orders/o1"].status = "CANCELLED";
    await expect(call()).rejects.toThrow(/no longer active/);
    store["orders/o1"] = { buyerId: "someoneElse", sellerId: "seller1", status: "CONFIRMED" };
    await expect(call()).rejects.toThrow(/does not belong/);
    expect(routes()).toHaveLength(0);
  });
});
