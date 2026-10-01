/**
 * Pure decision logic for updateOrderStatus (no Firebase imports; unit-testable).
 *
 * updateOrderStatus only moves an order along the fulfillment path. Anything that moves money or stock
 * has its own function and is refused here:
 *   - cancelling / refunding      -> cancelOrder
 *   - delivered / settled         -> confirmDelivery (buyer) or the fulfillment route
 */
export const FULFILLMENT_STEPS: Record<string, string[]> = {
    CONFIRMED: ["PROCESSING"],
    PROCESSING: ["READY"],
};

// Seller-driven pickup/hand-over. Courier-driven DISPATCHED is set by the fulfillment route instead.
const ADMIN_STEPS: Record<string, string[]> = {
    CONFIRMED: ["PROCESSING"],
    PROCESSING: ["READY"],
    READY: ["DISPATCHED"],
};

const PAID = new Set(["SUCCESS", "PAID"]);

export function resolveStatusUpdate(p: {
    isAdmin: boolean; isSeller: boolean; isBuyer: boolean;
    current: string; next: string; paymentStatus?: string;
}): void {
    const { current, next } = p;
    if (next === "CANCELLED" || next === "REFUNDED") {
        throw new Error("Use cancelOrder to cancel an order");
    }
    if (p.isSeller) {
        if (!PAID.has(p.paymentStatus || "")) throw new Error("Order has not been paid");
        if (!(FULFILLMENT_STEPS[current] || []).includes(next)) {
            throw new Error(`Seller cannot transition order from ${current} to ${next}`);
        }
        return;
    }
    if (p.isAdmin) {
        if (!PAID.has(p.paymentStatus || "")) throw new Error("Order has not been paid");
        if (!(ADMIN_STEPS[current] || []).includes(next)) {
            throw new Error(`Cannot transition order from ${current} to ${next} here`);
        }
        return;
    }
    throw new Error("Unauthorized status update");
}
