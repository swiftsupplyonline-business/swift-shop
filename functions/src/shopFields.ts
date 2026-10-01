/**
 * Pure validation for shop create/update payloads (no Firebase imports; unit-testable).
 * Clients may only ever supply the profile fields below. Ownership, verification, ratings and
 * counters are server-owned and are never accepted from a payload.
 */

export const EDITABLE_SHOP_FIELDS = [
    "name", "description", "logoUrl", "coverUrl", "category",
    "locationLat", "locationLng", "locationAddress", "isActive",
] as const;

const MAX_LEN: Record<string, number> = {
    name: 80, description: 2000, logoUrl: 2048, coverUrl: 2048, category: 60, locationAddress: 300,
};

export class ShopValidationError extends Error {}

/** Returns only allowlisted, type-checked fields. Throws ShopValidationError on bad input. */
export function pickShopFields(input: unknown, opts: { requireName: boolean }): Record<string, unknown> {
    if (!input || typeof input !== "object") throw new ShopValidationError("Shop payload required");
    const src = input as Record<string, unknown>;
    const out: Record<string, unknown> = {};

    for (const key of EDITABLE_SHOP_FIELDS) {
        if (!(key in src) || src[key] === undefined) continue;
        const v = src[key];
        if (key === "isActive") {
            if (typeof v !== "boolean") throw new ShopValidationError("isActive must be a boolean");
        } else if (key === "locationLat" || key === "locationLng") {
            if (typeof v !== "number" || !Number.isFinite(v)) throw new ShopValidationError(`${key} must be a number`);
            const limit = key === "locationLat" ? 90 : 180;
            if (Math.abs(v) > limit) throw new ShopValidationError(`${key} out of range`);
        } else {
            if (typeof v !== "string") throw new ShopValidationError(`${key} must be a string`);
            if (v.length > MAX_LEN[key]) throw new ShopValidationError(`${key} too long`);
        }
        out[key] = v;
    }

    if ("name" in out && !(out.name as string).trim()) throw new ShopValidationError("name cannot be empty");
    if (opts.requireName && !out.name) throw new ShopValidationError("name is required");
    if (!opts.requireName && Object.keys(out).length === 0) throw new ShopValidationError("No editable fields supplied");
    return out;
}
