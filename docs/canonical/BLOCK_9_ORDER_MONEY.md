# Block 9 — Order money flows (commerce.ts)

Branch `block-9-order-money`, based on `block-8-rules-money`. Not merged. Type-checks; 35 emulator-free unit tests pass.
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
- `updateOrderStatus`: buyer could set CANCELLED directly (no stock release, no refund) and admin could set any status
  with no payment check. It now only moves a PAID order along CONFIRMED -> PROCESSING -> READY (admin also READY ->
  DISPATCHED); CANCELLED/REFUNDED must go through `cancelOrder`; DELIVERED only via `confirmDelivery`/fulfillment route.
  The app already cancels through `cancelOrder`, so no client change is needed (`orderTransitions.ts`).
- Late MoPay SUCCESS on an order already CANCELLED/FAILED (e.g. reservation expired while the buyer was paying): used to
  throw while the buyer was charged. Now records a `paymentRefunds/{orderId}` doc (status REQUIRED), a ledger entry into
  `system_refund_pending`, sets order `paymentStatus: SUCCESS_AFTER_CANCEL` / `refundStatus: REQUIRED`, and returns
  status `PAID_AFTER_CANCEL`. Idempotent on repeat. There is still no automatic refund: someone must pay it back manually.
- Block 8 rules test was missing the `assertSucceeds` import (did not compile).

## Behaviour changes that need your OK
- Buyer can cancel only while PENDING/RESERVED/CONFIRMED/PROCESSING; seller/admin until completion.
- Cancelling an order paid via MoPay is refused (previously it marked CANCELLED and kept the buyer's money).
  Needs a MoPay refund path.

## Still open
- Nothing reads or acts on `paymentRefunds` yet (no admin screen/function to mark a refund done or credit the wallet).
  The client does not know the `PAID_AFTER_CANCEL` status; it will show it as an unknown result.
- Buyer app calls `updateOrderStatus(DISPATCHED)` for READY orders (OrdersViewModel.fulfillOrder), which a seller is not
  allowed to do (courier route sets DISPATCHED). Seller self-delivery therefore fails today; needs a product decision.
- Cancelling an order that is DELIVERED but not yet settled is refused (buyer must confirm delivery or contact support).
- `confirmDelivery`: update on a missing seller wallet throws (settlement stuck); delivery fee is paid to the seller, not the
  delivery provider (product decision); no paymentStatus check.
- `confirmMopayPayment` (admin) only accepts PENDING, which new orders never are; no gateway check, no commit.
- A delivery request is not consumed by the order that uses it (reusable).
- `commitmentCount` is not decremented when committed stock is returned.
- No emulator tests for order flows.

## Block 9c — rejectWithdrawal
New admin-only callable `rejectWithdrawal({ transactionId, reason? })` in `finance.ts`: PENDING withdrawal -> FAILED,
amount moves pending -> available, reversal ledger entry `system_withdrawal_escrow` -> `user_<uid>`
(`WITHDRAW_REJECT_<id>`). Idempotent; COMPLETED withdrawals cannot be rejected. Covered by an in-memory-Firestore
unit test (not the emulator). There is no admin UI yet, and the client is not notified on rejection.
