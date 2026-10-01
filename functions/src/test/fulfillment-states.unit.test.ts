import { DELIVERY_STATUSES, ALLOWED_DELIVERY_TRANSITIONS, canTransition, isDeliveryStatus } from "../fulfillmentStates";

describe("canonical fulfillment state machine", () => {
  test("happy path is fully reachable", () => {
    const path = ["REQUESTED","ASSIGNED","AT_PICKUP","PICKUP_CONFIRMED","IN_TRANSIT","DELIVERED"] as const;
    for (let i = 0; i < path.length - 1; i++) expect(canTransition(path[i], path[i+1])).toBe(true);
  });
  