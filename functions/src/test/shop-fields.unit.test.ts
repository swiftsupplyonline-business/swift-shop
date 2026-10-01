import { pickShopFields, ShopValidationError } from "../shopFields";

describe("pickShopFields", () => {
  test("drops server-owned fields (verification, rating, counters, owner)", () => {
    const out = pickShopFields({ name: "My Shop", isVerified: true, rating: 5, followerCount: 999, ownerId: "evil", listingCount: 50 }, { requireName: true });
    expect(out).toEqual({ name: "My Shop" });
  });
  test("accepts valid profile edits", () => {
    const out = pickShopFields({ description: "New", locationLat: -29.3, locationLng: 27.5, isActive: false }, { requireName: false });
    expect(out).toEqual({ description: "New", locationLat: -29.3, locationLng: 27.5, isActive: false });
  });
  test("rejects bad types, ranges, empties", () => {
    expect(() => pickShopFields({ name: 5 }, { requireName: true })).toThrow(ShopValidationError);
    expect(() => pickShopFields({ locationLat: 200 }, { requireName: false })).toThrow(ShopValidationError);
    expect(() => pickShopFields({ name: "  " }, { requireName: false })).toThrow(ShopValidationError);
    expect(() => pickShopFields({ isActive: "yes" }, { requireName: false })).toThrow(ShopValidationError);
    expect(() => pickShopFields({ name: "x".repeat(81) }, { requireName: true })).toThrow(ShopValidationError);
    expect(() => pickShopFields({ isVerified: true }, { requireName: false })).toThrow(ShopValidationError); // nothing editable left
    expect(() => pickShopFields({}, { requireName: true })).toThrow(ShopValidationError);
    expect(() => pickShopFields(null, { requireName: true })).toThrow(ShopValidationError);
  });
});
