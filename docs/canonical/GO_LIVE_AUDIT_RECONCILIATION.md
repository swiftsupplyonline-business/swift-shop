# Go-live audit reconciliation (branch go-live-remediation-c, base 032bda0)

Environment of the author of this document: Node/TypeScript only. No Android SDK, no Firestore emulator (download blocked),
no browser. Every "PASS" below was actually run; everything else is marked NOT RUN.

| # | Brief item | Status | Evidence / gap |
|---|---|---|---|
| 1 | Delivery fee economics | DONE (backend) | `createDeliveryRequest`/decline/cancel/expiry/FAILED/`confirmDelivery`; `deliveryEconomics.ts`; 26 unit tests in delivery-economics + delivery-escrow-flow. Business decisions listed in the CONTRACT. |
| 2 | Transaction safety | DONE for delivery + settlement | all new paths read-then-write; the test fake THROWS on read-after-write. NOT audited: finance.ts P2P/withdraw (reviewed earlier, no read-after-write found), advertising.ts. |
| 3 | Android App Links hosts | DONE (uncompiled) | `BuildConfig.WEB_HOST` per flavor; Navigation.kt + 3 share-URL sites use it (`LocalWebHost`, default = PRODUCTION). Remaining occurrences: google-services.json x3 and build.gradle flavor config (legitimate flavor config); docs/blueprint/PROJECT_STATE (documentation); `.github/workflows/deploy-firebase-hosting.yml` + `.firebaserc` (CI/config, review for prod deploy governance); `functions/*.js` and `functions/scripts/scratch/*` (dev-only scripts, hardcode the DEV project; accidental if run against prod). |
| 4 | assetlinks.json | OPEN | file contains only the DEV debug package + one fingerprint. STAGING and PRODUCTION entries need real SHA-256 fingerprints (Play App Signing / release keystore). Not invented. |
| 5 | Cross-shop delivery | VERIFIED IN CODE | server-side: provider listing must be DELIVER + available; any shop's provider is allowed; fee and provider come from the listing, not the client. Test: cross-shop request succeeds. Android `getDeliveryListings` filtering NOT reviewed. |
| 6 | Android delivery UI states | NOT DONE | needs compiled app + device. |
| 7 | Removed Android use cases | CHECKED | vs 33d711b the only providers removed are `RequestDeliveryUseCase` and `CreateDeliveryRequestUseCase` (Block 5); zero references remain; the replacement flow (DeliveryCheckout) is wired from Orders. FCM, user/shop/post search, messaging, notifications, social, advertising, wallet providers are unchanged. |
| 8 | Search | PARTIAL | listing search now queries `title_lowercase` with a trimmed, lowercased input and limit 30. Listings created before the engine may lack `title_lowercase`: backfill required. User search already uses `displayName_lowercase`. Shop/post search not reviewed. |
| 9 | Pagination | NOT DONE | not audited. |
| 10 | Android build | NOT RUN | BLOCKED: no Android SDK in this environment. |
| 11 | Functions tests | PARTIAL | `tsc` PASS. Emulator-free unit suites PASS (see below). Emulator suites NOT RUN (emulator download blocked). `commerce.test.ts`/`logistics.test.ts`/`security-rules.test.ts`/`notifications.test.ts` still need a run. No tests were deleted. One fixture updated: `create-delivery-job.unit.test.ts` now seeds an escrowed request (an un-escrowed paid request is now refused on purpose). |
| 12 | Firebase exports | PASS | compiled `functions/lib` loaded: 65 exports; all 6 hosting-referenced functions (payPreview, publicMarketplace, renderSharedListing, renderSharedDelivery, renderListingPreview, renderShopPreview) are exported. |
| 13 | Adversarial authorization | PARTIAL | unit-level: wrong buyer, wrong provider, non-driver status updates, mallory settlement, fee tampering, duplicate request/cancel/decline/expiry/settlement, insufficient balance, un-escrowed job. Firestore-rules attacks (wallet/ledger/escrow/settlement writes) are in `security-rules.test.ts` but NOT RUN. |
| 14 | Web verification | NOT DONE | needs a browser/hosting emulator. `/s/` `/d/` handler logic is unit-tested (Block 6). |
| 15 | Web payment | NOT DONE | MoPay availability on web not changed. |
| 16 | Navigation/UX hardening | NOT DONE | needs compiled app. |
| 17 | Documentation | PARTIAL | CONTRACT (delivery economics), MIGRATION, this file. AUTHORITY_MATRIX and PROJECT_STATE.md NOT updated. |
| 18 | Deployment governance | NOT DONE | `.github/workflows/deploy-firebase-hosting.yml` targets the DEV project; no staging/prod approval gates exist yet. |

## Known limitations of the delivery economics (decisions needed)
- A buyer cannot request delivery again once a job exists for the order, even if that job was cancelled/failed (order.fulfillmentId is set).
- Delivery fee is wallet-only. MoPay is not offered for delivery.
- `getDeliveryOptions` is unchanged; `requestDelivery` and `logistics.ts` (legacy) still deployed for old app versions and still create requests WITHOUT escrow; `createDeliveryJob` refuses such requests when their fee is > 0.

## Final classification
NOT READY (and BLOCKED for Android/emulator/browser evidence).
