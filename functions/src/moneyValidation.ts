/**
 * Pure input validation for money-moving callables (no Firebase imports; unit-testable).
 */

export class MoneyValidationError extends Error {}

export const SUPPORTED_CURRENCY = "LSL";

/** Amount must be a positive safe integer of minor units in the platform currency. */
export function parseAmount(amount: unknown): { minorUnits: number; currency: string } {
    if (!amount || typeof amount !== "object") throw new MoneyValidationError("Invalid amount.");
    const a = amount as Record<string, unknown>;
    const minorUnits = a.minorUnits;
    if (typeof minorUnits !== "number" || !Number.isSafeInteger(minorUnits) || minorUnits <= 0) {
        throw new MoneyValidationError("Amount must be a positive whole number of minor units.");
    }
    const currency = a.currency === undefined || a.currency === null ? SUPPORTED_CURRENCY : a.currency;
    if (currency !== SUPPORTED_CURRENCY) throw new MoneyValidationError(`Unsupported currency. Only ${SUPPORTED_CURRENCY} is supported.`);
    return { minorUnits, currency: SUPPORTED_CURRENCY };
}

/**
 * Idempotency keys are client-chosen. They are namespaced by user and operation so one user can
 * never read, collide with, or pre-claim another user's key, and so a withdrawal key cannot be
 * replayed as a transfer. Only [A-Za-z0-9_-] (UUIDs qualify) so the value is a safe document id.
 */
export function idempotencyDocId(uid: string, scope: string, key: unknown): string {
    if (typeof key !== "string" || !/^[A-Za-z0-9_-]{8,128}$/.test(key)) {
        throw new MoneyValidationError("Invalid idempotencyKey.");
    }
    return `${uid}_${scope}_${key}`;
}

export function requireText(value: unknown, field: string, max = 120): string {
    if (typeof value !== "string" || !value.trim() || value.length > max) {
        throw new MoneyValidationError(`Invalid ${field}.`);
    }
    return value.trim();
}

export function requireUid(value: unknown, field: string): string {
    // Firebase uids are short alphanumeric strings; reject path separators and oversized input.
    if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
        throw new MoneyValidationError(`Invalid ${field}.`);
    }
    return value;
}
