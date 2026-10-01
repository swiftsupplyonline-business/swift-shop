// Unit test with an in-memory Firestore fake (no emulator).
const store: Record<string, any> = {};
let idCounter = 0;
jest.mock("firebase-admin", () => {
  const ref = (col: string, id: string) => ({ path: `${col}/${id}`, id });
  const db = {
    collection: (col: string) => ({ doc: (id?: string) => ref(col, id || `auto${++idCounter}`) }),
    runTransaction: async (fn: any) => {
      const writes: Array<() => void> = [];
      const tx = {
        get: async (r: any) => ({ exists: r.path in store, data: () => store[r.path] }),
        update: (r: any, u: any) => writes.push(() => { store[r.path] = { ...store[r.path], ...u }; }),
        set: (r: any, d: any) => writes.push(() => { store[r.path] = d; }),
      };
      const out = await fn(tx);
      writes.forEach((w) => w());
      return out;
    },
  };
  const firestore: any = () => db;
  firestore.FieldValue = { serverTimestamp: () => "TS", increment: (n: number) => n };
  firestore.Timestamp = { now: () => ({ toMillis: () => 0 }), fromMillis: (n: number) => n };
  return { firestore, apps: [1], initializeApp: jest.fn() };
});

import { rejectWithdrawal } from "../finance";

const call = (data: any, token: any = { admin: true }) =>
  (rejectWithdrawal as any).run({ data, auth: { uid: "admin1", token } });

beforeEach(() => {
  for (const k of Object.keys(store)) delete store[k];
  store["wallets/u1"] = { availableBalanceMinorUnits: 1000, pendingBalanceMinorUnits: 5000 };
  store["walletTransactions/w1"] = { type: "WITHDRAWAL", status: "PENDING", userId: "u1", amountMinorUnits: 5000, currency: "LSL" };
});

describe("rejectWithdrawal", () => {
  test("returns the money to available, clears pending, writes a reversal ledger entry", async () => {
    const res = await call({ transactionId: "w1", reason: "bad number" });
    expect(res).toEqual({ transactionId: "w1", status: "FAILED", idempotent: false });
    expect(store["wallets/u1"].availableBalanceMinorUnits).toBe(6000);
    expect(store["wallets/u1"].pendingBalanceMinorUnits).toBe(0);
    expect(store["walletTransactions/w1"].status).toBe("FAILED");
    const ledger = Object.entries(store).filter(([k]) => k.startsWith("ledgerEntries/")).map(([, v]) => v);
    expect(ledger).toHaveLength(1);
    expect(ledger[0]).toMatchObject({ debitAccount: "system_withdrawal_escrow", creditAccount: "user_u1", amountMinorUnits: 5000 });
  });
  test("is idempotent: a second call does not refund twice", async () => {
    await call({ transactionId: "w1" });
    const again = await call({ transactionId: "w1" });
    expect(again.idempotent).toBe(true);
    expect(store["wallets/u1"].availableBalanceMinorUnits).toBe(6000);
  });
  test("cannot reject a completed withdrawal or a non-withdrawal", async () => {
    store["walletTransactions/w1"].status = "COMPLETED";
    await expect(call({ transactionId: "w1" })).rejects.toThrow(/not PENDING/);
    store["walletTransactions/w1"] = { type: "DEPOSIT", status: "PENDING", userId: "u1", amountMinorUnits: 5000 };
    await expect(call({ transactionId: "w1" })).rejects.toThrow(/not a WITHDRAWAL/);
    expect(store["wallets/u1"].availableBalanceMinorUnits).toBe(1000);
  });
  test("admin only; refuses when pending is smaller than the amount", async () => {
    await expect(call({ transactionId: "w1" }, {})).rejects.toThrow(/Admin/);
    store["wallets/u1"].pendingBalanceMinorUnits = 100;
    await expect(call({ transactionId: "w1" })).rejects.toThrow(/integrity/);
    expect(store["wallets/u1"].availableBalanceMinorUnits).toBe(1000);
  });
});
