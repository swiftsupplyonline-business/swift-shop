# Block 9 — Order money flows (commerce.ts)

Branch `block-9-order-money`, based on `block-8-rules-money`. Not merged. Type-checks; 29 emulator-free unit tests pass.
NOT run: Firestore emulator tests, any MoPay flow, any order transaction against a real/emulated Firestore.

## Fixed
- Negative/fractional/NaN quantity: `createOrder` and `createPurchaseOrder` multiplied price by an unvalidated
  quantity. A negative quantity gave a negative total, passed the balance check and CREDITED the wallet.
  Quantities are now whole numbers 1..999, max 50 lines, repeated listings merged (`parseOrderItems`).
- Reads after writes: both order transactions, `verifyMopayPayment` (success + failure) and `cancelOrder`
  called `transaction.get` after writing. The Firestore SDK throws on that, so multi-item carts and wallet orders
  on stocked listings could not complete. All reads now happen first (`getAll`).
- Wallet orders never committed stock and left reservations ACTIVE; the 5-minute expiry sweep then released a
  paid order's hold (stock never decremented, oversell). Wallet orders now commit stock in the same transaction
  (`purchaseAndCommitInventory`), reservation status COMMITTED.
- `cancelOrder` could refund a settled order (seller already paid) = money created. Settled/DELIVERED/COMPLETED/FAILED
  orders are no longer cancellable. Wallet refunds now cover every paid, unsettled state, use set+merge so a missing
  wallet doc cannot drop a refund, and return committed stock (`returnCommittedInventory`).
- `createOrder` idempotency keys were shared across users; now per-user (`<uid>_order_<key>`).
- Replayed key re-opened a second payable MoPay session (and used amount 0); now returns the existing session.
- Own-listing purchase blocked; non-LSL listings and invalid prices/delivery fees rejected; suspended accounts
  cannot place orders; paymentMethod must be MOPAY or SWIFT_WALLET.
- Block 8 rules test was missing the `assertSucceeds` import (did not compile).

## Behaviour changes that need your OK
- Buyer can cancel only while PENDING/RESERVED/CONFIRMED/PROCESSING; seller/admin until completion.
- Cancelling an order paid via MoPay is refused (previously it marked CANCELLED and kept the buyer's money).
  Needs a MoPay refund path.

## Still open
- `updateOrderStatus`: buyer "CANCELLED" writes status directly (no reservation release, no refund); admin can set
  any status with no payment check.
- Late MoPay SUCCESS on an order already cancelled/expired throws; buyer is charged, no automatic refund.
- `confirmDelivery`: update on a missing seller wallet throws (settlement stuck); delivery fee is paid to the
  seller, not the delivery provider (product decision); no paymentStatus check.
- `confirmMopayPayment` (admin) only accepts PENDING, which new orders never are; no gateway check, no commit.
- A delivery request is not consumed by the order that uses it (reusable).
- `commitmentCount` is not decremented when committed stock is returned.
- No emulator tests for order flows.
