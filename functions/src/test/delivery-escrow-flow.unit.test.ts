// In-memory Firestore fake: enforces reads-before-writes, strict update-of-missing-doc, equality/in/<= queries.
const store: Record<string, any> = {};
let idCounter = 0;
jest.mock("firebase-admin", () => {
  const match = (v: any, op: string, val: any) => op === "==" ? v === val : op === "in" ? val.includes(v) : op === "<=" ? v <= val : false;
  const ref = (col: string, id: string) => ({ path: `${col}/${id}`, id, get: async () => ({ exists: `${col}/${id}` in store, data: () => store[`${col}/${id}`] }) });
  const query = (col: string, filters: Array<[string, string, any]>): any => ({
    __query: true,
    where: (f: string, o: string, v: any) => query(col, [...filters, [f, o, v]]),
    limit: () => query(col, filters),
    run: () => {
      const docs = Object.entries(store)
        .filter(([k, v]) => k.startsWith(col + "/") && filters.every(([f, o, val]) => match(v[f], o, val)))
        .map(([k]) => ({ id: k.split("/")[1], data: () => store[k] }));
      return { empty: docs.length === 0, docs };
    },
    get: async () => query(col, filters).run(),
  });
  const db: any = {
    collection: (col: string) => ({ doc: (id?: string) => ref(col, id || `auto${++idCounter}`), ...query(col, []) }),
    runTransaction: async (fn: any) => {
      const writes: Array<() => void> = [];
      const tx = {
        get: async (r: any) => {
          if (writes.length) throw new Error("Firestore transactions require all reads to be executed before all writes.");
          if (r.__query) return r.run();
          return { exists: r.path in store, data: () => store[r.path] };
        },
        update: (r: any, u: any) => writes.push(() => { if (!(r.path in store)) throw new Error("NOT_FOUND " + r.path); store[r.path] = { ...store[r.path], ...u }; }),
        set: (r: any, d: any, o?: any) => writes.push(() => { store[r.path] = o?.merge ? { ...store[r.path], ...d } : d; }),
      };
      const out = await fn(tx);
      writes.forEach((w) => w());
      return out;
    },
  };
  const firestore: any = () => db;
  firestore.FieldValue = { serverTimestamp: () => "TS" };
  firestore.Timestamp = { now: () => "NOW", fromMillis: (n: number) => n };
  return { firestore, apps: [1], initializeApp: jest.fn() };
});

import { createDeliveryRequest, cancelDeliveryRequest, declineDeliveryRequest, acceptDeliveryRequest,
         createDeliveryJob, updateDeliveryStatus, expireDeliveryRequest } from "../fulfillment";
import { confirmDelivery } from "../commerce";
import * as admin from "firebase-admin";

const run = (fn: any, uid: string, data: any, token: any = {}) => fn.run({ data, auth: { uid, token } });
const wallet = (u: string) => store[`wallets/${u}`]?.availableBalanceMinorUnits;
const ledger = () => Object.entries(store).filter(([k]) => k.startsWith("ledgerEntries/")).map(([, v]) => v);
const sumWallets = () => Object.entries(store).filter(([k]) => k.startsWith("wallets/")).reduce((a, [, v]) => a + v.availableBalanceMinorUnits, 0);
const reqId = () => store["orders/o1"].activeDeliveryRequestId as string;
const DROP = { lat: -29.3, lng: 27.5 };

beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  store["orders/o1"] = { id: "o1", buyerId: "buyer", sellerId: "seller", shopId: "shopA", status: "CONFIRMED", paymentStatus: "PAID",
    settlementStatus: "ESCROW_HOLD", subtotalMinorUnits: 10000, deliveryFeeMinorUnits: 0, platformFeeMinorUnits: 150, totalMinorUnits: 10150,
    pickupSnapshot: { lat: -29.31, lng: 27.48, shopName: "A" } };
  store["listings/dl1"] = { listingType: "DELIVER", isAvailable: true, sellerId: "provider", priceMinorUnits: 5000, priceCurrency: "LSL", shopId: "shopB" };
  store["wallets/buyer"] = { availableBalanceMinorUnits: 20000 };
  store["wallets/provider"] = { availableBalanceMinorUnits: 0 };
  store["wallets/driver"] = { availableBalanceMinorUnits: 0 };
  store["wallets/seller"] = { availableBalanceMinorUnits: 0 };
});

