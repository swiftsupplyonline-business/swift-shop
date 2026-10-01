# Block 1 Audit: Listing Engine authority

Engine: `functions/src/listing/` (activity, inventory, lifecycle, ownership, slug, types, validate).
Audited on branch block-0-canonical-baseline (from canonical-platform-integration-2026-09-29).

## Listing writes that bypass the engine
| Location | What it does | Required change |
|---|---|---|
| `commerce.ts` createListing (~L890-1018) | derives status/isAvailable, writes stockQuantity=, reservedQuantity: 0, isAvailable inline (~L947-970) | delegate to engine create + validate + slug + ownership + activity |
| `commerce.ts` updateListing (~L1019-1141) | maps isAvailable<->PAUSED/ACTIVE inline (~L1073-1077) | delegate to engine lifecycle transition |
| `commerce.ts` deleteListing (~L1142), restockListing (~L1194) | listing mutations in commerce | move to engine (delete / restock) |
| `reservations.ts` (~L34) | decrements listing.reservedQuantity inline on expiry | call engine releaseInventory |
| `listing/validate.ts`, `listing/inventory.ts` | still reference `createOrder` | rewire to createPurchaseOrder terminology |

## Read-only consumers (no change to authority, verify field names)
publicMarketplace.ts, fulfillment.ts (isAvailable on delivery listings), sharePreview.ts, commerce.ts price reads.

## Not yet audited
Android (FirebaseCommerceRepository, feature/shop screens), hosting/app.js, Admin.
These must call callables only; no direct Firestore listing writes. Needs a Firestore rules check too.

## Exit criteria for Block 1
1. Every lifecycle op (create, update, publish, pause, restock, reserve, release, commit, delete, transfer) goes through listing/*.
2. `grep` for listing-field writes outside listing/ returns 0 (excluding tests).
3. Unit tests per op, plus adversarial: forged sellerId/price/status, negative stock, reservation race, unauthorized edit.
4. Firestore rules deny direct client writes to stock/reserved/status/seller/price fields.

## Correction (after reading the code in full)
The original table above overstated the gap. `createListing`, `updateListing`, `deleteListing` and
`restockListing` in commerce.ts already call the engine for validation, slugs, counters, activity and
restock. Remaining real gaps:
- reservations.ts released stock by hand and never re-derived status. FIXED in block-1-listing-engine
  (now uses engine `releaseInventory`; unit test added).
- createListing still assembles the new listing document inline (initial stock/status/counters). TODO: move to an engine builder.
- updateListing still maps the legacy `isAvailable` flag to PAUSED/ACTIVE inline. TODO: engine transition helper, then retire the flag from clients.
- Stale `createOrder` wording in engine comments: FIXED.
- Emulator-based test suites (commerce, logistics, notifications, security-rules) could not run in the
  review sandbox (Firestore emulator download blocked). They must be run locally or in CI before merge.

## Firestore rules (block-1b-firestore-rules)
- VULNERABILITY: `listings` update allowed the seller to write any field not on a denylist, so `status`, `reservedQuantity`,
  `inventoryMode`, `isAvailable`, `shareSlug`, `slugAliases` were client-writable (e.g. un-suspend a moderated listing, switch to
  unlimited stock, hijack another product's slug). FIXED: `allow update: if false` (all updates via callables). No Android, web or
  admin code writes listings directly (searched `data/`, `hosting/`, `web/`).
- Test added to `security-rules.test.ts` (needs the emulator; not run in authoring sandbox).
- DEPLOY NOTE: rules deploy independently of functions; deploy only after the callables are live (already true on this branch).
- Separate pre-existing issue: `FirebaseCommerceRepository.updateShop` writes `shops` directly (line ~55) but rules deny shop
  updates, so that call fails. Needs an `updateShop` callable (not yet written).
