// In-memory Firestore fake: enforces reads-before-writes, supports transactional queries (== and "in").
const store: Record<string, any> = {};
let idCounter = 0;
jest.mock("firebase-functions/v2/scheduler", () => ({ onSchedule: (_s: any, fn: any) => ({ run: fn }) }));
jest.mock("firebase-admin", () => {
  const ref = (col: string, id: string) => ({ path: `${col}/${id}`, id });
  const query = (col: string, filters: Array<[string, string, any]>) => ({
    __query: true,
    where: (f: string, o: string, v: any) => query(col, [...filters, [f, o, v]]),
    limit: () => query(col, filters),
    run: () => {
      const docs = Object.entries(store)
        .filter(([k, v]) => k.startsWith(col + "/") && filters.every(([f, o, val]) => o === "in" ? val.includes(v[f]) : o === "<=" ? v[f] <= val : v[f] === val))
        .map(([k]) => ({ id: k.split("/")[1], ref: ref(col, k.split("/")[1]), data: () => store[k] }));
      return { empty: docs.length === 0, docs };
    },
    get: async () => query(col, filters).run(),
  });
  const db = {
    collection: (col: string) => ({ doc: (id?: string) => ref(col, id || `auto${++idCounter}`), ...query(col, []) }),
    runTransaction: async (fn: any) => {
      const writes: Array<() => void> = [];
      const tx = {
        get: async (r: any) => {
          if (writes.length) throw new Error("Firestore transactions require all reads to be executed before all writes.");
          if (r.__query) return r.run();
          return { exists: r.path in store, data: () => store[r.path], ref: r, id: r.id };
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

import {
  createDeliveryRequest, acceptDeliveryRequest, declineDeliveryRequest, cancelDeliveryRequest, updateDeliveryStatus,
  expireDeliveryRequests,
} from "../fulfillment";
import { confirmDelivery, cancelOrder } from "../commerce";

const run = (fn: any, data: any, uid: string, token: any = {}) => (fn as any).run({ data, auth: { uid, token } });
const ledger = () => Object.entries(store).filter(([k]) => k.startsWith("ledgerEntries/")).map(([, v]) => v);
const bal = (uid: string) => store[`wallets/${uid}`]?.availableBalanceMinorUnits;

beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  store["orders/o1"] = { id: "o1", buyerId: "buyer1", sellerId: "seller1", shopId: "shop1", status: "CONFIRMED", paymentStatus: "PAID",
    paymentMethod: "SWIFT_WALLET", settlementStatus: "ESCROW_HOLD", subtotalMinorUnits: 10000, deliveryFeeMinorUnits: 0,
    platformFeeMinorUnits: 150, totalMinorUnits: 10150, pickupSnapshot: { lat: 1, lng: 2, shopName: "S" } };
  store["listings/dl1"] = { listingType: "DELIVER", isAvailable: true, sellerId: "courier1", priceMinorUnits: 700, priceCurrency: "LSL" };
  store["wallets/buyer1"] = { availableBalanceMinorUnits: 2000 };
});

const request = async () => (await run(createDeliveryRequest, { orderId: "o1", listingId: "dl1", dropoff: { lat: 3, lng: 4 } }, "buyer1")).requestId;

describe("createDeliveryRequest escrows the fee", () => {
  test("debits the buyer wallet into delivery escrow", async () => {
    const id = await request();
    expect(bal("buyer1")).toBe(1300);
    expect(store[`deliveryRequests/${id}`]).toMatchObject({ feePaymentStatus: "ESCROWED", deliveryFeeMinorUnits: 700, merchantId: "courier1", status: "PENDING" });
    expect(ledger()).toEqual([expect.objectContaining({ debitAccount: "user_buyer1", creditAccount: "system_delivery_escrow", amountMinorUnits: 700 })]);
  });
  test("insufficient balance rejects and writes nothing", async () => {
    store["wallets/buyer1"].availableBalanceMinorUnits = 100;
    await expect(request()).rejects.toThrow(/Insufficient/);
    expect(bal("buyer1")).toBe(100);
    expect(Object.keys(store).filter((k) => k.startsWith("deliveryRequests/"))).toHaveLength(0);
  });
  test("a second active request for the same order is refused (fee not taken twice)", async () => {
    await request();
    await expect(request()).rejects.toThrow(/already has an active/);
    expect(bal("buyer1")).toBe(1300);
  });
  test("a free delivery listing escrows nothing", async () => {
    store["listings/dl1"].priceMinorUnits = 0;
    const id = await request();
    expect(store[`deliveryRequests/${id}`].feePaymentStatus).toBe("NONE");
    expect(ledger()).toHaveLength(0);
  });
});

describe("refunds when the delivery does not happen", () => {
  test("declined by the provider", async () => {
    const id = await request();
    await run(declineDeliveryRequest, { requestId: id }, "courier1");
    expect(bal("buyer1")).toBe(2000);
    expect(store[`deliveryRequests/${id}`]).toMatchObject({ status: "DECLINED", feePaymentStatus: "REFUNDED" });
  });
  test("cancelled by the buyer, and never refunded twice", async () => {
    const id = await request();
    await run(cancelDeliveryRequest, { requestId: id }, "buyer1");
    expect(bal("buyer1")).toBe(2000);
    await expect(run(cancelDeliveryRequest, { requestId: id }, "buyer1")).rejects.toThrow();
    expect(bal("buyer1")).toBe(2000);
    expect(ledger().filter((l) => l.reference.startsWith("DELIVERY_FEE_REFUND"))).toHaveLength(1);
  });
  test("expired without an answer", async () => {
    const id = await request();
    store[`deliveryRequests/${id}`].expiresAt = Date.now() - 1000;
    await (expireDeliveryRequests as any).run();
    expect(bal("buyer1")).toBe(2000);
    expect(store[`deliveryRequests/${id}`]).toMatchObject({ status: "EXPIRED", feePaymentStatus: "REFUNDED" });
    await (expireDeliveryRequests as any).run();
    expect(bal("buyer1")).toBe(2000);
  });
  test("delivery job FAILED or CANCELLED refunds the escrowed fee", async () => {
    for (const [from, to] of [["IN_TRANSIT", "FAILED"], ["REQUESTED", "CANCELLED"]]) {
      for (const k of Object.keys(store)) if (k.startsWith("deliveryRequests/") || k.startsWith("deliveryRoutes/")) delete store[k];
      store["wallets/buyer1"].availableBalanceMinorUnits = 2000;
      const id = await request();
      store[`deliveryRequests/${id}`].status = "ACCEPTED";
      store["deliveryRoutes/job_o1"] = { orderId: "o1", requestId: id, buyerId: "buyer1", sellerId: "seller1", driverId: "driver1", status: from };
      await run(updateDeliveryStatus, { routeId: "job_o1", status: to }, to === "FAILED" ? "driver1" : "buyer1");
      expect(bal("buyer1")).toBe(2000);
      expect(store[`deliveryRequests/${id}`].feePaymentStatus).toBe("REFUNDED");
    }
  });
  test("cancelling the order refunds the product AND the escrowed fee in one wallet write", async () => {
    store["orders/o1"].status = "PROCESSING";
    store["wallets/buyer1"].availableBalanceMinorUnits = 2000;
    const id = await request();
    store[`deliveryRequests/${id}`].status = "ACCEPTED";
    await run(cancelOrder, { orderId: "o1" }, "buyer1");
    expect(bal("buyer1")).toBe(2000 + 10150);
    expect(store[`deliveryRequests/${id}`]).toMatchObject({ status: "CANCELLED", feePaymentStatus: "REFUNDED" });
    expect(store["orders/o1"].status).toBe("CANCELLED");
  });
});

describe("payout only on a successful, buyer-confirmed delivery", () => {
  const delivered = async () => {
    const id = await request();
    store[`deliveryRequests/${id}`].status = "ACCEPTED";
    store["orders/o1"] = { ...store["orders/o1"], status: "DELIVERED", fulfillmentStatus: "DELIVERED" };
    return id;
  };
  test("buyer confirms: delivery author gets the fee, seller gets product money", async () => {
    const id = await delivered();
    await run(confirmDelivery, { orderId: "o1" }, "buyer1");
    expect(bal("courier1")).toBe(700);
    expect(bal("seller1")).toBe(10000);
    expect(store[`deliveryRequests/${id}`].feePaymentStatus).toBe("RELEASED");
    expect(ledger().map((l) => l.reference)).toContain(`DELIVERY_FEE_PAYOUT_${id}`);
  });
  test("a repeat confirmation never pays the fee twice", async () => {
    await delivered();
    await run(confirmDelivery, { orderId: "o1" }, "buyer1");
    await run(confirmDelivery, { orderId: "o1" }, "buyer1");
    expect(bal("courier1")).toBe(700);
  });
  test("confirming before the delivery is DELIVERED is refused and nobody is paid", async () => {
    const id = await request();
    store[`deliveryRequests/${id}`].status = "ACCEPTED";
    store["orders/o1"] = { ...store["orders/o1"], status: "DISPATCHED", fulfillmentStatus: "IN_TRANSIT" };
    await expect(run(confirmDelivery, { orderId: "o1" }, "buyer1")).rejects.toThrow(/not complete/);
    expect(bal("courier1")).toBeUndefined();
    expect(bal("seller1")).toBeUndefined();
  });
  test("a refunded fee can no longer be released", async () => {
    const id = await request();
    await run(cancelDeliveryRequest, { requestId: id }, "buyer1");
    store["orders/o1"] = { ...store["orders/o1"], status: "READY" };
    await run(confirmDelivery, { orderId: "o1" }, "buyer1");
    expect(bal("courier1")).toBeUndefined();
    expect(bal("buyer1")).toBe(2000);
  });
});

describe("adversarial and concurrency", () => {
  test("client-supplied fee / provider fields are ignored; the stored listing decides", async () => {
    const res = await run(createDeliveryRequest, { orderId: "o1", listingId: "dl1", dropoff: { lat: 3, lng: 4 },
      deliveryFeeMinorUnits: 1, merchantId: "attacker", feePaymentStatus: "NONE" }, "buyer1");
    expect(res.deliveryFeeMinorUnits).toBe(700);
    expect(store[`deliveryRequests/${res.requestId}`]).toMatchObject({ merchantId: "courier1", deliveryFeeMinorUnits: 700, feePaymentStatus: "ESCROWED" });
    expect(bal("buyer1")).toBe(1300);
  });
  test("a user cannot create delivery for someone else's order", async () => {
    await expect(run(createDeliveryRequest, { orderId: "o1", listingId: "dl1", dropoff: { lat: 3, lng: 4 } }, "intruder"))
      .rejects.toThrow(/Only the buyer/);
    expect(ledger()).toHaveLength(0);
  });
  test("a buyer cannot route the fee to their own delivery listing", async () => {
    store["listings/dl1"].sellerId = "buyer1";
    await expect(request()).rejects.toThrow(/own delivery listing/);
    expect(bal("buyer1")).toBe(2000);
  });
  test("an unpaid, cancelled or delivered order cannot be given a delivery request", async () => {
    for (const patch of [{ status: "RESERVED", paymentStatus: "PENDING" }, { status: "CANCELLED" }, { status: "DELIVERED" }]) {
      store["orders/o1"] = { ...store["orders/o1"], ...patch };
      await expect(request()).rejects.toThrow();
    }
    expect(bal("buyer1")).toBe(2000);
  });
  test("only the requested provider can accept or decline; nobody else", async () => {
    const id = await request();
    for (const uid of ["buyer1", "intruder", "seller1"]) {
      await expect(run(acceptDeliveryRequest, { requestId: id }, uid)).rejects.toThrow(/Only the requested provider/);
      await expect(run(declineDeliveryRequest, { requestId: id }, uid)).rejects.toThrow(/Only the requested provider/);
    }
    expect(bal("buyer1")).toBe(1300);
    expect(store[`deliveryRequests/${id}`].status).toBe("PENDING");
  });
  test("accepting twice, or accepting then declining, is refused (no double transition, no refund after accept)", async () => {
    const id = await request();
    await run(acceptDeliveryRequest, { requestId: id }, "courier1");
    await expect(run(acceptDeliveryRequest, { requestId: id }, "courier1")).rejects.toThrow(/Cannot respond/);
    await expect(run(declineDeliveryRequest, { requestId: id }, "courier1")).rejects.toThrow(/Cannot respond/);
    expect(bal("buyer1")).toBe(1300);
    expect(store[`deliveryRequests/${id}`]).toMatchObject({ status: "ACCEPTED", feePaymentStatus: "ESCROWED" });
  });
  test("a stale (expired) request cannot be accepted", async () => {
    const id = await request();
    store[`deliveryRequests/${id}`].expiresAt = Date.now() - 1;
    await expect(run(acceptDeliveryRequest, { requestId: id }, "courier1")).rejects.toThrow(/expired/);
  });
  test("a driver cannot update a delivery they are not assigned to; buyer cannot mark it delivered", async () => {
    const id = await request();
    store[`deliveryRequests/${id}`].status = "ACCEPTED";
    store["deliveryRoutes/job_o1"] = { orderId: "o1", requestId: id, buyerId: "buyer1", sellerId: "seller1", providerId: "courier1", driverId: "driver1", status: "IN_TRANSIT" };
    await expect(run(updateDeliveryStatus, { routeId: "job_o1", status: "DELIVERED" }, "driver2")).rejects.toThrow(/assigned driver/);
    await expect(run(updateDeliveryStatus, { routeId: "job_o1", status: "DELIVERED" }, "buyer1")).rejects.toThrow(/assigned driver/);
    expect(store["deliveryRoutes/job_o1"].status).toBe("IN_TRANSIT");
  });
  test("a FAILED delivery cannot be replayed to refund twice", async () => {
    const id = await request();
    store[`deliveryRequests/${id}`].status = "ACCEPTED";
    store["deliveryRoutes/job_o1"] = { orderId: "o1", requestId: id, buyerId: "buyer1", sellerId: "seller1", driverId: "driver1", status: "IN_TRANSIT" };
    await run(updateDeliveryStatus, { routeId: "job_o1", status: "FAILED" }, "driver1");
    await expect(run(updateDeliveryStatus, { routeId: "job_o1", status: "FAILED" }, "driver1")).rejects.toThrow(/Cannot transition/);
    expect(bal("buyer1")).toBe(2000);
    expect(ledger().filter((l) => l.reference.startsWith("DELIVERY_FEE_REFUND"))).toHaveLength(1);
  });
  test("a driver cannot be paid by marking their own delivery done: payout waits for the buyer", async () => {
    const id = await request();
    store[`deliveryRequests/${id}`].status = "ACCEPTED";
    store["deliveryRoutes/job_o1"] = { orderId: "o1", requestId: id, buyerId: "buyer1", sellerId: "seller1", driverId: "driver1", status: "IN_TRANSIT" };
    await run(updateDeliveryStatus, { routeId: "job_o1", status: "DELIVERED" }, "driver1");
    expect(bal("courier1")).toBeUndefined();
    expect(store[`deliveryRequests/${id}`].feePaymentStatus).toBe("ESCROWED");
    await expect(run(confirmDelivery, { orderId: "o1" }, "driver1")).rejects.toThrow(/Unauthorized/);
    await expect(run(confirmDelivery, { orderId: "o1" }, "courier1")).rejects.toThrow(/Unauthorized/);
    expect(bal("courier1")).toBeUndefined();
  });
});