const request = () => run(createDeliveryRequest, "buyer", { orderId: "o1", listingId: "dl1", dropoff: DROP });

describe("request creation: buyer debit -> escrow", () => {
  test("fee is read from the listing, debited from the buyer, held in delivery escrow, order locked", async () => {
    const r = await request();
    expect(r.deliveryFeeMinorUnits).toBe(5000);
    expect(wallet("buyer")).toBe(15000);
    const hold = ledger().find((l) => l.reference.startsWith("DELIVERY_HOLD_"))!;
    expect(hold).toMatchObject({ debitAccount: "user_buyer", creditAccount: "system_delivery_escrow", amountMinorUnits: 5000 });
    expect(store[`deliveryRequests/${r.requestId}`]).toMatchObject({ escrowStatus: "HELD", escrowAmountMinorUnits: 5000, merchantId: "provider", status: "PENDING" });
    expect(reqId()).toBe(r.requestId);
  });
  test("client cannot influence the fee (extra payload fields are ignored)", async () => {
    const r = await run(createDeliveryRequest, "buyer", { orderId: "o1", listingId: "dl1", dropoff: DROP, deliveryFeeMinorUnits: 1, feeMinorUnits: 1, amount: 1 });
    expect(r.deliveryFeeMinorUnits).toBe(5000);
    expect(wallet("buyer")).toBe(15000);
  });
  test("duplicate request is rejected and charges nothing extra", async () => {
    await request();
    await expect(request()).rejects.toThrow(/already has an active delivery request/);
    expect(wallet("buyer")).toBe(15000);
    expect(ledger().length).toBe(1);
  });
  test("insufficient wallet balance", async () => {
    store["wallets/buyer"].availableBalanceMinorUnits = 4999;
    await expect(request()).rejects.toThrow(/Insufficient wallet balance/);
    expect(wallet("buyer")).toBe(4999);
    expect(ledger().length).toBe(0);
  });
  test("wrong buyer, unpaid order, unavailable or non-delivery listing, bad dropoff", async () => {
    await expect(run(createDeliveryRequest, "mallory", { orderId: "o1", listingId: "dl1", dropoff: DROP })).rejects.toThrow(/Only the buyer/);
    store["orders/o1"].paymentStatus = "PENDING"; store["orders/o1"].status = "RESERVED";
    await expect(request()).rejects.toThrow(/must be paid/);
    store["orders/o1"].paymentStatus = "PAID"; store["orders/o1"].status = "CONFIRMED";
    store["listings/dl1"].isAvailable = false;
    await expect(request()).rejects.toThrow(/unavailable/);
    store["listings/dl1"].isAvailable = true; store["listings/dl1"].listingType = "PRODUCT";
    await expect(request()).rejects.toThrow(/unavailable/);
    store["listings/dl1"].listingType = "DELIVER";
    await expect(run(createDeliveryRequest, "buyer", { orderId: "o1", listingId: "dl1", dropoff: { lat: 999, lng: 0 } })).rejects.toThrow(/required/);
    expect(wallet("buyer")).toBe(20000);
  });
  test("cross-shop provider is allowed (delivery listing belongs to a different shop)", async () => {
    await expect(request()).resolves.toMatchObject({ escrowStatus: "HELD" });
  });
});

