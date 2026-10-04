# Block 4 Audit: Fulfillment

## Fixed in this branch (block-4-fulfillment)
- BUG: canonical `fulfillment.ts` `updateDeliveryStatus` used a `PICKUP` status that its own validity list
  rejected, so no delivery could ever move past ASSIGNED. Now uses the canonical machine in
  `functions/src/fulfillmentStates.ts` (REQUESTED > ASSIGNED > AT_PICKUP > PICKUP_CONFIRMED > IN_TRANSIT > DELIVERED; FAILED/CANCELLED).
- PICKUP_CONFIRMED can be confirmed by the assigned driver or the merchandise seller (parity with legacy RED-4 rule).
- Order lifecycle parity: IN_TRANSIT sets order DISPATCHED, DELIVERED sets order DELIVERED (the buyer confirm-delivery
  screen and `confirmDelivery` depend on these), in addition to `fulfillmentStatus`.
- Android: driver's active-deliveries query listed the invalid "PICKUP"; now AT_PICKUP and PICKUP_CONFIRMED.
- Unit tests for the state machine (no emulator needed).

## Still duplicated: logistics.ts (legacy) vs fulfillment.ts (canonical)
| Legacy (logistics.ts) | Canonical | Blocker to removal |
|---|---|---|
| `requestDelivery` (exported in index.ts) | `createDeliveryRequest` -> `acceptDeliveryRequest` -> `createDeliveryJob` | Android `FirebaseDeliveryRepository.requestDelivery` + its ViewModel still call it. Migrate Android to the canonical 3-step flow first. |
| `expireDeliveryRequests` (scheduled, exported) | none yet | Must be ported to fulfillment.ts (canonical requests carry a 120s window; nothing expires PENDING ones otherwise). |
| `onOrderConfirmed` trigger, `respondToDeliveryRequest`, legacy `updateDeliveryStatus`/`createDeliveryRequest`/`cancelDeliveryRequest`/`authorizeDriver` | canonical equivalents | Not exported from index.ts (dead). `test/logistics.test.ts` imports them directly: port those tests to fulfillment.ts (error text differs: "not an authorized driver" vs "Driver is not authorized by this provider"). |

## Removal order
1. Port `expireDeliveryRequests` to fulfillment.ts. 2. Migrate Android `requestDelivery` call. 3. Port logistics tests.
4. Run emulator tests. 5. Delete logistics.ts and its index.ts export. 6. Audit script must show 0.
Emulator and Android builds could not run in the review sandbox.

## Update: expiry ported; Android contract mismatch found
- DONE: `expireDeliveryRequests` now lives in `fulfillment.ts` (batched, max 400 per run) and `index.ts` exports it from there.
  `logistics.ts` now only contributes `requestDelivery` to the deployed set.
- Android `RequestDeliveryUseCase` / `DeliveryRepository.requestDelivery` (legacy callable) are provided in `AppModule.kt`
  but no screen uses them: dead code, safe to delete once an Android build can verify it.
- CONTRACT MISMATCH (needs Android work, Block 5): the canonical backend `createDeliveryRequest` requires
  `{ orderId, listingId, dropoff:{lat,lng} }` and resolves pickup server-side from the paid order.
  Android `FirebaseDeliveryRepository.createDeliveryRequest` sends `{ listingId, pickup, dropoff }` with NO `orderId`,
  so the call is rejected ("orderId, listingId and dropoff are required"). `RequestDeliveryViewModel` also passes
  (listingId, dropoff, fallbackShopId) into a use case declared as (listingId, pickup, dropoff). The Android request flow
  must be rebuilt around an existing paid order: purchase -> order confirmed -> choose delivery (getDeliveryOptions) -> createDeliveryRequest(orderId, ...).
