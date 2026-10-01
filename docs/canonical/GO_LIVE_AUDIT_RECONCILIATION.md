# Go-live audit reconciliation (audit baseline 33d711b vs current block-9 branch)

The external audit was written against 33d711b. That commit is an ancestor of `block-9-order-money`, which has 19 further
commits. Each finding re-checked against current code:

| # | Audit finding | Status on current code |
|---|---|---|
| 1 | Android unit tests fail (junit unresolved in :core:model) | REAL. Fixed in batch A: 5 modules (core:model, core:media, domain:wallet, domain:feed, domain:commerce) had tests but no test deps. domain:commerce also needs mockito-kotlin (added to catalog, 5.4.0). NOT compiled here (no Android SDK / Gradle network). |
| 2 | Functions tests fail (6) | PARTLY. commerce/logistics/notifications suites need the Firestore emulator; cannot run here. Unit suites (51) pass. Old emulator suites still target `createOrder` behaviour and have not been re-run against Blocks 8-9. |
| 3 | confirmDelivery read-after-write | REAL, fixed in Block 9d (verified with a fake that enforces SDK ordering). |
| 4 | Fulfillment state machine uses PICKUP | STALE. `fulfillmentStates.ts` already has AT_PICKUP / PICKUP_CONFIRMED and `updateDeliveryStatus` uses it. |
| 5 | Delivery fee is never paid to the provider | REAL for the purchase-first flow. Block 9e pays the fee only for orders made by the legacy `createOrder` (fee snapshotted at checkout). `createPurchaseOrder` orders have no delivery fee, and `createDeliveryRequest` only records one. Needs a design (see below). |
| 6 | publicMarketplace / payPreview not exported | REAL. Fixed in batch A. |
| 7 | Notification functions not exported | REAL. Fixed in batch A. The app calls `updateFcmToken`, so push registration was failing in any deployed build. |
| 8 | No driver UI | Not verified here (Android UI). Backend path exists. |
| 9 | Web has no MoPay | Not verified; out of scope for backend batch. |
| 10 | CI does not cover canonical branches | REAL. Fixed in batch A (push: master, delivery-first-checkout, canonical-**, go-live-**; PRs to any base). |
| 11 | Deploy workflow deploys DEV only | REAL, not fixed. Needs your decision (below). |
| 12 | No signed production Android release pipeline | REAL, not fixed. Needs a keystore and Play setup from you. |
| 13 | State docs behind code | REAL. This folder (`docs/canonical`) now holds Blocks 8-9 notes; PROJECT_STATE.md still needs a rewrite. |

## Needs a decision before it can be built
- Delivery fee in the purchase-first flow: who pays and when? Proposed: buyer pays the fee from the Swift wallet when the
  delivery request is created (held in `system_order_escrow`-style delivery escrow), refunded if the request is declined,
  expires or is cancelled, and released to the delivery listing's author when the buyer confirms delivery. MoPay for the
  fee would be a second phase.
- Production deploy: manual approval + a separate workflow for `swift-d1baa`, using a service-account secret.
