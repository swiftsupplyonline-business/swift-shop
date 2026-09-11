# DELIVERY LISTING ARCHITECTURE
## M25 Replacement — Authoritative Delivery Fee from Listing

**Date:** September 2026  
**Status:** IMPLEMENTED  
**Phase:** W2.4C — Delivery Listing / Pricing Contract  

---

## The Problem

The previous implementation contained a hard-coded delivery fee in Cloud Functions:

```typescript
const deliveryFee = 2500; // Flat M25.00 for DEV
```

This appeared in two Cloud Functions:
- `calculateOrderFees`
- `createOrder`

This violated the canonical architecture principle:

> Delivery Route ≠ Delivery Price  
> The price comes from the selected delivery offering.

---

## The Solution

Delivery providers create a **DELIVER-type listing** in the marketplace. This listing carries the canonical delivery fee as `priceMinorUnits`. The buyer selects this listing at checkout. The backend reads the fee from the listing document — never from the client and never from a constant.

### Architecture flow

```
Delivery Provider
      ↓
Creates DELIVER listing (listingType = "DELIVER")
      ↓
Sets price (e.g. M50.00 = priceMinorUnits: 5000)
      ↓
Listing appears in checkout delivery selection screen
      ↓
Buyer selects delivery listing
      ↓
Client sends: deliveryListingId (NOT a fee amount)
      ↓
Backend reads listing document → priceMinorUnits
      ↓
Fee applied to order total
      ↓
DeliveryListingSnapshot stored on order (immutable)
```

### Authoritative rule

The client **must never** pass a fee amount to the backend. Only the `deliveryListingId` is sent. The backend is the only financial authority.

---

## Files Changed

### Cloud Functions (`functions/src/commerce.ts`)

**`calculateOrderFees`**
- Now requires `deliveryListingId` in request
- Validates listing exists, is type `DELIVER`, and is available
- Reads `priceMinorUnits` from listing document
- Removed: `const deliveryFee = 2500`

**`createOrder`**
- Now requires `deliveryListingId` in request
- Validates delivery listing before transaction (fail fast)
- Stores `selectedDeliveryListingId` and `deliveryListingSnapshot` on order document
- The snapshot preserves the price at order time — immutable even if provider changes listing later

### Android — Domain Layer (`domain/commerce/CommerceUseCases.kt`)

- `OrderSummary` gains `selectedDeliveryListingId: String`
- `CommerceRepository` gains `getDeliveryListings(): Result<List<DeliveryListing>>`
- `calculateOrderFees` now takes `deliveryListingId: String`
- `placeOrder` now takes `deliveryListingId: String`
- New: `GetDeliveryListingsUseCase`
- Updated: `PlaceOrderUseCase` validates `deliveryListingId` is non-blank

### Android — Models (`core/model/Models.kt`)

New models:
- `DeliveryListing` — the commercial offering a delivery provider creates
- `DeliveryListingSnapshot` — immutable snapshot stored on Order at checkout

Updated:
- `Order` gains `selectedDeliveryListingId: String` and `deliveryListingSnapshot: DeliveryListingSnapshot?`

### Android — Data Layer

**`FirebaseCommerceRepository`**
- `getDeliveryListings()` — queries Firestore `listings` collection where `listingType == "DELIVER"` and `isAvailable == true`
- New DTO: `FirestoreDeliveryListingDto` maps DELIVER listings to `DeliveryListing` domain model
- New DTO: `FirestoreDeliveryListingSnapshot` for reading snapshot from Order document
- `calculateOrderFees` passes `deliveryListingId` to Cloud Function
- `placeOrder` passes `deliveryListingId` to Cloud Function

**`OfflineFirstCommerceRepository`**
- Implements updated `getDeliveryListings`, `calculateOrderFees`, `placeOrder` signatures
- Delegates to `FirebaseCommerceRepository` (no local caching for delivery listings — always fresh)

### Android — Feature Layer

**`CheckoutViewModel`**
- New state: `DeliveryListingsState` (Loading / Loaded / Error)
- New flow: `selectedDeliveryListing: StateFlow<DeliveryListing?>`
- `loadDeliveryListings()` — called when buyer enters delivery step
- `selectDeliveryListing(listing)` — buyer's selection
- `placeOrder()` — validates delivery selection before proceeding
- Fee recalculation triggered when delivery listing changes

**`CheckoutScreen`**
- New step: `CheckoutStep.DELIVERY` — inserted between CART and ADDRESS
- Step indicator updated: Cart → Delivery → Address → Payment
- `DeliverySelectionStep` — scrollable list of delivery provider cards
- `DeliveryListingCard` — shows provider name, coverage area, estimated time, price
- Continue button disabled until a delivery listing is selected
- Payment step now shows full order summary including delivery fee

### DI (`app/di/AppModule.kt`)

- `provideGetDeliveryListingsUseCase` added to `CommerceModule`

---

## Firestore Schema — DELIVER Listing

A delivery listing is stored in the same `listings` collection as any other listing. Additional fields supplement the standard fields:

```
listings/{listingId}
  listingType: "DELIVER"          ← marks this as a delivery listing
  sellerId: {providerId}          ← delivery provider's uid
  shopId: {shopId}                ← provider's shop
  providerName: string            ← display name
  providerAvatarUrl: string
  title: string                   ← e.g. "Standard Maseru Delivery"
  description: string
  priceMinorUnits: number         ← THE canonical delivery fee
  priceCurrency: "LSL"
  estimatedMinutes: number
  coverageArea: string            ← e.g. "Maseru CBD & surrounds"
  isAvailable: boolean
  rating: float
  completedDeliveries: number
  imageUrls: [string]
  createdAt: Timestamp
  updatedAt: Timestamp
```

---

## Firestore Schema — Order (additions)

```
orders/{orderId}
  selectedDeliveryListingId: string          ← reference to the chosen listing
  deliveryListingSnapshot: {                 ← immutable at order time
    listingId: string
    providerId: string
    providerName: string
    title: string
    priceMinorUnits: number
    currency: "LSL"
    estimatedMinutes: number
  }
  deliveryFeeMinorUnits: number              ← authoritative fee from listing
```

---

## Architecture Laws Upheld

1. **Client is never financial authority** — only `deliveryListingId` is sent, not a fee amount
2. **Backend validates listing** — checks existence, type, and availability before accepting order
3. **Immutable historical records** — snapshot preserves commercial agreement at order time
4. **No distance-based pricing** — fee comes exclusively from provider's listing
5. **No hard-coded constants** — M25 constant is fully removed from both Cloud Functions

---

## What Has Not Changed

- The delivery listing fee is still a flat fee per order (not per km)
- Distance-based pricing is NOT implemented — that requires a separate formal contract
- The delivery assignment/tracking flow is unchanged
- Settlement rules are unchanged (still deferred pending contract)

---

## Next Steps (Not In This Phase)

- W2.5: Maps / Drop Your Pin implementation
- W2.6: Fulfillment lifecycle implementation
- W2.7: Settlement
- Delivery provider experience (creating DELIVER listings from the seller flow)
