import { parseSharePath, safeDecode, isScannableLegacySlug, isPubliclyVisible } from "../shareRouting";

describe("share link routing", () => {
  test("valid /s/ and /d/ paths parse; trailing slash ok", () => {
    expect(parseSharePath("s", "/s/my-shop/red-shoes")).toEqual({ shopSlug: "my-shop", productSlug: "red-shoes" });
    expect(parseSharePath("d", "/d/fast-co/express/")).toEqual({ shopSlug: "fast-co", productSlug: "express" });
  });
  test("malformed paths return null instead of throwing", () => {
    expect(safeDecode("%E0%A4%A")).toBeNull();
    expect(parseSharePath("s", "/s/shop/%E0%A4%A")).toBeNull();
    expect(parseSharePath("s", "/s/onlyshop")).toBeNull();
    expect(parseSharePath("s", "/s/a/b/c")).toBeNull();
    expect(parseSharePath("d", "/s/a/b")).toBeNull();
    expect(parseSharePath("s", "/s/" + "x".repeat(200) + "/p")).toBeNull();
  });
  test("junk slugs skip the full-collection legacy scan", () => {
    expect(isScannableLegacySlug("red-shoes")).toBe(true);
    expect(isScannableLegacySlug("../../etc")).toBe(false);
    expect(isScannableLegacySlug("Has Space")).toBe(false);
    expect(isScannableLegacySlug("")).toBe(false);
  });
  test("only published-ish listings are public", () => {
    for (const s of ["ACTIVE", "PAUSED", "OUT_OF_STOCK", undefined, ""]) expect(isPubliclyVisible(s)).toBe(true);
    for (const s of ["DRAFT", "ARCHIVED", "SUSPENDED", "DELETED"]) expect(isPubliclyVisible(s)).toBe(false);
  });
});
