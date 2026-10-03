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
SUPERSEDES the earlier text ("fee is snapshotted only, must not be represented as collected"). The delivery fee is now
collected and settled by the canonical Finance infrastructure: same Swift Wallet, same `ledgerEntries`; the only new thing is
the ledger account name `system_delivery_escrow`. Financial authority stays in Cloud Functions; Android and Web only call them.

| Event | Function | Money movement |
|---|---|---|
| Buyer requests delivery (paid purchase required) | `createDeliveryRequest` (one transaction) | fee read from the delivery listing (never the client); buyer wallet -> `system_delivery_escrow`; request `escrowStatus=HELD`; order locked to one active request |
| Provider declines | `declineDeliveryRequest` | full refund to buyer |
| Request expires | `expireDeliveryRequests` (one transaction per request) | full refund |
| Buyer cancels (before a job exists) | `cancelDeliveryRequest` | full refund |
| Job cancelled or FAILED | `updateDeliveryStatus` | full refund |
| Buyer confirms and job is DELIVERED | `confirmDelivery` | escrow -> platform fee, driver compensation, provider earnings (`planDeliveryPayout`) |
| Buyer confirms but the job never reached DELIVERED | `confirmDelivery` | escrow refunded to buyer |

Roles stay distinct: Product Seller != Delivery Provider != Driver (unless one account holds several roles).
Provider = author of the delivery listing (`providerId` / `merchantId`); driver = `driverId` on the job (may equal the provider).
When driver != provider the ledger records separate `DRIVER_COMPENSATION_*` and `PROVIDER_EARNINGS_*` lines.
`createDeliveryJob` refuses a request whose fee was not escrowed. Product settlement pays the seller the product subtotal only.

BUSINESS PARAMETERS AWAITING A FINANCE/PRODUCT DECISION (defaults chosen so no policy is invented):
1. Platform cut of the delivery fee: `DEFAULT_DELIVERY_PLATFORM_FEE_PER_MILLE` = 0 (no cut).
2. Driver share: optional `driverShareBps` on the delivery listing, default 0 (provider keeps the net fee).
3. Failed delivery: full refund to the buyer.
No percentage is implied by this contract. Changing any of these changes `deliveryEconomics.ts` constants only.
