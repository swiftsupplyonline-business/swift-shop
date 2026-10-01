# Authority Matrix (baseline from scripts/canonical-audit.sh)

| Domain | Canonical authority | Legacy / duplicate | Action | Block |
|---|---|---|---|---|
| Listing validation/inventory/lifecycle | Listing Engine (functions/src/listing/*) | commerce.ts, reservations.ts, publicMarketplace.ts, Android FirebaseCommerceRepository, hosting/app.js | Consolidate into engine; remove other writers | Block 1 |
| Purchase total | calculatePurchaseTotal | calculateOrderFees (13 refs) | Replace all consumers, then delete | Block 2 |
| Purchase creation | createPurchaseOrder | createOrder (12 refs) | Replace all consumers, then delete | Block 2 |
| Delivery request | createDeliveryRequest (fulfillment.ts) | logistics.ts | Migrate then delete logistics.ts | Block 4 |
| Fulfillment job / tracking | createDeliveryJob (fulfillment.ts) | logistics.ts | Migrate then delete | Block 4 |
| Fulfillment statuses | Canonical state machine | PICKUP (3 refs) | Rename to AT_PICKUP/PICKUP_CONFIRMED | Block 4 |
| Product link | /s/ | /listing/ (4 refs) | Redirect, then remove | Block 6 |
| Delivery link | /d/ | legacy delivery URL | Canonical | Block 6 |
| Web checkout | purchase-first | hosting/app.js calls both | Remove legacy calls | Block 6 |
| Android checkout | purchase-first | Commerce repos/use cases call calculateOrderFees | Remove legacy calls | Block 5 |

Baseline legacy reference count: 33. Completion = 0 (run `scripts/canonical-audit.sh --strict`).