describe("failure paths refund the escrow exactly once", () => {
  test("provider declines -> full refund, lock released, repeat decline impossible", async () => {
    const r = await request();
    await run(declineDeliveryRequest, "provider", { requestId: r.requestId });
    expect(wallet("buyer")).toBe(20000);
    expect(store[`deliveryRequests/${r.requestId}`]).toMatchObject({ status: "DECLINED", escrowStatus: "REFUNDED" });
    expect(store["orders/o1"].activeDeliveryRequestId).toBeNull();
    await expect(run(declineDeliveryRequest, "provider", { requestId: r.requestId })).rejects.toThrow(/Cannot respond/);
    expect(wallet("buyer")).toBe(20000);
    await expect(request()).resolves.toBeDefined(); // buyer may try another provider
  });
  test("only the addressed provider can respond", async () => {
    const r = await request();
    await expect(run(acceptDeliveryRequest, "mallory", { requestId: r.requestId })).rejects.toThrow(/Only the requested provider/);
    await expect(run(declineDeliveryRequest, "mallory", { requestId: r.requestId })).rejects.toThrow(/Only the requested provider/);
    expect(wallet("buyer")).toBe(15000);
  });
  test("request expires -> refund; second expiry call is a no-op (stale request)", async () => {
    const r = await request();
    expect(await expireDeliveryRequest(admin.firestore() as any, r.requestId)).toBe(false); // not yet stale
    store[`deliveryRequests/${r.requestId}`].expiresAt = Date.now() - 1;
    expect(await expireDeliveryRequest(admin.firestore() as any, r.requestId)).toBe(true);
    expect(await expireDeliveryRequest(admin.firestore() as any, r.requestId)).toBe(false);
    expect(wallet("buyer")).toBe(20000);
    expect(store["orders/o1"].activeDeliveryRequestId).toBeNull();
  });
  test("buyer cancels (pending, or accepted before a job) -> refund; repeated cancellation does not refund twice", async () => {
    const r = await request();
    await run(cancelDeliveryRequest, "buyer", { requestId: r.requestId });
    await expect(run(cancelDeliveryRequest, "buyer", { requestId: r.requestId })).rejects.toThrow(/Cannot cancel/);
    expect(wallet("buyer")).toBe(20000);
    const r2 = await request();
    await run(acceptDeliveryRequest, "provider", { requestId: r2.requestId });
    await run(cancelDeliveryRequest, "buyer", { requestId: r2.requestId });
    expect(wallet("buyer")).toBe(20000);
    await expect(run(cancelDeliveryRequest, "mallory", { requestId: r2.requestId })).rejects.toThrow(/Only the requester/);
  });
  test("job FAILED -> refund buyer; cancelled-before-assignment -> refund", async () => {
    const r = await request();
    await run(acceptDeliveryRequest, "provider", { requestId: r.requestId });
    const job = await run(createDeliveryJob, "buyer", { requestId: r.requestId });
    await run(updateDeliveryStatus, "buyer", { routeId: job.routeId, status: "CANCELLED" });
    expect(wallet("buyer")).toBe(20000);
    expect(store[`deliveryRequests/${r.requestId}`].escrowStatus).toBe("REFUNDED");

    // second order flow: fail after assignment
    store["orders/o2"] = { ...store["orders/o1"], id: "o2", fulfillmentId: undefined, activeDeliveryRequestId: undefined };
    const r2 = await run(createDeliveryRequest, "buyer", { orderId: "o2", listingId: "dl1", dropoff: DROP });
    await run(acceptDeliveryRequest, "provider", { requestId: r2.requestId });
    const job2 = await run(createDeliveryJob, "buyer", { requestId: r2.requestId });
    await run(updateDeliveryStatus, "driver", { routeId: job2.routeId, status: "ASSIGNED" }, { role: "DRIVER" }).catch(() => {});
    store[`deliveryRoutes/${job2.routeId}`].driverId = "driver"; store[`deliveryRoutes/${job2.routeId}`].status = "AT_PICKUP";
    await run(updateDeliveryStatus, "driver", { routeId: job2.routeId, status: "FAILED" });
    expect(wallet("buyer")).toBe(20000);
    expect(store[`deliveryRequests/${r2.requestId}`].escrowStatus).toBe("REFUNDED");
  });
  test("a job cannot be created for an un-escrowed paid-delivery request", async () => {
    store["deliveryRequests/legacy"] = { requesterId: "buyer", status: "ACCEPTED", relatedOrderId: "o1", deliveryFeeMinorUnits: 5000, pickup: {}, dropoff: DROP };
    await expect(run(createDeliveryJob, "buyer", { requestId: "legacy" })).rejects.toThrow(/not been escrowed/);
  });
});

