# Block 5: Android delivery migration (code written, NOT compiled)

Decision: delivery is a fulfillment service attached to an existing paid order. The only Android delivery
request flow is `DeliveryCheckout` (order -> getDeliveryOptions -> createDeliveryRequest(orderId, listingId, dropoff)
-> provider accepts -> createDeliveryJob -> tracking). It is already wired from Orders and from post-purchase Checkout.

## Removed (legacy, order-less delivery request)
- Screens: `RequestDeliveryScreen`, `RequestDeliveryViewModel`; nav route `request_delivery/{listingId}` and `Screen.RequestDelivery`.
- Domain: `RequestDeliveryUseCase`, `CreateDeliveryRequestUseCase`; repository methods `requestDelivery`, `createDeliveryRequest`
  (the latter sent no orderId and was rejected by the canonical backend).
- DI: the two providers in `AppModule.kt`. Firebase impl methods removed.

## Rewired
- On a DELIVER listing (including /d/ deep links) the CTA now opens "My orders"; the buyer picks a paid order and
  chooses the provider in DeliveryCheckout.

## MUST VERIFY by a developer / Jules (no Android SDK in the authoring environment)
1. `./gradlew assembleDebug` and unit tests pass; fix any unused-import or missing-import warnings.
2. Manual flow: buy product -> Orders -> Request Delivery -> choose provider -> drop-off -> provider accepts -> tracking.
3. Product follow-up (optional): let ListingDetail pass the chosen provider into DeliveryCheckout (preselect), and show an
   "order eligibility" hint when the buyer has no paid orders.

## Backend still pending removal
`requestDelivery` callable and `logistics.ts` remain deployed only for old app versions (grace period / min-version gate).
