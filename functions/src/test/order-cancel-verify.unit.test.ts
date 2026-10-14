// In-memory Firestore fake (reads-before-writes enforced) + stubbed MoPay client.
const store: Record<string, any> = {};
let idCounter = 0;
let mopayResult: any = {};
jest.mock("../mopay", () => ({
  MOPAY_API_KEY: "k",
  MopayClient: { verifyPaymentSession: async () => mopayResult, initiatePaymentSession: async () => ({}) },
}));
jest.mock("firebase-admin", () => {
  const ref = (col: string, id: string) => ({ path: `${col}/${id}`, id,
    update: async (u: any) => { store[`${col}/${id}`] = { ...store[`${col}/${id}`], ...u }; } });
  const query = (col: string, filters: Array<[string, any]>) => ({
    where: (f: string, _o: string, v: any) => query(col, [...filters, [f, v]]),
    limit: () => query(col, filters),
    get: async () => {
      const docs = Object.entries(store)
        .filter(([k, v]) => k.startsWith(col + "/") && filters.every(([f, val]) => v[f] === val))
        .map(([k, v]) => ({ id: k.split("/")[1], ref: ref(col, k.split("/")[1]), data: () => v }));
      return { empty: docs.length === 0, docs };
    },
  });
  const db = {
    collection: (col: string) => ({ doc: (id?: string) => ref(col, id || `auto${++idCounter}`), ...query(col, []) }),
    runTransaction: async (fn: any) => {
      const writes: Array<() => void> = [];
      const snap = (r: any) => ({ exists: r.path in store, data: () => store[r.path], ref: r });
      const tx = {
        get: async (r: any) => {
          if (writes.length) throw new Error("Firestore transactions require all reads to be executed before all writes.");
          if (r.get) return r.get();
          return snap(r);
        },
        update: (r: any, u: any) => writes.push(() => { store[r.path] = { ...store[r.path], ...u }; }),
        set: (r: any, d: any, o?: any) => writes.push(() => { store[r.path] = o?.merge ? { ...store[r.path], ...d } : d; }),
      };
      const out = await fn(tx);
      writes.forEach((w) => w());
      return out;
    },
  };
  const firestore: any = () => db;
  firestore.FieldValue = { serverTimestamp: () => "TS", increment: (n: number) => n };
  firestore.Timestamp = { now: () => "NOW", fromMillis: (n: number) => n };
  return { firestore, apps: [1], initializeApp: jest.fn() };
});

import { cancelOrder, verifyMopayPayment } from "../commerce";

const cancel = (orderId: string, uid: string, token: any = {}) =>
  (cancelOrder as any).run({ data: { orderId }, auth: { uid, token } });
const verify = (sessionId = "s1") =>
  (verifyMopayPayment as any).run({ data: { sessionId }, auth: { uid: "buyer1", token: {} } });
const ledger = () => Object.entries(store).filter(([k]) => k.startsWith("ledgerEntries/")).map(([, v]) => v);

beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  store["orders/o1"] = { id: "o1", buyerId: "buyer1", sellerId: "seller1", status: "DELIVERED", paymentStatus: "PAID",
    paymentMethod: "SWIFT_WALLET", settlementStatus: "ESCROW_HOLD", totalMinorUnits: 5000, mopaySessionId: "s1" };
  store["wallets/buyer1"] = { availableBalanceMinorUnits: 0 };
});

describe("cancelOrder after delivery", () => {
  test("nobody can cancel a delivered order: buyer, seller, admin; no refund is written", async () => {
    for (const [uid, token] of [["buyer1", {}], ["seller1", {}], ["root", { admin: true }]] as any) {
      await expect(cancel("o1", uid, token)).rejects.toThrow(/after delivery/);
    }
    store["orders/o1"] = { ...store["orders/o1"], status: "DISPATCHED", fulfillmentStatus: "DELIVERED" };
    await expect(cancel("o1", "buyer1")).rejects.toThrow(/after delivery/);
    expect(store["wallets/buyer1"].availableBalanceMinorUnits).toBe(0);
    expect(ledger()).toHaveLength(0);
  });
});

describe("verifyMopayPayment", () => {
  beforeEach(() => {
    store["orders/o1"] = { id: "o1", buyerId: "buyer1", sellerId: "seller1", status: "CANCELLED", paymentStatus: "PENDING",
      paymentMethod: "MOPAY", totalMinorUnits: 5000, mopaySessionId: "s1" };
    mopayResult = { reference: "o1", amount: 50, transactionStatus: "SUCCESS", transactionId: "tx9" };
  });
  test("payment arriving after the order was cancelled is refunded to the buyer's wallet, once", async () => {
    await expect(verify()).rejects.toThrow(/returned to your Swift wallet/);
    expect(store["wallets/buyer1"].availableBalanceMinorUnits).toBe(5000);
    expect(store["orders/o1"]).toMatchObject({ status: "CANCELLED", refundStatus: "REFUNDED_TO_WALLET" });
    expect(store["paymentRefunds/o1"]).toMatchObject({ status: "REFUNDED_TO_WALLET", amountMinorUnits: 5000 });
    expect(ledger()).toHaveLength(1);
    expect(ledger()[0]).toMatchObject({ debitAccount: "system_mopay_clearing", creditAccount: "user_buyer1", amountMinorUnits: 5000 });
    await expect(verify()).rejects.toThrow();
    expect(store["wallets/buyer1"].availableBalanceMinorUnits).toBe(5000); // not refunded twice
    expect(ledger()).toHaveLength(1);
  });
  test("a pending or failed gateway result is an error to the app, never 'order placed'", async () => {
    store["orders/o1"].status = "RESERVED";
    mopayResult = { reference: "o1", amount: 50, transactionStatus: "PENDING" };
    await expect(verify()).rejects.toThrow(/still being processed/);
  });
});
