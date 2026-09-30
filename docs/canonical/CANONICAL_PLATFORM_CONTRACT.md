# Swift Canonical Platform Contract

**Status:** Authoritative Block 0 contract.  
**Master document:** `docs/canonical/CANONICAL_PLATFORM_MIGRATION.md`  
**Legacy:** `delivery-first-checkout` — FROZEN / migration-only.

## Governing rules

1. Swift converges toward one canonical platform.
2. Purchase and Delivery are separate domains.
3. Listing, Order, Delivery Listing, Delivery Request, and Fulfillment Job are distinct entities.
4. Each major domain has one authoritative backend mutation path.
5. Android, Web, and Admin are consumers, not competing authorities.
6. No major migration is accepted from compilation or agent claims alone; repository diff and evidence are required.

## Canonical authorities

| Domain | Authority |
|---|---|
| Listing validation/ownership/lifecycle/inventory/slugs/activity | `functions/src/listing/*` |
| Purchase totals | `calculatePurchaseTotal` |
| Purchase creation | `createPurchaseOrder` |
| Order | Purchase-first Order architecture |
| Delivery Request | Fulfillment authority |
| Fulfillment / tracking | `functions/src/fulfillment.ts` |
| Product smart links | `/s/<shop-slug>/<product-slug>` |
| Delivery smart links | `/d/<shop-slug>/<delivery-slug>` |
| Identity | Canonical user/profile authority |
| Notifications | Canonical platform events |
| Entitlements | Canonical entitlement authority |

## Canonical fulfillment states

`REQUESTED → ASSIGNED → AT_PICKUP → PICKUP_CONFIRMED → IN_TRANSIT → DELIVERED`

Exception/terminal states are defined by the fulfillment implementation contract, including `CANCELLED` and `FAILED`.

**Invalid legacy vocabulary:** `PICKUP`.

## Canonical inventory

`availableQuantity = stockQuantity - reservedQuantity`

Lifecycle:

`AVAILABLE → RESERVED → COMMITTED`

Cancellation:

`RESERVED → RELEASED`

## Server-owned listing fields

Clients cannot authoritatively set:

`sellerId`, `status`, `shareSlug`, `commitmentCount`, `viewCount`, `shareCount`, `publishedAt`, or inventory authority fields.

## Client boundary

```
Android/Web/Admin
       ↓
Canonical client contract
       ↓
Callable / canonical function
       ↓
Domain authority
       ↓
Firestore
```

Clients must not recreate authoritative pricing, inventory, seller identity, order state, or delivery state machines.

## Migration acceptance

Each block requires audit → implementation → build → tests → adversarial verification → diff audit → acceptance.

See `CANONICAL_PLATFORM_MIGRATION.md` for the complete block roadmap and Definition of Done.
