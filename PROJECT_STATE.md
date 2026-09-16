# SWIFT SHOP — PROJECT STATE (living document)

**If you are an AI agent (Gemini, Claude, or otherwise) or a human developer
opening this repo: read this entire file before editing anything.** It is
the single source of truth for what's done, what's in flight, what's locked,
and what broke. It is not a static plan — update it every time you finish or
start a unit of work. Stale entries here are worse than no entries, because
the next person/agent will trust them.

The full long-range roadmap and phase breakdown lives in
`SWIFT_MASTER_PROJECT_BLUEPRINT.md` at repo root. This document is the
execution/state tracker that sits on top of it — check both.

---

## 0. THE RULE THAT MATTERS MOST

**Before touching any file in the RED list below, stop and check this
document's "Locked Files Ledger" (Section 2) for that exact file.** If the
ledger doesn't show it as explicitly opened for the change you're about to
make, do not proceed. Propose the change in Section 5 ("Proposed / Awaiting
Review") instead, and wait for a human to move it to "Authorized" before
implementing.

This rule exists because it has already been violated once in this project
(see Section 4, Incident 1). That incident produced two live, user-facing
bugs that shipped to a real device before anyone reviewed the diff. The cost
of skipping this step is measured in hours of debugging, not saved.

---

## 1. GOVERNANCE MODEL (verbatim — do not paraphrase away the constraints)

Three risk tiers. Classify every change before starting it.

### RED — CRITICAL AUTHORITY
`functions/src/commerce.ts`, `functions/src/finance.ts`, `functions/src/mopay.ts`,
Firestore security rules, Storage security rules, inventory mutation, wallet
balances, ledger entries, escrow, payment verification, financial state
transitions, order/payment authority — anything that can create, destroy,
duplicate, or misallocate money, and anything that changes server-side
security boundaries.

Cycle: **INSPECT → PROPOSE → REVIEW → IMPLEMENT → BUILD → VERIFY →
ADVERSARIAL VERIFY → EXPLICIT DEPLOY AUTHORIZATION.** Never cross this
boundary silently. A human must explicitly authorize implementation, and
separately, explicitly authorize deploy. Implementation authorization is
NOT deploy authorization.

### AMBER — IMPORTANT BUSINESS LOGIC
Orders UI, delivery flows, authentication, seller/buyer flows,
notifications, repository behavior, non-authority Firebase reads/writes.

Cycle: **INSPECT → IMPLEMENT BOUNDED SLICE → BUILD → TEST → DIFF REVIEW →
RUNTIME VERIFY.** Stop and escalate to RED-style review if: a RED boundary
is touched, architecture contradicts the task, existing behavior would be
destructively changed, a required backend contract is missing, or the task
can't be completed safely.

### GREEN — PRODUCT/UI WORK
Compose UI, navigation, presentation state, non-authoritative client
interactions, layouts, display formatting.

Cycle: **INSPECT → IMPLEMENT → BUILD → VERIFY → REPORT.** No artificial
approval gates.

### Locked files (never open without explicit human authorization, regardless of tier logic above)
- `functions/src/commerce.ts`
- `functions/src/finance.ts`
- `functions/src/mopay.ts`
- `core/model/src/main/java/com/swiftshop/core/model/Models.kt`
- Theme.kt
- FeedEngine.kt
- Firestore/Storage security rules
- posts/bookmarks likes migration
- `swift_store/swift_store` donor tree (harvest source only, never build)

### Definition of done
A feature is done when a real user can complete the whole journey, not when
a file compiles. ("Create something to sell" is not done when
`CreateListingViewModel` compiles — it's done when a buyer can find and buy
the listing and the seller sees the sale.)

### Deploy discipline
Implementation and deployment are separate. `firebase deploy` and any
production push is NEVER an automatic consequence of implementation.
Committing to git is likewise never automatic — see Incident 1.

---

## 2. LOCKED FILES LEDGER

Every RED/locked file, its last human-reviewed state, and its current
status. **Update this table the moment a RED file's content changes, even
if you didn't make the change** — if you find a RED file dirty and you
don't have a matching entry here, that's an incident (see Section 4), not
a batch to continue.

| File | Last reviewed commit/state | Status as of this writing | Notes |
|---|---|---|---|
| `functions/src/commerce.ts` | Reviewed 2026-09-16 | Reviewed + partially fixed — 2 items still open (see Section 5) | Three layers reconciled 2026-09-16: (1) original Incident-1 diff (delivery-listing selection + seller status-transition matrix) — traced line-by-line, retroactively authorized by human operator; (2) Bug 1 fix (named-item "not found" error) — implemented + build-verified, see Batch 3; (3) shop-mismatch `shopId` ownership check — implemented + build-verified, see Batch 3. Open: fallback-path arbitrary pick when 2+ DELIVER listings exist and none selected (Section 5); `SELLER_TRANSITIONS` matrix not yet adversarially tested. |
| `functions/src/finance.ts` | `ebfae2c` | Clean | No known pending work |
| `functions/src/mopay.ts` | `ebfae2c` | Clean | No known pending work |
| `core/model/Models.kt` | `ebfae2c` | **DIRTY, UNREVIEWED** — adds `requiresDelivery: Boolean` and `selectedDeliveryListingId: String` to `Order` | Same unauthorized change set as commerce.ts above |
| Firestore rules (`docs/firestore.rules`) | `ebfae2c` | Clean | `deliveryRequests`, `wallets`, `ledgerEntries`, `walletTransactions` all correctly server-only as of last read. `listings.likeCount`/`commentCount` still weak (any signed-in user can write) — known, unfixed, tracked as open item |
| `functions/src/logistics.ts` | `ebfae2c` | Clean, batch complete | `respondToDeliveryRequest` (accept/decline) implemented and verified this session — see Section 6, Batch 1 |

**If you are an agent and you find a RED file dirty with no row here
explaining why: STOP. Add a row documenting exactly what you found, flag it
to the human operator, and do not build on top of it until reviewed.**

**Update 2026-09-16:** the code content introduced in this incident (delivery-listing selection logic + seller status-transition matrix in commerce.ts) has since been traced line-by-line and retroactively authorized by the human operator. This resolves the was-this-code-sound question but NOT the how-did-a-commit-get-pushed question — that remains open.

---

## 3. OPEN BUGS (found via real device runtime verification, not yet fixed)

### Bug 1 — "Listing {id} not found" empties the whole cart at checkout
- **Symptom:** buyer has a cart item referencing a listing that no longer
  exists (e.g. deleted by seller); `calculateOrderFees` throws for the
  entire cart instead of handling the one dead item.
- **Root cause:** a per-item existence check inside `commerce.ts`
  `calculateOrderFees` (exact line not yet located — search for the string
  template that produces `"Listing {id} not found"`) aborts the whole
  calculation on one bad item.
- **Fix classification:** RED (`commerce.ts`) — requires full review cycle.
- **Proposed fix shape (not yet authorized/implemented):** either drop
  unavailable items from the calculation and report which ones were
  dropped, or fail with a specific, actionable error naming the exact
  unavailable item — not a bare exception. Needs a product decision on
  which behavior is wanted before implementation.

### Bug 2 — cart appears to empty when choosing a delivery provider (confirmed root cause)
- **Symptom:** tapping a delivery provider in checkout step 1 makes the
  cart list disappear with no error message.
- **Confirmed root cause:** in `CheckoutViewModel.updateFees()`,
  `_uiState.value = CheckoutUiState.Loading` is set synchronously, with no
  debounce, the instant a provider is tapped (`updateSelectedDeliveryListing`
  calls `updateFees()` directly). `CheckoutScreen.CartStep` reads
  `val items = (state as? CheckoutUiState.CartLoaded)?.items ?: emptyList()`
  — so during the `Loading` window (or indefinitely, if the call hangs) the
  screen renders `EmptyState("Your cart is empty")`, which is
  indistinguishable from an actually-empty cart. Same latent bug exists for
  the address and requires-delivery toggles too, just masked by their
  500ms debounce.
- **Fix classification:** AMBER, client-only. Does not touch commerce.ts.
- **Proposed fix shape (not yet authorized/implemented):** stop discarding
  the last-known items/summary on recalculation — either keep rendering the
  previous `CartLoaded` data with a small inline spinner overlay while a
  new fee calculation is in flight, or add a distinct
  `Recalculating(items, summary)` state that `CartStep` treats the same as
  `CartLoaded` for rendering purposes.

### Open item (not a bug, a known gap) — delivery-listing shop mismatch
- The new `selectedDeliveryListingId` path in `commerce.ts` never checks
  the selected DELIVER listing belongs to the same shop as the order, and
  the client's `getDeliveryListings()` query has no `shopId` filter — pulls
  every available DELIVER listing marketplace-wide. A buyer could currently
  select an unrelated shop's delivery listing. RED, not yet scoped as a
  batch.

---

## 4. INCIDENT LOG

### Incident 1 — unreviewed RED-file changes + unauthorized commit/push
- **What happened:** at some point between sessions, `commerce.ts` and
  `core/model/Models.kt` (both locked) were modified to add
  buyer-selectable delivery listings, without going through the RED
  proposal/review cycle documented in this file. Separately, a commit
  (`ebfae2c "checkpoint: current Swift Shop state"`) was created and pushed
  to `origin/reconciled-tier2-2026-09-11`, despite every batch in this
  project's history carrying an explicit "do NOT commit, do NOT deploy"
  instruction.
- **Impact:** the unreviewed delivery-listing-selection feature is the
  direct cause of Bug 2 above, and is implicated in Bug 1.
- **Status:** unresolved as of this writing — the human operator has not
  yet confirmed how the commit/push happened (which tool, which session).
- **Standing instruction for any agent:** do not assume silence on this
  question means it's resolved. If you're picking up this repo and this
  incident still shows "unresolved," ask before treating the tree as a
  clean baseline.

---

## 5. PROPOSED / AWAITING REVIEW (nothing here is authorized to implement)

- Delivery-listing fallback ordering gap in `commerce.ts` (RED) — when
  `requiresDelivery` is true, no `selectedDeliveryListingId` is given, and
  a shop has 2+ DELIVER listings, the fallback path takes
  `deliverySnap.docs[0]` with no defined ordering. Not a security issue
  (already shop-scoped), but "which delivery option did the buyer get" is
  effectively undefined. A proposed diff exists in `RED_PROPOSALS.md`,
  added 2026-09-16 — note it likely requires a new Firestore composite
  index, flagged in the proposal itself.
- Adversarial edge-case testing of the `SELLER_TRANSITIONS` matrix in
  `updateOrderStatus` (implemented as part of the authorized Incident-1
  diff, never stress-tested — e.g. behavior on PENDING, whether a seller
  can skip straight to READY)
- FCM/push notifications — no infrastructure exists anywhere in the
  project yet (checked exhaustively across `functions/src/`); needed for
  the delivery-request flow to be genuinely usable, not yet scoped as its
  own batch
- Accepted-delivery-request → order/payment/escrow bridge — explicitly
  deferred pending a read-only architecture recon (never started)

## AUTHORIZED / IN PROGRESS

(none)

---

## 6. BATCH LOG (append a new entry every time a batch closes — never delete history)

### Batch 4 — CartStep/Error state cosmetic fix (2026-09-16)
- **Classification:** GREEN (presentation-only, no logic change)
- **Files:** `feature/checkout/src/main/java/com/swiftshop/feature/checkout/CheckoutScreen.kt`
- **Fix:** `CartStep`'s empty-items branch previously showed "Your cart is empty, add items to get started" for BOTH a genuinely empty cart AND an `Error` state, contradicting the real error already shown in the footer. Replaced the if/else with a three-way `when` on state; `Error` now shows a neutral "Something went wrong / see the message below" placeholder instead.
- **Status:** implemented, build-verified (`gradlew.bat assembleDevDebug` PASS, `feature:checkout` recompiled cleanly, 1m 16s). NOT committed, NOT deployed.

### Batch 3 — Bug 1 fix + shop-mismatch fix in commerce.ts (2026-09-16)
- **Classification:** RED (functions/src/commerce.ts)
- **Files:** `functions/src/commerce.ts`, `domain/commerce/CommerceUseCases.kt`, `data/firebase/FirebaseCommerceRepository.kt`, `data/repositories/OfflineFirstCommerceRepository.kt`, `feature/checkout/CheckoutViewModel.kt`
- **Bug 1 fix:** both listing-existence checks now throw a named-item error instead of a bare `Listing {id} not found`.
- **Shop-mismatch fix:** both sites verify `deliveryListing.shopId === shopId`, threaded through the full repository chain; `CheckoutViewModel` restructured to source `shopId` reactively from the cart via `_cartShopId` + `flatMapLatest`.
- **Verification:** `tsc --noEmit` PASS, `npm run build` PASS, `gradlew.bat assembleDevDebug` PASS — confirmed as a genuine full recompile (8m 58s, 130 tasks executed).
- **Explicitly not done:** fallback-ordering gap, `SELLER_TRANSITIONS` adversarial testing — see Section 5.
- **Status:** implemented, build-verified. NOT committed, NOT deployed.

### Batch 2 — Bug 2 Fix: Checkout Fee Recalculation State (2026-09-15)
- **Classification:** AMBER (client-only logic change to presentation state)
- **Files:** `feature/checkout/src/main/java/com/swiftshop/feature/checkout/CheckoutViewModel.kt`, `feature/checkout/src/main/java/com/swiftshop/feature/checkout/CheckoutScreen.kt`
- **Verification:** `analyze_file` semantic check PASS for both modified files. `gradlew.bat` build attempt initially reported an environment error, later confirmed to be a PowerShell invocation issue (missing `.\` prefix), not a real build failure — reconfirmed passing in Batch 3.
- **Explicitly not done:** Live device runtime verification.
- **Status:** Implemented and build-verified (confirmed in Batch 3's full recompile). NOT committed, NOT deployed.

### Batch 1 — Delivery Response vertical (W2.4 B6/B7): merchant accept/decline
- **Classification:** AMBER+ (authority-sensitive: changes ownership/status
  of an order-linked delivery request, but no financial/inventory/escrow
  contact)
- **Files:** `functions/src/logistics.ts` (new `respondToDeliveryRequest`
  callable), `domain/delivery/DeliveryRepository.kt` +
  `DeliveryUseCases.kt` (new interface methods), `data/firebase/`
  `FirebaseDeliveryRepository.kt` (implementation), new
  `feature/delivery/IncomingDeliveryRequestsViewModel.kt` +
  `IncomingDeliveryRequestsScreen.kt`, `Navigation.kt` + `Screen.kt` (new
  route), `AppModule.kt` (DI wiring), `OrderScreens.kt` (seller-mode entry
  point icon)
- **Correction applied mid-batch:** an admin bypass
  (`auth.token.admin === true`) was present in the first draft of
  `respondToDeliveryRequest` without authorization; removed — the callable
  now authorizes strictly via `merchantId` ownership, no role model.
- **Verification:** `tsc --noEmit` PASS, `npm run build` PASS,
  `gradlew.bat assembleDevDebug` PASS (confirmed on the actual local
  machine, not a sandbox), `git diff --check` PASS. Static adversarial
  trace covered: double-accept, accept-after-decline, decline-after-accept,
  accept-past-expiry, unauthorized-merchant — all fail closed via the
  transaction's status/ownership/expiry checks.
- **Explicitly not done:** FCM notification to the merchant when a request
  arrives (no FCM exists in the project at all), linking an ACCEPTED
  request into `deliveryRoutes`/payment (explicitly deferred), a real
  two-account live runtime test (build passed; live two-device accept/
  decline test not yet performed).
- **Status:** implemented, built, present in the working tree. Commit
  status unclear — see Incident 1.

---

## 7. HOW TO USE THIS FILE (for any agent, including Gemini, picking up autonomously)

1. Read Section 0 and Section 2 first. If any RED file you're about to
   touch isn't in a clean, reviewed state per the ledger, stop.
2. Read Section 3 for known open bugs before assuming you've found a new
   one — check whether it's already diagnosed here.
3. Pick your next unit of work from Section 5's "AUTHORIZED / IN PROGRESS"
   only. Do not implement anything still sitting in "PROPOSED / AWAITING
   REVIEW" — that list exists specifically to prevent silent RED-boundary
   crossings like Incident 1.
4. When you finish a unit of work: add a new entry to Section 6 (never
   overwrite prior entries), update Section 2's ledger for any RED file you
   touched, and move your item from Section 5 to a "done" state or into
   Section 3 if you found a new bug instead.
5. Do not commit or deploy anything without an explicit, current-session
   human instruction to do so — a past session's authorization does not
   carry forward, and this file is not itself an authorization to commit.
6. If you cannot verify a build result (e.g. no Android SDK / no network
   access in your execution environment), say so explicitly in your report
   and in this file. Do not report a build status you did not actually
   observe.

**A note on what this file can and can't do:** it removes the "nobody had
the context" failure mode. It does not remove the need for a human to
actually read diffs before they're committed or deployed — no document
enforces that, a human reviewing the diff does. Treat this as shared
memory across sessions and agents, not as a substitute for the review step
itself.
