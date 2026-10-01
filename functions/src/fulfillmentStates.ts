/**
 * Canonical fulfillment state machine. Single source of truth for delivery-route statuses.
 * Every surface (Functions, Android, Web, Admin, tests) must use exactly these strings.
 *
 * REQUESTED -> ASSIGNED -> AT_PICKUP -> PICKUP_CONFIRMED -> IN_TRANSIT -> DELIVERED
 * CANCELLED is reachable from REQUESTED/ASSIGNED; FAILED from AT_PICKUP onward.
 */
export const DELIVERY_STATUSES = [
    "REQUESTED", "ASSIGNED", "AT_PICKUP", "PICKUP_CONFIRMED",
    "IN_TRANSIT", "DELIVERED", "FAILED", "CANCELLED",
] as const;

export type DeliveryStatus = typeof DELIVERY_STATUSES[number];

export const ALLOWED_DELIVERY_TRANSITIONS: Record<DeliveryStatus, DeliveryStatus[]> = {
    REQUESTED:        ["ASSIGNED", "CANCELLED"],
    ASSIGNED:         ["AT_PICKUP", "CANCELLED"],
    AT_PICKUP:        ["PICKUP_CONFIRMED", "FAILED"],
    PICKUP_CONFIRMED: ["IN_TRANSIT", "FAILED"],
    IN_TRANSIT:       ["DELIVERED", "FAILED"],
    DELIVERED:        [],
    FAILED:           [],
    CANCELLED:        [],
};

export function isDeliveryStatus(s: unknown): s is DeliveryStatus {
    return typeof s === "string" && (DELIVERY_STATUSES as readonly string[]).includes(s);
}

export function canTransition(from: unknown, to: DeliveryStatus): boolean {
    return isDeliveryStatus(from) && ALLOWED_DELIVERY_TRANSITIONS[from].includes(to);
}
