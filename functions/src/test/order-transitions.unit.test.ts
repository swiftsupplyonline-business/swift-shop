import { resolveStatusUpdate } from "../orderTransitions";

const base = { isAdmin: false, isSeller: false, isBuyer: false, paymentStatus: "SUCCESS" };

describe("resolveStatusUpdate", () => {
  test("seller moves a paid order CONFIRMED -> PROCESSING -> READY", () => {
    expect(() => resolveStatusUpdate({ ...base, isSeller: true, current: "CONFIRMED", next: "PROCESSING" })).not.toThrow();
    expect(() => resolveStatusUpdate({ ...base, isSeller: true, current: "PROCESSING", next: "READY" })).not.toThrow();
  });
  test("nobody can cancel/refund through this function (no refund, no stock release)", () => {
    for (const who of [{ isBuyer: true }, { isSeller: true }, { isAdmin: true }]) {
      for (const next of ["CANCELLED", "REFUNDED"]) {
        expect(() => resolveStatusUpdate({ ...base, ...who, current: "RESERVED", next })).toThrow(/cancelOrder/);
      }
    }
  });
  test("buyer cannot change status at all", () => {
    expect(() => resolveStatusUpdate({ ...base, isBuyer: true, current: "CONFIRMED", next: "PROCESSING" })).toThrow();
  });
  test("seller cannot skip steps, go backwards, or mark delivered", () => {
    for (const [current, next] of [["CONFIRMED", "READY"], ["READY", "PROCESSING"], ["READY", "DELIVERED"], ["PROCESSING", "DELIVERED"]]) {
      expect(() => resolveStatusUpdate({ ...base, isSeller: true, current, next })).toThrow();
    }
  });
  test("unpaid orders cannot be advanced by seller or admin", () => {
    for (const who of [{ isSeller: true }, { isAdmin: true }]) {
      expect(() => resolveStatusUpdate({ ...base, ...who, paymentStatus: "PENDING", current: "CONFIRMED", next: "PROCESSING" })).toThrow(/paid/);
    }
  });
  test("admin follows the same path (plus READY -> DISPATCHED) but cannot set DELIVERED or arbitrary states", () => {
    expect(() => resolveStatusUpdate({ ...base, isAdmin: true, current: "READY", next: "DISPATCHED" })).not.toThrow();
    for (const [current, next] of [["RESERVED", "CONFIRMED"], ["DISPATCHED", "DELIVERED"], ["CONFIRMED", "DELIVERED"], ["CANCELLED", "PROCESSING"]]) {
      expect(() => resolveStatusUpdate({ ...base, isAdmin: true, current, next })).toThrow();
    }
  });
  test("seller self-delivery: READY -> DISPATCHED only when the seller is the courier", () => {
    expect(() => resolveStatusUpdate({ ...base, isSeller: true, sellerIsCourier: true, current: "READY", next: "DISPATCHED" })).not.toThrow();
    expect(() => resolveStatusUpdate({ ...base, isSeller: true, sellerIsCourier: false, current: "READY", next: "DISPATCHED" })).toThrow();
    expect(() => resolveStatusUpdate({ ...base, isSeller: true, current: "READY", next: "DISPATCHED" })).toThrow();
  });
  test("seller self-delivery never lets the seller mark DELIVERED or skip READY", () => {
    expect(() => resolveStatusUpdate({ ...base, isSeller: true, sellerIsCourier: true, current: "DISPATCHED", next: "DELIVERED" })).toThrow();
    expect(() => resolveStatusUpdate({ ...base, isSeller: true, sellerIsCourier: true, current: "PROCESSING", next: "DISPATCHED" })).toThrow();
  });
});
