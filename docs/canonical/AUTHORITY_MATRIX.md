# Authority Matrix — canonical-only target

| Domain | Canonical authority | Legacy authority | Final state |
|---|---|---|---|
| Listing validation/inventory/lifecycle | `functions/src/listing/*` | duplicate writers | Listing Engine remains sole authority |
| Purchase total | `calculatePurchaseTotal` | `calculateOrderFees` | legacy deleted |
| Purchase creation | `createPurchaseOrder` | `createOrder` | legacy deleted |
| Delivery request | `createDeliveryRequest` in `functions/src/fulfillment.ts` | `requestDelivery` / `logistics.ts` | legacy deleted |
| Fulfillment job/tracking | `createDeliveryJob` + `updateDeliveryStatus` | duplicate logistics implementation | legacy deleted |
| Fulfillment states | `functions/src/fulfillmentStates.ts` | obsolete pickup state | canonical vocabulary only |
| Android checkout | canonical purchase use cases/repository | legacy fee/order methods | legacy methods removed |
| Web checkout | `calculatePurchaseTotal` + `createPurchaseOrder` | legacy callables | canonical |

## Finance/settlement note
The canonical delivery request snapshots a provider listing fee after purchase. The purchase order's escrow total is created before that request and does not include the later delivery fee. Existing settlement code must not be interpreted as proof of provider payout for this later fee. No fee economics are changed by the legacy elimination migration; this remains a separate go-live blocker.
