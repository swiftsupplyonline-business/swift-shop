# SWIFT — CANONICAL AUDIT PROTOCOL

**Status:** Authoritative audit workflow for the Canonical Platform migration  
**Applies to:** Blocks 0–12, every implementation branch, every agent, and every release candidate  
**Master contract:** `docs/canonical/CANONICAL_PLATFORM_MIGRATION.md`

---

## 1. Purpose

Swift must be audited at **two different levels** before a migration block can be accepted:

1. **Architecture / Block Audit** — proves that the intended canonical architecture exists and that responsibilities are assigned to the correct authorities.
2. **Flow Audit** — proves that real user and system flows actually travel through those authorities correctly from entry point to database mutation and resulting state.

A block is **not complete because its files exist, functions compile, or an agent reports completion**.

The required model is:

```
ARCHITECTURE AUDIT
       ↓
AUTHORITY AUDIT
       ↓
FLOW AUDIT
       ↓
STATE / MUTATION AUDIT
       ↓
CONSUMER AUDIT
       ↓
TEST / BUILD VERIFICATION
       ↓
ADVERSARIAL VERIFICATION
       ↓
DIFF AUDIT
       ↓
ACCEPT / REMEDIATE
```

---

## 2. The Core Rule

### Never confuse existence with correctness.

These are different questions:

| Question | Audit layer |
|---|---|
| Does the canonical component exist? | Architecture |
| Is it the authoritative component? | Authority |
| Does every consumer use it? | Consumer |
| Does the real flow reach it? | Flow |
| Does it perform the correct mutation? | Mutation |
| Does the state transition work? | State |
| Does it work under failure/edge cases? | Adversarial |
| Did the branch actually contain the claimed change? | Diff |

Example:

> `reserveInventory()` exists

does **not** prove inventory is canonical.

We must prove that **all authoritative reservation mutations** route through the canonical inventory engine and that no hidden writer bypasses it.

---

# 3. Audit Level 1 — Architecture / Block Audit

For every migration block, first establish the intended architecture.

Document:

- canonical modules
- canonical functions
- canonical entities
- canonical states
- canonical routes
- canonical client contracts
- server-owned fields
- allowed consumers
- legacy components
- dependencies on other blocks

### Classification

Every relevant component must be classified as exactly one of:

- **AUTHORITATIVE** — canonical owner of the responsibility.
- **DUPLICATE** — another implementation of an already-owned responsibility.
- **LEGACY** — intentionally retained old architecture awaiting migration/removal.
- **ORPHANED** — code exists but has no valid consumer or authority role.
- **BROKEN** — intended component exists but cannot correctly execute its contract.
- **MISSING** — required canonical component or contract does not exist.

Do not use “complete” as a substitute for these classifications.

---

# 4. Audit Level 2 — Authority Audit

For every domain, answer:

> **Who is allowed to mutate this state?**

The canonical model is:

```
Android ─┐
Web ─────┼──→ Canonical Domain Authority ──→ Firestore
Admin ───┘
```

Clients are consumers.

They must not independently author:

- authoritative pricing
- inventory quantities
- reservations
- seller ownership
- order state
- delivery state
- fulfillment state
- server-owned listing fields
- settlement/financial state

### Authority test

For each mutation:

```
Operation
  ↓
Who calls it?
  ↓
What function receives it?
  ↓
What function actually mutates data?
  ↓
Is that function the canonical authority?
  ↓
Can another path perform the same mutation?
```

If two independent paths can perform the same authoritative mutation, the domain is **not consolidated**.

---

# 5. Audit Level 3 — Flow Audit

Architecture tells us what should exist.

Flow auditing proves that it actually works.

Every critical user/system journey must be traced end-to-end.

## 5.1 Listing flow

```
Seller/Profile
 ↓
Shop
 ↓
Create Listing
 ↓
Listing Engine
 ↓
Validation
 ↓
Ownership
 ↓
Inventory
 ↓
Lifecycle
 ↓
Slug
 ↓
Smart Link
```

Verify:

- valid listing
- invalid listing
- unauthorized seller
- duplicate/invalid slug
- inventory initialization
- lifecycle state
- server-owned fields
- persistence
- returned DTO
- downstream smart link

## 5.2 Product purchase flow

```
Smart Link
 ↓
Listing
 ↓
Product UI
 ↓
Selection / Cart
 ↓
calculatePurchaseTotal
 ↓
createPurchaseOrder
 ↓
Inventory Reservation
 ↓
Payment
 ↓
Order
```

Verify:

- price calculation
- quantity
- stock availability
- reservation
- payment
- idempotency
- order creation
- failure rollback
- duplicate submission

## 5.3 Order flow

