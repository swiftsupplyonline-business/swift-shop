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

## Second audit (same baseline 33d711b) — checked against current code
| Finding | Status |
|---|---|
| Delivery vocabulary: `PICKUP` still in transition table, auth check and Android `FirebaseDeliveryRepository` | STALE. No `PICKUP` remains anywhere in the tree (grep over .kt/.ts/.js/.html/.rules); a unit test asserts it is not a valid status. |
| `createDeliveryJob` reads outside the transaction | REAL, fixed (batch A2): all reads go through the transaction, job id is deterministic (`job_<orderId>`) so concurrent calls cannot create two, jobs created earlier under random ids are still found, and buyer/active-order checks added. Unit-tested incl. read-after-write enforcement. |
| Web `/s/...` product links intercepted by the JS router -> "Page not found" | REAL, fixed: links to `/s/`, `/d/`, `/pay/`, `/api/` are real browser navigations; modified/new-tab clicks are no longer hijacked either. Not run in a browser; regex checked in node. |
| Android App Links hard-coded to the DEV host | REAL, fixed: host comes from a per-flavor manifest placeholder (dev / staging / production `*.web.app`). Not built here. |
| Production App Links verification | NOT DONE. `hosting/.well-known/assetlinks.json` only lists the dev debug package + debug cert. Needs the staging/production package names and SHA-256 of the real release signing key. |
| `docs/canonical/CANONICAL_PLATFORM_*.md`, `AUTHORITY_MATRIX.md` | Still missing, and `PROJECT_STATE.md` is stale. I have not drafted them: they should be written from the decisions you have made, not reverse-engineered by me. |
| Functions tests 6/14 failing; Android JUnit; exports; CI triggers; confirmDelivery | See the table above (batch A / Block 9). |
| Web MoPay "coming soon" | Product decision: Wallet-only web, or build MoPay on web? |

## Batch B — delivery-fee escrow (purchase-first flow)
Implements the decided economics, reusing wallets + ledgerEntries (no new payment architecture). `functions/src/deliveryFee.ts`.

| Event | Money movement | `feePaymentStatus` |
|---|---|---|
| `createDeliveryRequest` | buyer wallet -> `system_delivery_escrow` (fails if balance too low; one active request per order) | ESCROWED |
| provider declines / request expires (scheduler) / buyer cancels (PENDING, or ACCEPTED with no job yet) | escrow -> buyer wallet | REFUNDED |
| delivery job FAILED or CANCELLED (`updateDeliveryStatus`) | escrow -> buyer wallet | REFUNDED |
| order cancelled (`cancelOrder`) | escrow -> buyer wallet (together with the product refund, one wallet write); request + open job cancelled | REFUNDED |
| buyer confirms a DELIVERED order (`confirmDelivery`) | escrow -> delivery listing's author (`merchantId`), same transaction as seller settlement | RELEASED |

Rules: the fee is released only after the buyer confirms and only if the delivery is DELIVERED (confirming earlier is refused with
"cancel the delivery request first"); every refund/release checks the escrowed state first so repeats and races cannot pay twice;
the expiry job now runs one transaction per request so the refund is atomic with the status change.

Not covered: MoPay for the fee (wallet only), no auto-settlement if the buyer never confirms delivery (the fee stays escrowed
until they confirm or an admin acts), no emulator run. Android: `createDeliveryRequest` can now fail with an insufficient-balance
message; the app shows the error string, but there is no "top up" prompt. Route docs now carry `requestId`.
