# Canonical Legacy Elimination Migration

## Purpose
This file records the controlled removal of the legacy commerce and logistics authorities from `canonical-platform-integration-2026-09-29`. It is a migration record, not a second architecture.

## Starting architecture
The branch contained canonical purchase/fulfillment callables alongside legacy `calculateOrderFees`, `createOrder`, `requestDelivery`, and `functions/src/logistics.ts`. Android also retained legacy commerce repository/use-case methods.

## Canonical architecture
Purchase: `calculatePurchaseTotal` -> `createPurchaseOrder` -> reserve -> payment -> commit/release.
Delivery: `createDeliveryRequest` -> provider response -> `createDeliveryJob` -> `updateDeliveryStatus`, all in `functions/src/fulfillment.ts`, after the purchase is paid/confirmed.

## Migration blocks
1. Baseline and contract
2. Listing Engine
3. Purchase/Commerce
4. Fulfillment
5. Android
6. Web/Smart Links
7. Admin
8. Legacy elimination and strict verification

## Deletion gates
Legacy authorities are deleted only after client/repository consumers and Firebase exports are migrated. Compatibility aliases are forbidden.

## Final verification gates
Repository-wide legacy search, Firebase export audit, canonical authority presence, Functions build/tests, Android unit tests, web validation, and an independent final audit are required before promotion.

## Branch/main transition
Do not promote to `main` until the independent final audit passes and all identified finance/settlement blockers are resolved separately. The frozen `delivery-first-checkout` branch is not deleted by this migration.

## Known finance blocker
The canonical post-purchase delivery flow snapshots `deliveryFeeMinorUnits` on `deliveryRequests`/`deliveryRoutes`, while `createPurchaseOrder` creates the purchase before delivery and does not include that later fee in `totalMinorUnits` or escrow. The current code therefore records/snapshots the delivery fee but does not establish that the fee was collected or settled to the provider. No delivery platform fee, provider commission, driver commission, or settlement percentage is defined by this migration. The discrepancy must be resolved by the finance/product authority before go-live.


## canonical-reconcile-d (reconciliation of the money/escrow work onto the canonical tree)
Base 5f810580 + 3501b7e (Android build blockers). Brought in from go-live-remediation-c: Block 8 (money validation, idempotency
namespacing, campaign budget guard, rules hardening), Block 9 (order payment/cancel/late-payment refund, order transitions,
withdrawal rejection) and the delivery fee escrow. `createDeliveryJob` is now fully transactional and keeps the canonical
job id (`deliveryRoutes/{orderId}`) plus a lookup for jobs stored under earlier random ids.
