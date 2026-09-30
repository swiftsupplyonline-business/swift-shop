# SWIFT — CANONICAL PLATFORM MIGRATION

**Status:** MASTER ARCHITECTURE & MIGRATION CONTRACT  
**Platform:** Swift Marketplace  
**Repository:** `swiftsupplyonline-business/swift-shop`  
**Legacy architecture:** `delivery-first-checkout`  
**Target:** Canonical Platform Architecture

## 1. Purpose

Swift must converge from the legacy delivery-first architecture to one canonical platform.

The migration objective is:

**Build canonical → migrate every consumer → verify the ecosystem → eliminate legacy → make canonical the new main platform.**

This document governs architecture, implementation, verification, agent work, branch strategy, and legacy elimination.

Major migration work is not complete when code merely compiles. Completion requires architectural correctness, consumer migration, integration verification, authorization/security verification, state-machine verification, adversarial testing, and diff review.

## 2. North Star

### Platform domains

- **Identity:** Users, Profiles, Roles, Entitlements, Profile Upgrades, Messaging Identity, Notification Identity.
- **Discovery:** Feed, Search, Social, Smart Links, Recommendations, Maps, AI Discovery.
- **Commerce:** Shop, Listing, Listing Engine, Purchase, Payment, Inventory, Order, Settlement.
- **Fulfillment:** Delivery Listing, Delivery Options, Delivery Request, Provider, Fulfillment Job, Driver, Route, Tracking, Delivery Completion.

### Principal customer journey

```
Profile → Shop → Listing → Listing Engine → Smart Link
→ Web/Android → Purchase Checkout → Purchase Order
→ Payment → Seller Preparation → Delivery Request
→ Provider → Driver → Tracking → Delivered
→ Settlement / Completion
```

## 3. Non-negotiable architectural principles

### 3.1 Purchase is not Delivery

```
PURCHASE → ORDER
ORDER → DELIVERY REQUEST → FULFILLMENT
```

Delivery must never become a hidden part of purchase creation.

### 3.2 Listing is not Order

```
LISTING → PURCHASE → ORDER
```

Viewing or selecting a listing never creates an order.

### 3.3 Delivery Listing is not Fulfillment Job

```
DELIVERY LISTING → DELIVERY REQUEST → FULFILLMENT JOB → DRIVER → TRACKING
```

### 3.4 Delivery Request is not Fulfillment

A buyer request becomes an operational fulfillment only after provider acceptance and canonical job creation.

### 3.5 One authoritative backend per domain

Android, Web, and Admin are consumers of canonical backend authorities. They must not create competing mutation authorities.

```
Android ─┐
Web ─────┼→ Canonical Domain Engine → Firestore
Admin ───┘
```

## 4. Canonical entity relationship

```
USER → PROFILE → SHOP → LISTING → SMART LINK
→ PURCHASE → ORDER
              ├→ PAYMENT
              ├→ INVENTORY
              └→ FULFILLMENT
                    ├→ DELIVERY REQUEST
                    ├→ PROVIDER
                    ├→ DRIVER
                    └→ DELIVERY ROUTE
```

Order is the bridge between commercial commitment and fulfillment and references fulfillment explicitly.

## 5. Migration blocks

| Block | Scope | Exit condition |
|---|---|---|
| 0 | Canonical baseline + contract | Authority and contracts documented |
| 1 | Listing Engine | Listing/inventory authority consolidated |
| 2 | Purchase / Commerce | Purchase-first authority live |
| 3 | Order + Inventory | Order is commercial bridge |
| 4 | Fulfillment / Delivery | One delivery state machine |
| 5 | Android | Kotlin consumes canonical contracts |
| 6 | Web / Smart Links | `/s/` and `/d/` canonical |
| 7 | Admin | Admin consumes canonical APIs |
| 8 | Social / Messaging / Notifications | Canonical identity/events |
| 9 | Advertising / Entitlements / Profile upgrades | Canonical authorization |
| 10 | Cross-platform integration | Same entity semantics everywhere |
| 11 | Legacy elimination | Consumers and legacy code removed |
| 12 | Canonical-main verification | Canonical branch becomes main only after verification |

Blocks normally execute sequentially. Later blocks must not silently redefine earlier canonical contracts.

## 6. Block 0 — Canonical baseline

Required artifacts:

- `docs/canonical/CANONICAL_PLATFORM_MIGRATION.md`
- `docs/canonical/CANONICAL_PLATFORM_CONTRACT.md`
- `docs/canonical/AUTHORITY_MATRIX.md`
- canonical audit tooling

The contract must define canonical entities, functions, Firestore structures, statuses, ownership, state transitions, client contracts, smart-link routes, payment boundaries, and delivery boundaries.

## 7. Block 1 — Listing Engine

**Authority:** `functions/src/listing/*`

Expected components:

```
activity.ts
index.ts
inventory.ts
lifecycle.ts
ownership.ts
slug.ts
types.ts
validate.ts
```

The engine owns:

- validation
- ownership
- lifecycle
- inventory
- reservation/release/commit
- restock
- slugs
- activity
- server-owned fields

Inventory:

```
availableQuantity = stockQuantity - reservedQuantity
```

Canonical operations:

- `reserveInventory()`
- `releaseInventory()`
- `commitInventory()`
- `restockInventory()`

Canonical listing lifecycle:

`DRAFT → ACTIVE → PAUSED → OUT_OF_STOCK → ARCHIVED → SUSPENDED → DELETED`

`OUT_OF_STOCK` is derived from inventory conditions; clients cannot arbitrarily set it.

Server-owned fields include `sellerId`, `status`, `shareSlug`, `commitmentCount`, `viewCount`, `shareCount`, and `publishedAt`.

### Block 1 acceptance

- All listing mutations route through the engine.
- Inventory mutations route through the engine.
- Unauthorized seller mutations fail.
- Protected fields cannot be forged.
- Lifecycle transitions are validated.
- Inventory cannot become negative.
- Reservation races are safe.
- Consumers use the canonical Listing contract.

## 8. Block 2 — Purchase / Commerce

Canonical functions:

- `calculatePurchaseTotal`
- `createPurchaseOrder`

Legacy functions:

- `calculateOrderFees`
- `createOrder`

Canonical flow:

```
Listing
→ validate purchasability
→ calculate purchase total
→ reserve inventory
→ create purchase order
→ payment
→ order
```

Server authority owns final price, seller, availability, reservation, payment amount, and authoritative order state.

Acceptance includes duplicate-purchase safety, concurrent oversell prevention, price/seller manipulation rejection, payment-failure rollback, and cancellation release.

## 9. Block 3 — Order + Inventory

Order owns the commercial commitment and references:

- buyer
- seller
- purchased listing
- quantity
- authoritative amount
- payment state
- inventory state
- preparation state
- fulfillment reference

Inventory:

```
AVAILABLE → RESERVED → COMMITTED
CANCEL → RESERVED → RELEASED
```

State transitions must be idempotent or safely rejected.

## 10. Block 4 — Fulfillment / Delivery

Canonical components:

- Delivery Listing
- Delivery Options
- Delivery Request
- Provider
- Fulfillment Job
- Driver
- Route
- Tracking
- Delivery Completion

Canonical flow:

```
Order
→ Delivery Options
→ Buyer selects delivery
→ Delivery Request
→ Provider accepts
→ Fulfillment Job
→ Driver authorization
→ Pickup
→ Transit
→ Delivered
```

Canonical fulfillment states:

`REQUESTED → ASSIGNED → AT_PICKUP → PICKUP_CONFIRMED → IN_TRANSIT → DELIVERED`

Terminal/exception states include `CANCELLED` and `FAILED` as explicitly defined by the fulfillment contract.

**`PICKUP` is not a canonical state.** Any existing `PICKUP` usage must be migrated to the agreed canonical vocabulary.

Cross-shop delivery is permitted by the platform model where applicable: the delivery provider/service does not have to belong to the product seller's shop.

Delivery fees, payment responsibility, provider earnings, driver earnings, cancellation, refund, failure, and settlement must remain explicit financial boundaries.

## 11. Block 5 — Android

Target:

```
UI → ViewModel → Use Case → Repository → Callable
→ Canonical Function → Firestore
```

Android must migrate listing, inventory, purchase, checkout, orders, cancellation, delivery requests, provider/driver operations, tracking, notifications, and smart links.

Android must not reproduce backend authority locally or invent its own commerce/delivery state machine.

## 12. Block 6 — Web / Smart Links

Canonical product route:

`/s/<shop-slug>/<product-slug>`

Canonical delivery route:

