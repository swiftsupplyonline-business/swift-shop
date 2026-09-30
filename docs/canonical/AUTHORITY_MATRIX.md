# Swift Canonical Authority Matrix

**Status:** Living Block 0 migration artifact.  
**Master:** `docs/canonical/CANONICAL_PLATFORM_MIGRATION.md`

| Domain | Canonical authority | Current legacy/duplicate risk | Target action | Block |
|---|---|---|---|---|
| Listing validation | Listing Engine | `commerce.ts` | Delegate to engine | 1 |
| Listing ownership | Listing Engine | commerce/client paths | Consolidate | 1 |
| Listing lifecycle | Listing Engine | inline commerce mutations | Consolidate | 1 |
| Listing inventory | Listing Engine | reservations/commerce/client paths | Consolidate | 1 |
| Listing slug/activity | Listing Engine | legacy helpers | Consolidate | 1 |
| Purchase totals | `calculatePurchaseTotal` | `calculateOrderFees` | Migrate consumers, delete legacy | 2 |
| Purchase creation | `createPurchaseOrder` | `createOrder` | Migrate consumers, delete legacy | 2 |
| Order | Purchase-first order architecture | legacy order paths | Make Order the commercial commitment | 3 |
| Inventory lifecycle | Listing Engine | scattered reservation paths | Reserve/release/commit through engine | 3 |
| Delivery Request | Fulfillment | `logistics.ts` | Migrate then remove duplicate authority | 4 |
| Fulfillment Job | Fulfillment | `logistics.ts` | Migrate then remove duplicate authority | 4 |
| Tracking | Fulfillment | legacy tracking paths | Consolidate | 4 |
| Fulfillment statuses | Fulfillment state machine | `PICKUP` and other drift | Normalize to canonical vocabulary | 4 |
| Product smart link | `/s/` | `/listing/`, `/shop/` | Compatibility then remove legacy | 6 |
| Delivery smart link | `/d/` | legacy delivery routes | Canonicalize | 6 |
| Android commerce | Canonical callable APIs | legacy repository/use-case paths | Migrate | 5 |
| Web commerce | Canonical callable APIs | legacy `hosting/app.js` calls | Migrate | 6 |
| Admin | Canonical APIs | direct/duplicate mutations | Migrate | 7 |
| Identity | Canonical user/profile | scattered identity logic | Consolidate | 8–10 |
| Notifications | Canonical events | inferred business state | Event-driven integration | 8 |
| Social | Canonical social graph | duplicate/TBD paths | Audit and consolidate | 8 |
| Messaging | Canonical messaging | duplicate/TBD paths | Audit and consolidate | 8 |
| Advertising | Canonical advertising engine | scattered campaign logic | Consolidate | 9 |
| Entitlements | Canonical entitlement authority | scattered flags | Consolidate | 9 |
| Profile upgrades | Entitlements | direct feature flags | Route through entitlement authority | 9 |
| Search/discovery | Canonical discovery contracts | client-specific behavior | Cross-platform integration | 10 |
| Security rules | Canonical ownership/authority model | direct client writes | Verify and harden | 10 |
| Legacy architecture | None; migration-only | `delivery-first-checkout` | Freeze → migrate → remove | 11 |

## Baseline evidence

The current Block 0 audit tooling reports known legacy references rather than claiming zero. Its current baseline must be treated as evidence to drive migration, not as a completion claim.

**Completion target:** zero legacy references, or explicitly documented compatibility exceptions.
