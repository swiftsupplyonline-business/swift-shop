import { planDeliveryPayout, outcomeAtSettlement, refundsOnRouteStatus, sanitizeDriverShareBps, isValidFee } from "../deliveryEconomics";

const sum = (l: { amountMinorUnits: number }[]) => l.reduce((a, b) => a + b.amountMinorUnits, 0);

describe("planDeliveryPayout", () => {
  test("provider is also the driver: one earnings line, no platform cut by default", () => {
    const l = planDeliveryPayout({ feeMinorUnits: 5000, providerId: "p", driverId: "p", driverShareBps: 7000 });
    expect(l).toEqual([{ kind: "PROVIDER_EARNINGS", uid: "p", amountMinorUnits: 5000 }]);
  });
  test("distinct driver: ledger distinguishes driver compensation from provider earnings", () => {
    const l = planDeliveryPayout({ feeMinorUnits: 5000, providerId: "p", driverId: "d", driverShareBps: 6000 });
    expect(l.map((x) => [x.kind, x.uid, x.amountMinorUnits])).toEqual([["DRIVER_COMPENSATION", "d", 3000], ["PROVIDER_EARNINGS", "p", 2000]]);
  });
  test("platform fee is taken first and the split always conserves the fee", () => {
    for (const fee of [1, 7, 99, 5000, 12345]) for (const pm of [0, 15, 100]) for (const bps of [0, 3333, 10000]) {
      const l = planDeliveryPayout({ feeMinorUnits: fee, providerId: "p", driverId: "d", driverShareBps: bps, platformFeePerMille: pm });
      expect(sum(l)).toBe(fee);
      l.forEach((x) => expect(x.amountMinorUnits).toBeGreaterThan(0));
    }
  });
  test("no driver assigned: provider earns the net fee", () => {
    expect(sum(planDeliveryPayout({ feeMinorUnits: 400, providerId: "p", driverId: "", driverShareBps: 5000 }))).toBe(400);
  });
  test("rejects bad fees / platform rates", () => {
    expect(() => planDeliveryPayout({ feeMinorUnits: -1, providerId: "p" })).toThrow();
    expect(() => planDeliveryPayout({ feeMinorUnits: 1.5, providerId: "p" })).toThrow();
    expect(() => planDeliveryPayout({ feeMinorUnits: 10, providerId: "" })).toThrow();
    expect(() => planDeliveryPayout({ feeMinorUnits: 10, providerId: "p", platformFeePerMille: 2000 })).toThrow();
  });
  test("untrusted driverShareBps is sanitised to 0", () => {
    for (const bad of [-1, 10001, 1.5, "50", null, undefined, NaN]) expect(sanitizeDriverShareBps(bad)).toBe(0);
    expect(sanitizeDriverShareBps(2500)).toBe(2500);
    expect(isValidFee(0)).toBe(true); expect(isValidFee(-1)).toBe(false);
  });
});

describe("escrow outcomes", () => {
  test("release only when the job was DELIVERED; otherwise refund; never touch non-held escrow", () => {
    expect(outcomeAtSettlement("HELD", "DELIVERED")).toBe("RELEASE");
    for (const s of ["IN_TRANSIT", "ASSIGNED", "FAILED", "CANCELLED", undefined]) expect(outcomeAtSettlement("HELD", s)).toBe("REFUND");
    for (const e of ["NONE", "REFUNDED", "RELEASED", undefined]) expect(outcomeAtSettlement(e, "DELIVERED")).toBe("NONE");
  });
  test("failed and cancelled jobs refund", () => {
    expect(refundsOnRouteStatus("FAILED")).toBe(true);
    expect(refundsOnRouteStatus("CANCELLED")).toBe(true);
    expect(refundsOnRouteStatus("DELIVERED")).toBe(false);
  });
});
