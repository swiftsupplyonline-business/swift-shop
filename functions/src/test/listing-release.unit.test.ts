// Pure unit test (no emulator): releaseInventory frees stock and restores availability.
import { releaseInventory } from "../listing";
import { InventoryMode, ListingStatus } from "../listing/types";

function run(listing: any, qty: number) {
  const updates: any[] = [];
  const tx: any = { update: (_ref: any, u: any) => updates.push(u) };
  releaseInventory(tx, {} as any, listing, qty, "NOW" as any);
  return updates[0];
}

describe("releaseInventory", () => {
  const base = { inventoryMode: InventoryMode.STOCKED, stockQuantity: 5, reservedQuantity: 5 };
  test("OUT_OF_STOCK listing becomes ACTIVE when reservation released", () => {
    const u = run({ ...base, status: ListingStatus.OUT_OF_STOCK, isAvailable: false }, 2);
    expect(u.reservedQuantity).toBe(3);
    expect(u.status).toBe(ListingStatus.ACTIVE);
    expect(u.isAvailable).toBe(true);
  });
  test("reserved quantity never goes negative", () => {
    const u = run({ ...base, reservedQuantity: 1, status: ListingStatus.ACTIVE }, 9);
    expect(u.reservedQuantity).toBe(0);
  });
});
