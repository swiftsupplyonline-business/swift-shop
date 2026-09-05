# Corrected Package Changelog — 2026-08-30

This zip is a cleaned, corrected pass over `SwiftShop_Salvaged.zip`. It is source-only:
`build/`, `node_modules/`, `.gradle/`, `.git/`, `.idea/`, forensic checkout duplicates,
and ~70 stray UI-dump/log/screenshot files at the repo root were stripped. Original
was 233MB; this is source + gradle wrapper only. Re-run `./gradlew` / `npm install`
to regenerate everything that was removed.

## Code changes in this pass

1. **`functions/src/logistics.ts` (new)** — implements `requestDelivery` and
   `updateDeliveryStatus`, which the Android client (`FirebaseDeliveryRepository.kt`)
   already calls but which did not exist server-side. Contract matches the client
   exactly. Includes order-status validation, authorization, and a linear delivery
   status state machine. Driver-assignment logic assumes a `role: "DRIVER"` custom
   claim — verify this is actually how drivers are provisioned before deploying.

2. **`functions/src/advertising.ts` (new)** — implements `activateCampaign` and
   `cancelCampaign`, called by `FirebaseAdvertisingRepository.kt` but previously
   missing server-side. Follows the existing wallet/ledger escrow pattern from
   `finance.ts`: full budget is escrowed on first activation, refunded in full on
   cancellation. **Assumption flagged in code**: `CampaignStatus` has no dedicated
   `CANCELLED` value, so cancellation maps to `COMPLETED` + a `cancelledEarly: true`
   flag. Consider adding a real `CANCELLED` enum value instead — cleaner long-term.
   There's also no per-impression spend metering yet, so refunds are always full;
   this matches the blueprint's own §27 note that escrow wait-time rules are
   unresolved.

3. **`functions/src/index.ts`** — exports the two new files.

4. **`docs/firestore.rules`** — fixed a real bug in the `deliveryRoutes` update rule:
   it required a nested `driverCurrentLocation` map, but the Android client
   (`FirestoreDeliveryRoute`) writes flat `driverCurrentLocationLat` /
   `driverCurrentLocationLng` fields. As written, every driver location update
   would have been silently rejected. Also removed `status` from the client's
   directly-writable fields — status now only changes through
   `updateDeliveryStatus`, consistent with the server-authoritative model used
   everywhere else (client was previously allowed to write delivery status
   directly, which is a gap of the same shape as the listing-creation issue
   below).

## Verified, not changed

- **`cancelOrder`, `updateOrderStatus`, `createListing`** (in `commerce.ts`) are
  already fully implemented, including correct stock restoration on cancel. They
  are **not yet deployed** — this was a deploy gap, not a code gap. No changes made.
- Confirmed by inspection: Android's `PaymentMethod` enum (`MOPAY`, `SWIFT_WALLET`)
  sent via `.name` matches exactly what `createOrder` validates against. One of
  the two open questions from before is resolved.
- Confirmed `provisionNewUser` creates a `wallets/{uid}` doc on every new
  `users/{uid}` creation — answers the second open question, with one caveat: this
  only covers users created *after* this trigger was deployed. Any earlier users
  (or ones created by ad-hoc scripts bypassing the trigger) may still lack a
  wallet doc and should be checked before assuming universal coverage.
- All of `functions/src/*.ts` (existing + new) passes `tsc --noEmit --strict`
  cleanly.

## Still outstanding (unchanged by this pass)

- Nothing has been deployed. Everything below still needs `firebase deploy`.
- `createListing` — Android's "Create Listing" flow writes directly to Firestore
  instead of calling the Cloud Function (server-authority gap) — not touched yet.
- RUNTIME-002 — still unverified at the code level.
- Search Title results — still broken.

## Deploy commands (run these yourself — not run in this session)

```
cd functions
npm install
npm run build
firebase deploy --only functions:cancelOrder,functions:updateOrderStatus,functions:createListing,functions:requestDelivery,functions:updateDeliveryStatus,functions:activateCampaign,functions:cancelCampaign
firebase deploy --only firestore:rules
```

Deploy the rules change too — the `deliveryRoutes` fix only takes effect once pushed.
