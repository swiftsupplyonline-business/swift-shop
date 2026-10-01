/**
 * Pure helpers for the public /s/ (product) and /d/ (delivery) smart links.
 * Kept free of Firebase imports so they can be unit-tested without an emulator.
 */

/** decodeURIComponent that never throws: returns null for malformed escapes (e.g. "%E0%A4%A"). */
export function safeDecode(value: string): string | null {
    try { return decodeURIComponent(value); } catch { return null; }
}

/** Parses "/s/<shop>/<product>" or "/d/<shop>/<product>". Returns null for anything malformed. */
export function parseSharePath(prefix: "s" | "d", path: string): { shopSlug: string; productSlug: string } | null {
    const m = path.match(new RegExp(`^/${prefix}/([^/]+)/([^/]+)/?$`));
    if (!m) return null;
    const shopSlug = safeDecode(m[1]);
    const productSlug = safeDecode(m[2]);
    if (!shopSlug || !productSlug) return null;
    if (shopSlug.length > 120 || productSlug.length > 120) return null;
    return { shopSlug, productSlug };
}

/**
 * Normalised slugs only ever contain [a-z0-9-]. The legacy "scan everything" fallback is
 * pointless (and an abuse vector) for anything else, so callers skip it.
 */
export function isScannableLegacySlug(slug: string): boolean {
    return /^[a-z0-9-]{1,100}$/.test(slug);
}

/** Statuses that must never be publicly rendered. PAUSED / OUT_OF_STOCK render as "unavailable". */
const HIDDEN_STATUSES = new Set(["DRAFT", "ARCHIVED", "SUSPENDED", "DELETED"]);

export function isPubliclyVisible(status: unknown): boolean {
    // Pre-engine documents have no status: treat as visible (backward compatible).
    if (status === undefined || status === null || status === "") return true;
    return !HIDDEN_STATUSES.has(String(status));
}
