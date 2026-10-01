// In-memory Firestore fake that, like the real SDK, throws on any read issued after a write.
const store: Record<string, any> = {};
let idCounter = 0;
jest.mock("firebase-admin", () => {
  const ref = (col: string, id: string) => ({ path: `${col}/${id}`, id });
  const db = {
    collection: (col: string) => {
      const query = (filters: Array<[string, any]>): any => ({
        __query: true,
        where: (f: string, _o: string, v: any) => query([...filters, [f, v]]),
        limit: () => query(filters),
        run: () => {
          const docs = Object.entries(store)
            .filter(([k, v]) => k.startsWith(col + "/") && filters.every(([f, val]) => v[f] === val))
            .map(([k]) => ({ id: k.split("/")[1], ref: ref(col, k.split("/")[1]), data: () => store[k] }));
          return { empty: docs.length === 0, docs };
        },
      });
      return { doc: (id?: string) => ref(col, id || `auto${++idCounter}`), ...query([]) };
    },
    runTransaction: async (fn: any) => {
      const writes: Array<() => void> = [];
      const tx = {
        get: async (r: any) => {
          if (writes.length) throw new Error("Firestore transactions require all reads to be executed before all writes.");
          if (r.__query) return r.run();
          return { exists: r.path in store, data: () => store[r.path] };
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

import { confirmDelivery } from "../commerce";

const call = (uid = "buyer1") => (confirmDelivery as any).run({ data: { orderId: "o1" }, auth: { uid, token: {} } });
const ledger = () => Object.entries(store).filter(([k]) => k.startsWith("ledgerEntries/")).map(([, v]) => v);

beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  store["orders/o1"] = { buyerId: "buyer1", sellerId: "seller1", status: "DISPATCHED", paymentStatus: "SUCCESS",
    settlementStatus: "ESCROW_HOLD", subtotalMinorUnits: 10000, deliveryFeeMinorUnits: 500, platformFeeMinorUnits: 150, totalMinorUnits: 10650 };
  store["wallets/seller1"] = { availableBalanceMinorUnits: 1000 };
});

describe("confirmDelivery", () => {
  test("pays the seller, books the fee, marks the order settled (reads precede writes)", async () => {
    await expect(call()).resolves.toEqual({ success: true });
    expect(store["wallets/seller1"].availableBalanceMinorUnits).toBe(11500);
    expect(store["orders/o1"].settlementStatus).toBe("SETTLED");
    expect(ledger().map((l) => l.amountMinorUnits).sort((a, b) => a - b)).toEqual([150, 500, 10000, 10650]);
  });
  test("delivery fee goes to the delivery listing's author, product money to the seller", async () => {
    store["orders/o1"].deliveryProviderId = "courier1";
    store["wallets/courier1"] = { availableBalanceMinorUnits: 200 };
    await call();
    expect(store["wallets/seller1"].availableBalanceMinorUnits).toBe(11000);
    expect(store["wallets/courier1"].availableBalanceMinorUnits).toBe(700);
    expect(ledger().map((l) => l.reference).sort()).toEqual(
      ["DELIVERY_FEE_o1", "PLATFORM_FEE_o1", "SALE_PROCEEDS_o1", "SETTLE_ORDER_o1"]);
  });
  test("seller who is also the courier receives both amounts in one wallet", async () => {
    store["orders/o1"].deliveryProviderId = "seller1";
    await call();
    expect(store["wallets/seller1"].availableBalanceMinorUnits).toBe(11500);
  });
  test("a courier with no wallet doc still gets paid", async () => {
    store["orders/o1"].deliveryProviderId = "courier2";
    await call();
    expect(store["wallets/courier2"].availableBalanceMinorUnits).toBe(500);
  });
  test("a seller with no wallet doc still gets paid", async () => {
    delete store["wallets/seller1"];
    await call();
    expect(store["wallets/seller1"].availableBalanceMinorUnits).toBe(10500);
  });
  test("repeat confirmation does not pay twice", async () => {
    await call(); await call();
    expect(store["wallets/seller1"].availableBalanceMinorUnits).toBe(11500);
    expect(ledger()).toHaveLength(4);
  });
  test("refuses unpaid orders, orders without escrow, other users, inconsistent amounts", async () => {
    store["orders/o1"].paymentStatus = "PENDING";
    await expect(call()).rejects.toThrow(/not been paid/);
    store["orders/o1"].paymentStatus = "SUCCESS"; store["orders/o1"].settlementStatus = "PENDING";
    await expect(call()).rejects.toThrow(/no escrow/);
    store["orders/o1"].settlementStatus = "ESCROW_HOLD";
    await expect(call("intruder")).rejects.toThrow(/Unauthorized/);
    store["orders/o1"].totalMinorUnits = 99999;
    await expect(call()).rejects.toThrow(/inconsistent/);
    expect(store["wallets/seller1"].availableBalanceMinorUnits).toBe(1000);
  });
});