`/d/<shop-slug>/<delivery-slug>`

Legacy routes such as `/listing/**` and `/shop/**` are compatibility-only during migration and must not receive new architecture.

Web purchase:

```
Smart Link → Listing → Purchase UI → Canonical Purchase API → Order
```

Web delivery:

```
Delivery Link → Delivery Listing → Options → Delivery Request → Fulfillment
```

Active Web consumers must stop using `calculateOrderFees` and `createOrder` once the canonical purchase contract is available.

## 13. Block 7 — Admin

Admin is a client, not a second backend.

Responsibilities include moderation, users, shops, listings, order oversight, fulfillment oversight, advertising, entitlements, and platform monitoring.

Administrative operations must still respect domain authority.

## 14. Block 8 — Social / Messaging / Notifications

Social:

```
User → Profile → Social Graph → Posts → Feed
```

Messaging:

```
User → Conversation → Message → Notification
```

Notifications consume canonical events rather than independently inferring business state.

Important canonical events include Purchase Created, Payment Confirmed, Order Prepared, Delivery Requested, Delivery Accepted, Driver Assigned, Pickup Confirmed, Delivery Started, Delivery Completed, and Order Cancelled.

## 15. Block 9 — Advertising / Entitlements / Profile Upgrades

Advertising must explicitly model advertiser, campaign, creative, placement, targeting, budget, spend, reporting, and status.

Entitlements are the authorization source for feature access. Profile upgrades must update entitlement authority rather than creating scattered feature flags.

## 16. Block 10 — Cross-platform integration

```
             CANONICAL BACKEND
              /      |      \
          Android   Web     Admin
```

The same entity must mean the same thing across every consumer:

`Listing(Android) = Listing(Web) = Listing(Admin) = Listing(Functions)`

## 17. Six critical end-to-end flows

Every migration must eventually verify:

1. **Listing:** Profile → Shop → Listing Engine → Validation → Inventory → Lifecycle → Slug → Smart Link.
2. **Product Purchase:** Smart Link → Listing → UI → Selection → `calculatePurchaseTotal` → `createPurchaseOrder` → reservation → payment → Order.
3. **Order:** Purchase → Order → reservation → payment → preparation → fulfillment-ready.
4. **Delivery:** Order → options → request → provider → accept → fulfillment job.
5. **Tracking:** Fulfillment → driver authorization → pickup → confirmation → transit → delivered → settlement.
6. **Web / Smart Links:** `/s/` product flow and `/d/` delivery flow, including malformed/nonexistent/archived/unavailable entities and wrong route types.

Every component is classified:

**AUTHORITATIVE · DUPLICATE · ORPHANED · BROKEN · MISSING · LEGACY**

## 18. Authority matrix policy

Each domain has exactly one canonical mutation authority. Consumers are adapters.

Key target authorities:

| Domain | Canonical authority | Legacy examples |
|---|---|---|
| Listing | Listing Engine | commerce.ts mutations |
| Inventory | Listing Engine | legacy inventory/reservation paths |
| Purchase total | `calculatePurchaseTotal` | `calculateOrderFees` |
| Purchase creation | `createPurchaseOrder` | `createOrder` |
| Order | Purchase-first order architecture | legacy order paths |
| Delivery request | Fulfillment | legacy logistics |
| Fulfillment/tracking | Fulfillment | legacy logistics |
| Product links | `/s/` | `/listing/` |
| Delivery links | `/d/` | legacy delivery routes |
| Notifications | Canonical events | inferred/duplicate event paths |
| Identity | Canonical user/profile | duplicate identity logic |
| Entitlements | Canonical entitlement authority | scattered flags |

The matrix is a living migration artifact and must be updated with evidence.

## 19. Legacy policy and elimination

`delivery-first-checkout` is **FROZEN / MIGRATION-ONLY**.

No new product features should be implemented specifically for it. Only security fixes, critical migration fixes, temporary compatibility, and migration evidence are allowed.

Legacy deletion sequence:

```
FREEZE → MAP → MIGRATE → VERIFY → REMOVE CONSUMERS
→ DELETE LEGACY → VERIFY ZERO REFERENCES
```

Do not delete legacy implementation before every consumer is migrated.

Forbidden post-migration references include `createOrder(`, `calculateOrderFees(`, legacy delivery APIs/statuses, legacy listing mutations, legacy checkout screens/models, and obsolete smart-link routes unless an exception is explicitly documented.

