import { DELIVERY_STATUSES, ALLOWED_DELIVERY_TRANSITIONS, canTransition, isDeliveryStatus } from "../fulfillmentStates";

describe("canonical fulfillment state machine", () => {
  test("declares only canonical delivery states", () => {
    expect(DELIVERY_STATUSES).toEqual([
      "REQUESTED", "ASSIGNED", "AT_PICKUP", "PICKUP_CONFIRMED",
      "IN_TRANSIT", "DELIVERED", "FAILED", "CANCELLED",
    ]);
    expect(isDeliveryStatus("PICKUP")).toBe(false);
    expect(isDeliveryStatus("PICKUP_CONFIRMED")).toBe(true);
  });

  test("happy path is fully reachable", () => {
    const path = ["REQUESTED", "ASSIGNED", "AT_PICKUP", "PICKUP_CONFIRMED", "IN_TRANSIT", "DELIVERED"] as const;
    for (let i = 0; i < path.length - 1; i++) {
      expect(canTransition(path[i], path[i + 1])).toBe(true);
    }
  });

  test("terminal states and invalid transitions are rejected", () => {
    expect(ALLOWED_DELIVERY_TRANSITIONS.DELIVERED).toEqual([]);
    expect(ALLOWED_DELIVERY_TRANSITIONS.FAILED).toEqual([]);
    expect(ALLOWED_DELIVERY_TRANSITIONS.CANCELLED).toEqual([]);
    expect(canTransition("REQUESTED", "IN_TRANSIT")).toBe(false);
    expect(canTransition("ASSIGNED", "DELIVERED")).toBe(false);
    expect(canTransition("DELIVERED", "IN_TRANSIT")).toBe(false);
  });
});
