import { parseAmount, idempotencyDocId, requireText, requireUid, MoneyValidationError } from "../moneyValidation";

describe("parseAmount", () => {
  test("accepts whole positive LSL minor units; default currency LSL", () => {
    expect(parseAmount({ minorUnits: 25000, currency: "LSL" })).toEqual({ minorUnits: 25000, currency: "LSL" });
    expect(parseAmount({ minorUnits: 100 }).currency).toBe("LSL");
  });
  test("rejects fractional, zero, negative, non-numeric, unsafe, foreign currency", () => {
    for (const bad of [{ minorUnits: 1.5 }, { minorUnits: 0 }, { minorUnits: -5 }, { minorUnits: "100" },
        { minorUnits: Number.MAX_SAFE_INTEGER + 10 }, { minorUnits: 100, currency: "USD" }, null, undefined, "x", {}]) {
      expect(() => parseAmount(bad)).toThrow(MoneyValidationError);
    }
  });
});

describe("idempotencyDocId", () => {
  test("namespaces by user and scope so keys cannot collide across users or operations", () => {
    const k = "3f2b8a1e-5c7d-4e21-9a0b-1c2d3e4f5a6b";
    expect(idempotencyDocId("alice", "withdraw", k)).not.toBe(idempotencyDocId("bob", "withdraw", k));
    expect(idempotencyDocId("alice", "withdraw", k)).not.toBe(idempotencyDocId("alice", "p2p", k));
  });
  test("accepts UUIDs and the web client's key format; rejects unsafe values", () => {
    expect(() => idempotencyDocId("u", "purchase", "web_1700000000000_abc123xyz")).not.toThrow();
    for (const bad of ["short", "has/slash-12345", "has space 12345", "x".repeat(200), 12345678, null, undefined, ".."]) {
      expect(() => idempotencyDocId("u", "p", bad)).toThrow(MoneyValidationError);
    }
  });
});

describe("requireText / requireUid", () => {
  test("bounds and shape", () => {
    expect(requireText("  +26612345678 ", "phone", 20)).toBe("+26612345678");
    expect(() => requireText({}, "x")).toThrow(MoneyValidationError);
    expect(() => requireText("", "x")).toThrow(MoneyValidationError);
    expect(() => requireText("y".repeat(500), "x", 50)).toThrow(MoneyValidationError);
    expect(requireUid("abcDEF123_-", "uid")).toBe("abcDEF123_-");
    expect(() => requireUid("a/b", "uid")).toThrow(MoneyValidationError);
    expect(() => requireUid(42, "uid")).toThrow(MoneyValidationError);
  });
});
