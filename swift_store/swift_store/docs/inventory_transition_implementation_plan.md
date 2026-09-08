# Implementation Plan - Coordinated Inventory Architecture Transition

## Goal Description
Safely transition the production environment (`swift-dev-3d3ae`) from the legacy `stockQuantity` direct-decrement model to the canonical reservation-hold architecture. This requires coordinated deployment of lifecycle Functions and non-destructive reconciliation of legacy listing data.

## User Review Required

> [!IMPORTANT]
> **Cloud Scheduler Infrastructure**: The `Cloud Scheduler API` must be enabled in the Google Cloud Console before deployment. The automated reaper (`cleanupExpiredReservations`) depends on this to prevent inventory leaks from abandoned orders.

> [!WARNING]
> **Schema Enforcement**: Once deployed, the new `createOrder` will strictly reject any listing that has not been migrated to the canonical schema (`totalQuantity`/`reservedQuantity`). The five identified legacy listings must be reconciled immediately after code deployment.

## Proposed Changes

### Cloud Functions - Coordinated Lifecycle Release

The following functions must be deployed together as a single atomic set to ensure state-machine compatibility.

#### [MODIFY] [commerce.ts](file:///C:/Users/Tech%20Semiconductors/AndroidStudioProjects/SwiftShop_Candidate_Claude/swift_store/swift_store/functions/src/commerce.ts)
- **`createOrder`**: Implement canonical reservation creation. Require `totalQuantity`/`reservedQuantity`. Increment `reservedQuantity` transactionally. Fail closed for legacy schema.
- **`verifyMopayPayment`**: Implement reservation commit (decrement `totalQuantity`) on SUCCESS and release (decrement `reservedQuantity`) on FAILURE/CANCELLED.
- **`cancelOrder`**: Implement reservation release. Use terminal-state protection to prevent double-restoration.
- **`createListing` / `updateListing`**: Ensure all new/updated listings are written with the canonical inventory fields.

#### [MODIFY] [maintenance.ts](file:///C:/Users/Tech%20Semiconductors/AndroidStudioProjects/SwiftShop_Candidate_Claude/swift_store/swift_store/functions/src/maintenance.ts)
- **`cleanupExpiredReservations`**: [NEW DEPLOYMENT] Automated reaper to release `ACTIVE` reservations older than 15 minutes.

---

### Data Reconciliation - Legacy Listing Migration

A narrowly scoped migration is required for the five proven legacy listings.

| Listing ID | Title | Current Stock | Action |
| :--- | :--- | :---: | :--- |
| `QmEKHrVOAEAg6SZmj2C6` | Standard Beauty Balm | 98 | Init `totalQuantity: 98`, `reservedQuantity: 0` |
| `WJ4lo7KlgDj8Vv9X21fG` | Elizabeth Arden Red Door | 98 | Init `totalQuantity: 98`, `reservedQuantity: 0` |
| `YlQXjzczeipls6VTNnlo` | Local delivery | 1 | Init `totalQuantity: 1`, `reservedQuantity: 0` |
| `sSHYPE7fqpQwZOkHSO1S` | Book your car for service | 100 | Init `totalQuantity: 100`, `reservedQuantity: 0` |
| `vJ0RRQwa7P0KMFi25zNx` | Laundry combo | 15 | Init `totalQuantity: 15`, `reservedQuantity: 0` |

> [!NOTE]
> Since the legacy system decremented `stockQuantity` immediately at order creation, the current values already exclude items in the four existing `PENDING` orders. Migration to `totalQuantity` preserves this "already-removed-from-shelf" state.

---

## Verification Plan

### Automated Pre-Deployment Gates
1. **Compilation**: `cd functions; npm run build`
2. **Unit Tests**: `cd functions; npm test` (If exists)
3. **Emulator Integration**: Run the full `createOrder -> verifyMopayPayment -> cleanup` lifecycle in the Firebase Emulator with mixed-schema data.

### Production Deployment Sequence
1. **Gate 1**: Confirm `Cloud Scheduler API` is enabled.
2. **Gate 2**: Deploy the coordinated set: `firebase deploy --only functions:createOrder,functions:verifyMopayPayment,functions:cancelOrder,functions:cleanupExpiredReservations,functions:createListing,functions:updateListing`
3. **Gate 3**: Execute the targeted data migration script for the five legacy listings.
4. **Gate 4**: Verify reaper execution logs.

### Manual Verification
- Attempt checkout with a migrated legacy listing.
- Verify reservation document creation.
- Verify `reservedQuantity` increment.
- Verify 15-minute expiration release.
