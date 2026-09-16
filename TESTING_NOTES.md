# MANUAL TESTING SCRIPT: DELIVERY RESPONSE FEATURE

This script covers the runtime verification of the Merchant Accept/Decline flow (Batch 1), which requires two separate user accounts.

## Prerequisites
- Two Android devices (or emulators) running Swift Shop.
- Device A signed in as **Buyer**.
- Device B signed in as **Seller** with at least one active listing of type `DELIVER`.

---

## Test Scenario 1: Successful Delivery Acceptance
1. **[Device A - Buyer]** Navigate to a listing from Seller B.
2. **[Device A - Buyer]** Tap "Add to Cart" and proceed to Checkout.
3. **[Device A - Buyer]** In the Delivery section, select "I want it delivered".
4. **[Device A - Buyer]** Select a delivery provider and complete the address.
5. **[Device A - Buyer]** Complete the checkout process (Place Order).
6. **[Device A - Buyer]** Navigate to the order details and tap "Request Delivery" (if not automated). The UI should transition to a "WAITING" state for the delivery request.
7. **[Device B - Seller]** Navigate to `My Orders` -> `Sales mode`.
8. **[Device B - Seller]** Tap the delivery-requests icon (truck/clipboard icon) in the top bar.
9. **[Device B - Seller]** Locate the pending request from Buyer A.
10. **[Device B - Seller]** Tap "Accept".
11. **[Device A - Buyer]** Observe the delivery request status. It MUST update to "ACCEPTED" automatically without a screen refresh.

## Test Scenario 2: Delivery Decline
1. Repeat steps 1-9 from Scenario 1.
2. **[Device B - Seller]** Tap "Decline".
3. **[Device A - Buyer]** Observe the delivery request status. It MUST update to "DECLINED" automatically without a screen refresh.

## Test Scenario 3: Concurrency / Double-Tap Prevention
1. Repeat steps 1-9 from Scenario 1.
2. **[Device B - Seller]** Tap "Accept" twice very quickly.
3. **Verify:** The app must not crash. The second tap should either be ignored (UI disabled) or result in a clean "Already responded" message. The backend transaction logic in `functions/src/logistics.ts` already prevents duplicate status changes, but the UI should handle this gracefully.

---
**Verification Result (2026-09-15):** Not testable in this environment (single device available). Script provided for manual verification.
