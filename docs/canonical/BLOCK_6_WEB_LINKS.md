# Block 6: Web smart links (/s/ and /d/)

## Already correct
- `/s/<shop>/<product>` and `/d/<shop>/<product>` are served by `renderSharedListing` / `renderSharedDelivery`;
  `/listing/<id>` 301-redirects to the canonical link (kept permanently for links already shared).
- `/s/` of a DELIVER listing redirects to `/d/`; `/d/` of a non-delivery listing is a 404.
- Web checkout calls only `calculatePurchaseTotal` / `createPurchaseOrder` (Block 2).

## Fixed in block-6-web-links
- Malformed escapes (e.g. `/s/shop/%E0%A4%A`) made `decodeURIComponent` throw outside the try block -> crash/500. Now 404 (`shareRouting.ts`).
- Any unknown slug triggered a full scan of the `shops` collection (cost + abuse vector). The legacy scan now runs only for
  well-formed slugs (`[a-z0-9-]`, max 100).
- DRAFT / ARCHIVED / SUSPENDED / DELETED listings were publicly rendered. They now 404 on `/s/`, `/d/` and `/listing/`.
  PAUSED / OUT_OF_STOCK still render (shown as unavailable).
- 13 emulator-free unit tests across routing, fulfillment states and the listing engine pass.

## Follow-ups
- Android `CreateListingScreen` still builds a `/listing/<id>` share URL (works via the redirect). Better: have `createListing`
  return the canonical `shareUrl` and use it.
- Cache: full-collection legacy fallback should disappear once a backfill sets `shareSlug` on every shop/listing.
- Emulator tests not run in the authoring sandbox.
