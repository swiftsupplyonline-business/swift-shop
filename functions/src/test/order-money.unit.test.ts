// Pure unit tests (no emulator) for the order money-path guards.
import { parseOrderItems, MoneyValidationError } from "../moneyValidation";
import { purchaseAndCommitInventory, returnCommittedInventory } from "../listing";
import { InventoryMode, ListingStatus, ListingType } from "../listing/types";

describe("parseOrderItems", () => {
  test("accepts whole quantities and defaults a missing quantity to 1", () => {
    expect(parseOrderItems([{ listingId: "abc123", quantity: 3 }, { listingId: "def456" }])).toEqual([
      { listingId: "abc123", quantity: 3 },
      { listingId: "def456", quantity: 1 },
    ]);
  });
  test("merges repeated listings so each is written once per transaction", () => {
    expect(parseOrderItems([{ listingId: "a1", quantity: 2 }, { listingId: "a1", quantity: 3 }])).toEqual([
      { listingId: "a1", quantity: 5 },
    ]);
  });
  test("rejects negative, zero, fractional, non-numeric, NaN and oversized quantities", () => {
    for (const quantity of [-1, 0, 0.5, "2", NaN, Infinity, 1000, Number.MAX_SAFE_INTEGER + 10]) {
      expect(() => parseOrderItems([{ listingId: "a1", quantity }])).toThrow(MoneyValidationError);
    }
    expect(() => parseOrderItems([{ listingId: "a1", quantity: 600 }, { listingId: "a1", quantity: 600 }])).toThrow(MoneyValidationError);
  });
  test("rejects bad shapes and ids", () => {
    for (const bad of [undefined, null, "x", {}, [], [null], [{}], [{ listingId: "a/b" }], [{ listingId: 5 }],
        Array.from({ length: 51 }, (_, i) => ({ listingId: "l" + i }))]) {
      expect(() => parseOrderItems(bad)).toThrow(MoneyValidationError);
    }
  });
});

function runFn(fn: any, listing: any, qty: number) {
  const updates: any[] = [];
  const tx: any = { update: (_r: any, u: any) => updates.push(u) };
  fn(tx, {} as any, listing, qty, "NOW" as any);
  return updates[0];
}
const stocked = { inventoryMode: InventoryMode.STOCKED, listingType: ListingType.BUY, title: "T",
  stockQuantity: 5, reservedQuantity: 2, status: ListingStatus.ACTIVE };

describe("purchaseAndCommitInventory", () => {
  test("decrements stock, leaves other buyers' reservations untouched", () => {
    const u = runFn(purchaseAndCommitInventory, stocked, 2);
    expect(u.stockQuantity).toBe(3);
    expect(u.reservedQuantity).toBeUndefined();
  });
  test("flips to OUT_OF_STOCK when the last available unit is bought", () => {
    const u = runFn(purchaseAndCommitInventory, stocked, 3);
    expect(u.stockQuantity).toBe(2);
    expect(u.status).toBe(ListingStatus.OUT_OF_STOCK);
  });
  test("rejects over-purchase and non-positive/fractional quantities", () => {
    expect(() => runFn(purchaseAndCommitInventory, stocked, 4)).toThrow();
    for (const q of [0, -2, 1.5, NaN]) expect(() => runFn(purchaseAndCommitInventory, stocked, q)).toThrow();
  });
});

describe("returnCommittedInventory", () => {
  test("restores stock and re-activates an OUT_OF_STOCK listing", () => {
    const u = runFn(returnCommittedInventory, { ...stocked, stockQuantity: 2, reservedQuantity: 2, status: ListingStatus.OUT_OF_STOCK }, 2);
    expect(u.stockQuantity).toBe(4);
    expect(u.status).toBe(ListingStatus.ACTIVE);
  });
});