describe("success path: release at settlement", () => {
  async function toDelivered(driverId: string, shareBps = 0) {
    store["listings/dl1"].driverShareBps = shareBps;
    const r = await request();
    await run(acceptDeliveryRequest, "provider", { requestId: r.requestId });
    const job = await run(createDeliveryJob, "buyer", { requestId: r.requestId });
    Object.assign(store[`deliveryRoutes/${job.routeId}`], { driverId, status: "DELIVERED" });
    Object.assign(store["orders/o1"], { status: "DELIVERED" });
    return r.requestId;
  }
  test("provider earns the fee; product proceeds go to the seller; ledger balances", async () => {
    const id = await toDelivered("provider");
    const before = sumWallets();
    await run(confirmDelivery, "buyer", { orderId: "o1" });
    // Delivery fee 5000 at the canonical 1.5% platform cut: platform 75, provider 4925.
    expect(wallet("provider")).toBe(4925);
    expect(wallet("seller")).toBe(10000);
    expect(store[`deliveryRequests/${id}`].escrowStatus).toBe("RELEASED");
    const fromEscrow = ledger().filter((l) => l.debitAccount === "system_delivery_escrow");
    expect(fromEscrow.reduce((a, l) => a + l.amountMinorUnits, 0)).toBe(5000); // escrow fully drained: nothing lost or created
    expect(fromEscrow.filter((l) => l.creditAccount === "system_fees").reduce((a, l) => a + l.amountMinorUnits, 0)).toBe(75);
    expect(sumWallets() - before).toBe(14925); // seller 10000 + provider 4925 reach wallets; the 75 platform fee stays in system_fees
  });
  test("distinct driver: ledger distinguishes driver compensation from provider earnings", async () => {
    await toDelivered("driver", 6000);
    await run(confirmDelivery, "buyer", { orderId: "o1" });
    // 5000 fee: platform 75, net 4925; driver share 60% of net = floor(2955), provider keeps the remaining 1970.
    expect(wallet("driver")).toBe(2955);
    expect(wallet("provider")).toBe(1970);
    expect(wallet("driver") + wallet("provider")).toBe(4925);
    const refs = ledger().map((l) => l.reference);
    expect(refs.some((r: string) => r.startsWith("DRIVER_COMPENSATION_"))).toBe(true);
    expect(refs.some((r: string) => r.startsWith("PROVIDER_EARNINGS_"))).toBe(true);
  });
  test("duplicate settlement does not pay twice", async () => {
    await toDelivered("driver", 6000);
    await run(confirmDelivery, "buyer", { orderId: "o1" });
    const snapshot = JSON.stringify([wallet("provider"), wallet("driver"), wallet("seller"), ledger().length]);
    await run(confirmDelivery, "buyer", { orderId: "o1" });
    expect(JSON.stringify([wallet("provider"), wallet("driver"), wallet("seller"), ledger().length])).toBe(snapshot);
  });
  test("buyer confirming when the job never reached DELIVERED refunds the delivery fee instead of paying the provider", async () => {
    const id = await toDelivered("driver");
    store[`deliveryRoutes/o1`].status = "IN_TRANSIT";
    await run(confirmDelivery, "buyer", { orderId: "o1" });
    expect(wallet("provider")).toBe(0);
    expect(wallet("buyer")).toBe(20000);
    expect(store[`deliveryRequests/${id}`].escrowStatus).toBe("REFUNDED");
  });
});

describe("authority: users cannot skip the server path", () => {
  test("only the assigned driver / admin can mark a job delivered; buyer cannot", async () => {
    const r = await request();
    await run(acceptDeliveryRequest, "provider", { requestId: r.requestId });
    const job = await run(createDeliveryJob, "buyer", { requestId: r.requestId });
    Object.assign(store[`deliveryRoutes/${job.routeId}`], { driverId: "driver", status: "IN_TRANSIT" });
    await expect(run(updateDeliveryStatus, "buyer", { routeId: job.routeId, status: "DELIVERED" })).rejects.toThrow(/Only the assigned driver/);
    await expect(run(updateDeliveryStatus, "mallory", { routeId: job.routeId, status: "DELIVERED" })).rejects.toThrow(/Only the assigned driver/);
    await expect(run(confirmDelivery, "mallory", { orderId: "o1" })).rejects.toThrow(/Unauthorized/);
  });
  test("job creation requires the buyer and an ACCEPTED request; concurrent duplicate creation yields one job", async () => {
    const r = await request();
    await expect(run(createDeliveryJob, "buyer", { requestId: r.requestId })).rejects.toThrow(/accepted first/);
    await run(acceptDeliveryRequest, "provider", { requestId: r.requestId });
    await expect(run(createDeliveryJob, "mallory", { requestId: r.requestId })).rejects.toThrow(/Only the buyer/);
    const a = await run(createDeliveryJob, "buyer", { requestId: r.requestId });
    const b = await run(createDeliveryJob, "buyer", { requestId: r.requestId });
    expect(b).toMatchObject({ routeId: a.routeId, alreadyExists: true });
  });
});
