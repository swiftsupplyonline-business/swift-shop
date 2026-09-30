/**
 * Stable URL slug helpers for public Swift share links.
 *
 * Slugs are derived from display names/titles once at write time and then
 * stored on the owning Firestore document. Existing documents can still be
 * resolved by deriving the same slug at read time.
 */
export function normalizeShareSlug(value: unknown): string {
    return String(value ?? "")
        .normalize("NFKD")
        .replace(/[\u0300-\u036f]/g, "")
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "")
        .replace(/-{2,}/g, "-");
}