```
Purchase
 ↓
Order
 ↓
Inventory Reservation
 ↓
Payment
 ↓
Seller Preparation
 ↓
Ready for Fulfillment
```

Verify:

- order ownership
- payment state
- inventory state
- cancellation
- reservation release
- order state transitions
- seller/buyer visibility

## 5.4 Delivery flow

```
Order
 ↓
Delivery Options
 ↓
Delivery Request
 ↓
Provider
 ↓
Accept
 ↓
Fulfillment Job
 ↓
Driver
 ↓
Tracking
 ↓
Delivered
```

Verify:

- orderId linkage
- delivery options
- provider authorization
- acceptance
- job creation
- driver authorization
- pickup
- pickup confirmation
- transit
- delivery
- cancellation/failure
- settlement/completion

## 5.5 Smart-link flow

Product:

```
/s/<shop-slug>/<product-slug>
```

Delivery:

```
/d/<shop-slug>/<delivery-slug>
```

Verify:

- valid route
- malformed route
- nonexistent entity
- wrong entity type
- invalid slug
- archived listing
- unavailable listing
- forged parameters
- compatibility route behavior

---

# 6. Mutation Audit

A flow is not complete until its database mutations are traced.

For each operation document:

| Field | Required evidence |
|---|---|
| Entry point | Screen/API/trigger |
| Caller | Exact file/function |
| Callable/API | Exact endpoint |
| Authority | Canonical function/module |
| Mutation | Exact Firestore/document fields |
| Preconditions | Auth/ownership/state |
| Side effects | Events/notifications/payment/etc. |
| Result state | Expected canonical state |
| Failure behavior | Rollback/release/error |
| Duplicate path | Any competing writer? |

### Example

```
Expire reservation
 ↓
scheduled trigger
 ↓
expireReservations
 ↓
releaseInventory
 ↓
reservedQuantity decreases
 ↓
availableQuantity recalculates
 ↓
OUT_OF_STOCK state reconciles
```

If expiry instead directly modifies `reservedQuantity`, that is an authority violation even if the final numbers sometimes look correct.

---

# 7. State-Machine Audit

Every canonical state machine must be checked as a graph.

For each state:

- valid incoming transitions
- valid outgoing transitions
- actor allowed to perform transition
- backend function performing transition
- database mutation
- side effects
- terminal conditions
- failure conditions

Example fulfillment graph:

```
REQUESTED
   ↓
ASSIGNED
   ↓
AT_PICKUP
   ↓
PICKUP_CONFIRMED
   ↓
IN_TRANSIT
   ↓
DELIVERED
```

Exceptions:

```
REQUESTED ─→ CANCELLED / FAILED
ASSIGNED ─→ CANCELLED / FAILED
...
```

### State audit must catch

- unreachable states
- impossible transitions
- legacy state vocabulary
- transition validators that reject transitions used by callers
- client-controlled state changes
- missing terminal behavior
- missing failure recovery

The `PICKUP` → `AT_PICKUP` issue is exactly the type of defect this audit is designed to expose.

---

# 8. Consumer Audit

For every canonical function, identify every caller.

Example:

```
createPurchaseOrder
├── Android
├── Web
├── Admin?
└── Tests
```

Then identify every legacy caller:

```
createOrder
├── Android?
├── Web?
├── Tests
└── external/deployed clients?
```

The migration cannot declare a legacy function removable until:

1. current source consumers are migrated;
2. tests are migrated;
3. scheduled/background consumers are migrated;
4. compatibility requirements are addressed;
5. deployed-client risk is explicitly resolved;
6. zero-reference search passes;
7. adversarial verification passes.

---

# 9. Branch / Diff Audit

Agent reports are not evidence of repository state.

Every implementation branch must be compared against its intended base.

Minimum evidence:

```
BASE
 ↓
BRANCH DIFF
 ↓
FILES CHANGED
 ↓
FUNCTIONS CHANGED
 ↓
BEHAVIOR CHANGED
 ↓
TESTS ADDED/UPDATED
```

For every claimed fix, answer:

- Which commit?
- Which file?
- Which function?
- What behavior changed?
- What was deliberately not changed?
- What test proves it?
- What remains unverified?

Never infer implementation from an agent's narrative.

---

# 10. Verification Ladder

Use this order:

### Level A — Static

- type-check
- syntax-check
- lint where configured
- forbidden-reference search
- architecture/reference audit

### Level B — Unit

Run focused tests for the changed authority.

### Level C — Integration

Run emulator-backed tests where required.

Example:

```
firebase emulators:exec "npx jest"
```

### Level D — Platform build

- Functions build/type-check
- Android build
- Web build/syntax
- Admin build

### Level E — Adversarial

Attempt to break:

