import { DELIVERY_STATUSES, ALLOWED_DELIVERY_TRANSITIONS, canTransition, isDeliveryStatus } from "../fulfillmentStates";

describe("canonical fulfillment state machine", () => {
  test("happy path is fully reachable", () => {
    const path = ["REQUESTED","ASSIGNED","AT_PICKUP","PICKUP_CONFIRMED","IN_TRANSIT","DELIVERED"] as const;
    for (let i = 0; i < path.length - 1; i++) expect(canTransition(path[i], path[i+1])).toBe(true);
  });
  test("legacy PICKUP is not a valid status", () => {
    expect(isDeliveryStatus("PICKUP")).toBe(false);
  });
  test("every transition target is a valid status", () => {
    for (const tos of Object.values(ALLOWED_DELIVERY_TRANSITIONS)) for (const t of tos) expect(DELIVERY_STATUSES).toContain(t);
  });
  test("terminal states are terminal; no skipping", () => {
    for (const t of ["DELIVERED","FAILED","CANCELLED"]) expect(ALLOWED_DELIVERY_TRANSITIONS[t as "FAILED"]).toEqual([]);
    expect(canTransition("ASSIGNED","IN_TRANSIT")).toBe(false);
    expect(canTransition("REQUESTED","DELIVERED")).toBe(false);
    expect(canTransition("IN_TRANSIT","CANCELLED")).toBe(false);
  });
});
