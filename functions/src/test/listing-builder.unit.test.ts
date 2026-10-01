import { buildNewListingDoc, statusFromAvailabilityToggle, ListingStatus } from "../listing";

describe("buildNewListingDoc", () => {
  const doc: any = buildNewListingDoc({
    clientFields: { title: "T", sellerId: "FORGED", status: "SUSPENDED", stockQuantity: 999, reservedQuantity: 50, priceMinorUnits: 100 },
    listingId: "L1", sellerId: "real", title: "Tee", listingType: "PRODUCT",
    inventoryMode: "STOCKED", stockQuantity: 3, shareSlug: "tee", now: "NOW" as any,
  });
  test("server-owned fields override forged client values", () => {
    expect(doc.sellerId).toBe("real");
    expect(doc.status).toBe(ListingStatus.ACTIVE);
    expect(doc.stockQuantity).toBe(3);
    expect(doc.reservedQuantity).toBe(0);
    expect(doc.isAvailable).toBe(true);
    expect(doc.priceMinorUnits).toBe(100);
  });
});

describe("statusFromAvailabilityToggle", () => {
  test("pause and resume", () => {
    expect(statusFromAvailabilityToggle(ListingStatus.ACTIVE, false)).toBe(ListingStatus.PAUSED);
    expect(statusFromAvailabilityToggle(ListingStatus.PAUSED, true)).toBe(ListingStatus.ACTIVE);
  });
  test("cannot resurrect suspended or out-of-stock via flag", () => {
    expect(statusFromAvailabilityToggle(ListingStatus.SUSPENDED, true)).toBe(ListingStatus.SUSPENDED);
    expect(statusFromAvailabilityToggle(ListingStatus.OUT_OF_STOCK, true)).toBe(ListingStatus.OUT_OF_STOCK);
  });
});