- authorization
- ownership
- inventory
- idempotency
- invalid state transitions
- duplicate requests
- stale data
- malformed routes
- unavailable inventory
- payment failure
- delivery failure
- cancellation
- concurrent operations

### Level F — Diff audit

Review the actual Git diff.

### Level G — Acceptance

Only after all applicable evidence exists.

---

# 11. Evidence Status

Use explicit verification markers.

| Marker | Meaning |
|---|---|
| 🟢 VERIFIED | Evidence exists and passed |
| 🟡 PARTIAL | Implemented but some verification/consumer work remains |
| 🔴 FAILED | Known defect |
| ⚪ NOT RUN | Required verification has not been performed |
| 🔵 LEGACY | Deliberately retained during migration |
| 🟣 BLOCKED | Cannot verify because a required environment/dependency is unavailable |

Never convert 🟡, ⚪, 🔵 or 🟣 into 🟢 merely because the code looks correct.

---

# 12. Block Completion Gate

A block may be called **IMPLEMENTED** only when:

- [ ] architecture exists
- [ ] authority is identified
- [ ] duplicate authorities are identified
- [ ] critical flows traced
- [ ] mutations traced
- [ ] state transitions verified
- [ ] all current consumers identified
- [ ] legacy consumers identified
- [ ] required migrations implemented
- [ ] tests updated
- [ ] applicable builds pass
- [ ] emulator/integration tests pass where required
- [ ] adversarial verification performed
- [ ] actual diff audited
- [ ] remaining limitations documented

### Important

**IMPLEMENTED ≠ ACCEPTED.**

A block can be implemented while still being:

- partially verified,
- waiting for consumer migration,
- waiting for legacy removal,
- waiting for emulator testing,
- waiting for Android build verification.

---

# 13. Canonical Audit Output

Every block audit should produce these artifacts:

```
BLOCK AUDIT
│
├── Architecture Map
├── Authority Matrix
├── Flow Matrix
├── Mutation Matrix
├── State Machine Map
├── Consumer Matrix
├── Legacy Matrix
├── Verification Matrix
└── Remaining Work
```

The minimum useful summary is:

| Area | Status | Evidence | Remaining |
|---|---|---|---|
| Architecture | 🟢/🟡/🔴 | files/functions | gaps |
| Authority | 🟢/🟡/🔴 | mutation tracing | duplicates |
| Flows | 🟢/🟡/🔴 | end-to-end trace | broken paths |
| States | 🟢/🟡/🔴 | transition audit | unreachable/invalid |
| Consumers | 🟢/🟡/🔴 | caller search | legacy callers |
| Tests | 🟢/🟡/🔴 | test evidence | missing tests |
| Build | 🟢/🟡/🔴 | build output | unavailable environments |
| Legacy | 🟢/🔵/🔴 | reference audit | removals |

---

# 14. Agent Operating Instruction

Any AI agent working on Swift must follow:

```
READ CANONICAL CONTRACT
        ↓
READ THIS AUDIT PROTOCOL
        ↓
AUDIT CURRENT ARCHITECTURE
        ↓
TRACE CURRENT FLOWS
        ↓
IDENTIFY AUTHORITIES / DUPLICATES
        ↓
PROPOSE CHANGES
        ↓
IMPLEMENT SURGICALLY
        ↓
TEST
        ↓
ADVERSARIAL VERIFY
        ↓
DIFF AUDIT
        ↓
REPORT EVIDENCE
```

Agents must not:

- declare a block complete because files exist;
- delete legacy code before consumer migration;
- introduce a second authority;
- modify an earlier canonical contract silently;
- change client behavior to accommodate a legacy backend when the canonical backend contract is correct;
- claim tests passed when they were not run;
- claim Android verification without an Android build;
- claim emulator verification without emulator execution.

---

# 15. Relationship to the Migration Roadmap

The existing block roadmap answers:

> **WHAT are we migrating?**

This protocol answers:

> **HOW do we prove each migration actually works?**

Therefore:

```
CANONICAL_PLATFORM_MIGRATION.md
        │
        │ WHAT
        ▼
Migration Blocks
        │
        ▼
CANONICAL_AUDIT_PROTOCOL.md
        │
        │ HOW TO VERIFY
        ▼
Architecture Audit
        ↓
Flow Audit
        ↓
Mutation / State Audit
        ↓
Consumer Audit
        ↓
Verification
        ↓
Acceptance
```

---

# 16. The Golden Rule

> **Audit the architecture. Then audit the flows. Then audit the mutations and state transitions. Then verify the consumers. Then test. Then audit the diff.**

This prevents the most dangerous false-positive:

```
"THE CANONICAL CODE EXISTS"
             ≠
"THE PLATFORM USES THE CANONICAL CODE CORRECTLY"
```

Swift is accepted only when both are proven.
