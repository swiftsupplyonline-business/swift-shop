# Block 2 Audit: Purchase-first commerce

Canonical: `calculatePurchaseTotal` -> `createPurchaseOrder`. Legacy: `calculateOrderFees`, `createOrder`.

## Consumer status
| Consumer | Status |
|---|---|
| Web `hosting/app.js` checkout | Already canonical. Stale variable names/comment fixed in this branch. |
| Android `CheckoutViewModel` | Already canonical (CalculatePurchaseTotalUseCase, CreatePurchaseOrderUseCase). |
| Android legacy path | DEAD CODE: `CalculateOrderFeesUseCase`, `PlaceOrderUseCase`, `CommerceRepository.calculateOrderFees/placeOrder`, their Firebase + OfflineFirst implementations, and the two providers in `AppModule.kt`. No feature module calls them. |
| Backend callables | `calculateOrderFees`, `createOrder` still exported from commerce.ts/index.ts. `functions/src/test/commerce.test.ts` tests `createOrder` (3 tests) and must be ported to `createPurchaseOrder` first. |
| Other | `functions/verification.js` (old manual script), comment in `logistics.ts`. |

## Removal order (each step only after the previous is verified)
1. Android: delete the dead legacy use cases/repository methods/providers; `./gradlew build` must pass. (Needs Android SDK; not possible in the review sandbox.)
2. Backend: port the 3 createOrder tests to createPurchaseOrder, run with Firestore emulator, then delete `createOrder` and `calculateOrderFees` from commerce.ts and index.ts.
3. Confirm no deployed client version still calls them (old APK in the wild). Consider a deprecation window or force-update before deleting server callables.
4. `scripts/canonical-audit.sh --strict` must report 0 for both symbols.

## Decision needed from product owner
Old Android builds may still call `createOrder`. Delete the callables only after a minimum-supported-app-version gate is in place.