## 20. Automated regression detection

CI should fail when forbidden legacy references are introduced.

The target is:

**Legacy References = 0**

or:

**Legacy References = explicitly approved compatibility exceptions**

The canonical audit script is evidence, not a substitute for architectural review.

## 21. Adversarial verification

### Commerce
Duplicate purchase, double payment, insufficient wallet, payment failure, reservation expiry, cancellation, concurrent purchase, stock exhaustion, price/quantity/seller manipulation.

### Listing
Unauthorized edit/delete, seller transfer, forged seller/price/status, negative inventory, reservation race, invalid lifecycle, invalid stock transition.

### Delivery
Request before valid purchase, wrong buyer, unauthorized provider/driver, unavailable/expired request, duplicate request/fulfillment, invalid transition, cancellation, failed delivery.

### Web
Malformed links, nonexistent/deleted/archived/unavailable listing, invalid slugs, wrong entity type, forged purchase data, backend mismatch.

## 22. AI-agent workflow

Agents are implementation tools, not architectural authorities.

Required workflow:

```
INSPECT → PROPOSE → REVIEW → IMPLEMENT → BUILD
→ VERIFY → ADVERSARIAL VERIFY → DIFF AUDIT → ACCEPT
```

Every major task must specify:

1. target branch
2. target block
3. objective
4. scope
5. canonical contract
6. required changes
7. forbidden changes
8. acceptance criteria
9. tests
10. build
11. adversarial tests
12. evidence
13. commit

Agent claims such as “100% complete” are never acceptance evidence.

## 23. Diff-first review

No major implementation is accepted from an agent description alone.

Review:

- `git diff`
- `git diff --stat`
- relevant tests
- build output
- adversarial evidence
- remaining references

Acceptance is based on repository evidence.

## 24. Branch strategy

Current repository default is still the legacy `delivery-first-checkout`.

Canonical work proceeds on canonical branches, currently including:

- `canonical-platform-integration-2026-09-29`
- `block-0-canonical-baseline`

Do not replace `main`/default prematurely. The canonical branch becomes the new main only after Blocks 0–12 and the final verification threshold are satisfied.

## 25. Definition of Done

The migration is complete only when:

- canonical domain boundaries and authorities are explicit;
- Listing Engine owns listing/inventory/lifecycle/ownership;
- purchase-first commerce is authoritative;
- legacy order creation and fee calculation are removed from active consumers;
- Order bridges commerce to fulfillment explicitly;
- reservation/commit/release lifecycle is correct;
- Delivery Request, Delivery Listing, and Fulfillment Job remain distinct;
- provider, driver, tracking, and settlement boundaries are explicit and tested;
- Android/Web/Admin consume canonical contracts;
- `/s/` and `/d/` are canonical smart-link routes;
- social, messaging, notifications, advertising, entitlements, profile upgrades, search/discovery are integrated;
- authorization, ownership, callable authentication, rules, and forged-field rejection are verified;
- unit, integration, state-machine, security, adversarial, and cross-platform tests pass;
- legacy consumers/functions/models/screens/routes are removed where no longer required;
- legacy references are zero or explicitly approved;
- the canonical branch is verified before becoming main.

## 26. Governing rule

**Swift must converge toward one canonical platform, not evolve into two competing platforms.**

Every implementation decision must answer:

1. Is this canonical?
2. Is this migrating toward canonical?
3. Is it temporarily required for controlled migration?

If none apply, it must not be added.

## 27. Immediate priority

The immediate priority is:

```
CANONICAL INTEGRATION AUDIT
→ BLOCK 0
→ BLOCK 1
→ BLOCK 2
→ BLOCK 3
→ BLOCK 4
```

The first complete audit traces Listing, Product Purchase, Order, Delivery, Tracking, and Web/Smart Links, classifying every component as authoritative, duplicate, orphaned, broken, missing, or legacy.

## 28. Master success condition

The repository must ultimately be truthfully described as:

> **One Swift platform, one canonical architecture, one authoritative backend contract per domain, one purchase model, one order model, one fulfillment model, shared Android/Web/Admin consumers, verified smart links, verified security, verified state machines, and no uncontrolled legacy architecture remaining.**

**Canonical Platform → Fully Integrated → Fully Verified → Legacy Eliminated → New Main**
