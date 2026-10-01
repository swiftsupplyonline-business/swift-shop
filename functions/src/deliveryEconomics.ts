/**
 * Delivery fee economics: pure planning functions (no Firebase imports; unit-testable).
 *
 * Reuses the canonical wallet / ledgerEntries infrastructure. No second wallet or ledger exists:
 * the buyer's wallet is debited into a ledger account named `system_delivery_escrow`, and later
 * either refunded to the buyer or released to the provider / driver / platform.
 *
 * Actors (never assume they are the same entity):
 *  - buyer     : pays the delivery fee (Swift Wallet only).
 *  - provider  : author of the delivery listing (`merchantId` on the request) - accepts the request.
 *  - driver    : `driverId` on the fulfillment route - may equal the provider.
 *  - platform  : takes `platformFeePerMille` of the fee (0 until the business decides otherwise).
 */

export const DELIVERY_ESCROW_ACCOUNT = "system_delivery_escrow";
export const PLATFORM_FEES_ACCOUNT = "system_fees";

/** Platform cut of the delivery fee, per mille. 15‰ = 1.5%. */
export const DEFAULT_DELIVERY_PLATFORM_FEE_PER_MILLE = 15;

export type EscrowStatus = "NONE" | "HELD" | "REFUNDED" | "RELEASED";

export interface PayoutLine {
    kind: "PLATFORM_FEE" | "DRIVER_COMPENSATION" | "PROVIDER_EARNINGS";
    /** wallet owner uid, or null for the platform */
    uid: string | null;
    amountMinorUnits: number;
}

export function isValidFee(fee: unknown): fee is number {
    return typeof fee === "number" && Number.isSafeInteger(fee) && fee >= 0;
}

/** Listing may carry `driverShareBps` (0..10000): the driver's share of the net fee when driver != provider. */
export function sanitizeDriverShareBps(v: unknown): number {
    return typeof v === "number" && Number.isInteger(v) && v >= 0 && v <= 10000 ? v : 0;
}

/**
 * Splits a delivery fee. Always conserves: sum(lines) === fee.
 *  platform = floor(fee * perMille / 1000)
 *  net      = fee - platform
 *  driver   = floor(net * driverShareBps / 10000), only if a distinct driver exists
 *  provider = net - driver
 */
export function planDeliveryPayout(p: {
    feeMinorUnits: number;
    providerId: string;
    driverId?: string | null;
    driverShareBps?: number;
    platformFeePerMille?: number;
}): PayoutLine[] {
    if (!isValidFee(p.feeMinorUnits)) throw new Error("Invalid delivery fee");
    if (!p.providerId) throw new Error("Delivery provider is required");
    const perMille = p.platformFeePerMille ?? DEFAULT_DELIVERY_PLATFORM_FEE_PER_MILLE;
    if (!Number.isInteger(perMille) || perMille < 0 || perMille > 1000) throw new Error("Invalid platform fee");

    const platform = Math.floor((p.feeMinorUnits * perMille) / 1000);
    const net = p.feeMinorUnits - platform;
    const distinctDriver = !!p.driverId && p.driverId !== p.providerId;
    const driver = distinctDriver ? Math.floor((net * sanitizeDriverShareBps(p.driverShareBps)) / 10000) : 0;
    const provider = net - driver;

    const lines: PayoutLine[] = [];
    if (platform > 0) lines.push({ kind: "PLATFORM_FEE", uid: null, amountMinorUnits: platform });
    if (driver > 0) lines.push({ kind: "DRIVER_COMPENSATION", uid: p.driverId!, amountMinorUnits: driver });
    if (provider > 0) lines.push({ kind: "PROVIDER_EARNINGS", uid: p.providerId, amountMinorUnits: provider });
    return lines;
}

export type EscrowOutcome = "NONE" | "REFUND" | "RELEASE";

/**
 * What to do with a HELD escrow when the order is settled by the buyer.
 * Delivery fee is earned only if the fulfillment job actually reached DELIVERED.
 */
export function outcomeAtSettlement(escrowStatus: unknown, routeStatus: unknown): EscrowOutcome {
    if (escrowStatus !== "HELD") return "NONE";
    return routeStatus === "DELIVERED" ? "RELEASE" : "REFUND";
}

/** Fulfillment job statuses that end the job without delivery and therefore refund a HELD escrow. */
export function refundsOnRouteStatus(status: unknown): boolean {
    return status === "CANCELLED" || status === "FAILED";
}

export function walletAfterRefund(currentAvailable: number, amount: number): number {
    if (!isValidFee(currentAvailable) || !isValidFee(amount)) throw new Error("Invalid wallet amounts");
    return currentAvailable + amount;
}
