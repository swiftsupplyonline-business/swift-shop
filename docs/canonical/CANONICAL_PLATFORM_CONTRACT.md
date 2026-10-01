# Canonical Platform Contract

Status: authoritative. `delivery-first-checkout` is FROZEN (migration-only, no new features).
This document is the instruction manual for every human and AI agent working in this repo.

## Core rule
Purchase and Delivery are separate domains. Delivery is a fulfillment service attached to an
existing, paid Order. It never defines the purchase.

Order != Delivery Request; Delivery Request != Fulfillment Job;
Fulfillment Job != Delivery Listing; Listing != Order.

## Domains and authorities
- Listing: identity, ownership, lifecycle, inventory, pricing, slug, activity.
  Sole authority: `functions/src/listing/*` (Listing Engine). No other module may write
  `stockQuantity`, `reservedQuantity`, `isAvailable`, `status`, `sellerId`, `shopId`, `priceMinorUnits`.
- Purchase: cart items, pricing snapshot, reservation, payment, order creation.
  Sole authority: `calculatePurchaseTotal` -> `createPurchaseOrder` -> reserve -> pay -> commit/release.
- Order: buyer, seller, listing snapshots, payment, reservation, `fulfillmentId` reference only.
- Fulfillment: `createDeliveryRequest`, provider accept, `createDeliveryJob`, driver, route, tracking.
  Sole authority: `functions/src/fulfillment.ts`.
- Links: `/s/<shop>/<product>` product; `/d/<shop>/<delivery>` delivery. `/listing/` is legacy.

## Canonical fulfillment state machine
REQUESTED -> (CANCELLED) | ASSIGNED -> AT_PICKUP -> PICKUP_CONFIRMED -> IN_TRANSIT -> (FAILED) | DELIVERED
Every surface (Functions, Android, Web, Admin, notifications, tracking, tests) uses exactly these
strings. `PICKUP` is invalid.

## Clients
Android, Web and Admin are clients of the same callable contracts. No client-side commerce logic.
Android: UI -> ViewModel -> UseCase -> Repository -> Callable -> Canonical Function.

## Migration blocks
0 baseline+contract, 1 Listing Engine, 2 Purchase/Commerce, 3 Order+Inventory, 4 Fulfillment,
5 Android, 6 Web/Smart links, 7 Admin, 8 Social/Messaging/Notifications,
9 Advertising/Entitlements/Profile upgrades, 10 Cross-platform, 11 Legacy elimination, 12 Canonical-main verification.
Each block: AUDIT, IMPLEMENT, BUILD, UNIT, INTEGRATION, ADVERSARIAL, DIFF AUDIT, ACCEPT.


## Delivery financial boundary
A delivery listing price is snapshotted as `deliveryFeeMinorUnits` on the delivery request and fulfillment route after the purchase is paid. The current purchase escrow total is created before delivery is requested and therefore does not include this later fee. The repository currently does not establish a separate authoritative collection, escrow, provider payout, driver payout, or delivery platform-fee contract for that amount. Until Finance/Product defines one, code must treat the fee as recorded/snapshotted data only and must not represent it as collected or settled. No delivery commission or platform-fee percentage is implied by this contract.
