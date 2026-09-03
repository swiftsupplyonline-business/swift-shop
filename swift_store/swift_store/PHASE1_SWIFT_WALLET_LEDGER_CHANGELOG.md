# Phase 1 Changelog — Swift Wallet Atomic Debit + Ledger

Scope: narrow, explicitly authorized. Two changes only. Nothing else in this
package was modified from SwiftShop_corrected.zip.

## What changed

### 1. `functions/src/commerce.ts` — `createOrder`

**Problem (forensic finding, this session):** the `SWIFT_WALLET` payment path
in `createOrder` reserved inventory and created a `PENDING` order, but never
debited the buyer's wallet or wrote a ledger entry. A wallet checkout could
never actually complete — no money moved, no confirmation, order stuck
`PENDING` indefinitely.

**Fix:** inside the *existing* Step-1 Firestore transaction (same one that
already does inventory reservation), added, for `SWIFT_WALLET` only:

- A wallet read (`wallets/{uid}`) executed *before* any writes in the
  transaction — required by Firestore's reads-before-writes rule.
- A balance gate: if `availableBalanceMinorUnits < total`, throws before any
  write commits, so the whole transaction (including stock reservation)
  rolls back atomically. No dangling reservations on insufficient funds.
- A currency gate (must be LSL) — fails closed rather than silently
  mismatching currencies.
- An atomic debit of the wallet (`availableBalanceMinorUnits -= total`).
- A `walletTransactions` record (`type: PURCHASE`), matching the existing
  convention in `finance.ts` (deposit/withdrawal).
- A double-entry `ledgerEntries` write: `debitAccount: user_{uid}`,
  `creditAccount: system_order_escrow` — same escrow account MoPay's
  `verifyMopayPayment` already credits into, so wallet and MoPay orders land
  in the same place for later settlement.
- The order is created directly as `status: "CONFIRMED"` /
  `paymentStatus: "SUCCESS"` (wallet payment is internal and already
  verified — no external gateway round-trip needed, unlike MoPay).

**Not changed:** the MOPAY path, the M25 hard-coded delivery fee (explicitly
deferred per the roadmap pending its own pricing contract), settlement,
cancellation/refunds, and everything outside `createOrder`.

### 2. `functions/tsconfig.json`

**Problem (found while verifying #1):** `npm run build` (`tsc`) failed with
`TS5011` — `rootDir` was not set, and this reproduces on the untouched
baseline commit, before any of my changes. This means the project's own
build script has been broken independent of this work — `firebase deploy`
would have failed on this too.

**Fix:** added `"rootDir": "src"` — the standard, minimal fix for this error.
Config-only, no logic touched.

## Verification actually performed (not claimed, performed)

- `npx tsc --noEmit` — clean, zero errors, under the project's real
  `strict: true` / `noUnusedLocals: true` settings.
- `npm run build` — clean full build, confirmed by inspecting the compiled
  `functions/lib/commerce.js` for the new logic (`isWalletPayment`,
  `ORDER_CONFIRM_WALLET_...`, balance-check strings all present).
- Confirmed the `TS5011` build failure is pre-existing by stashing my change
  and re-running `npm run build` against the untouched baseline commit —
  same failure, so it's not something I introduced.

## Explicitly NOT verified (no Firebase access from this environment)

- Not deployed. Not run against the emulator or live `swift-dev-3d3ae`.
- No E2E test of an actual wallet checkout.
- No confirmation of whether the `createOrder`-response cast issue
  previously logged in memory was real on the *live* deployed function —
  you confirmed it was already fixed in this source, so no action was taken
  there.

Before this goes anywhere near production wallet checkouts, it still needs:
unit/integration test against the emulator, a real sandbox wallet-checkout
E2E run, and a concurrency test (two simultaneous orders against the same
low-balance wallet) to confirm the transaction actually contends correctly.
