# SWIFT — CANONICAL IMPLEMENTATION PROTOCOL

**Status:** Authoritative implementation workflow for the Canonical Platform migration  
**Applies to:** Blocks 0–12, implementation branches, AI agents, human contributors, and release candidates  
**Repository:** swiftsupplyonline-business/swift-shop  
**Master architecture contract:** docs/canonical/CANONICAL_PLATFORM_MIGRATION.md  
**Audit contract:** docs/canonical/CANONICAL_AUDIT_PROTOCOL.md

## 1. Purpose

This document defines how Swift changes are planned, implemented, verified, and accepted.

The Migration document defines **what Swift is becoming**. The Audit Protocol defines **how repository reality is examined**. This document defines **how an implementation moves from an audited problem to an accepted repository change**.

A completely new agent must be able to enter the repository, read the canonical documents, understand the assigned scope, audit the current state, implement against the canonical contract, and produce acceptance evidence without relying on undocumented conversation history.

Agents are implementation tools, not architectural authorities.

## 2. Non-negotiable workflow

~~~text
READ CONTRACT
    ↓
AUDIT CURRENT STATE
    ↓
IDENTIFY GAP
    ↓
CHECKPOINT A — AUDIT GATE
    ↓
WRITE IMPLEMENTATION PLAN
    ↓
CHECKPOINT B — PLAN GATE
    ↓
IMPLEMENT SURGICALLY
    ↓
SELF-VERIFY
    ↓
ADVERSARIAL VERIFY
    ↓
DIFF AUDIT
    ↓
CHECKPOINT C — ACCEPTANCE GATE
    ↓
UPDATE DOCUMENTATION
    ↓
ACCEPT / REMEDIATE
~~~

Never begin by changing code.

## 3. Required reading order

Every new agent must read:

1. docs/canonical/CANONICAL_PLATFORM_MIGRATION.md
2. docs/canonical/CANONICAL_PLATFORM_CONTRACT.md
3. docs/canonical/AUTHORITY_MATRIX.md
4. docs/canonical/CANONICAL_AUDIT_PROTOCOL.md
5. This document
6. The relevant BLOCK_*_AUDIT.md
7. The latest applicable AUDIT_RUN_*.md

Then inspect the actual repository at the specified target branch/ref.

Documentation is a contract and evidence source, not proof that implementation matches the contract.

Always compare:

~~~text
DOCUMENTED INTENT
      vs
ACTUAL REPOSITORY
~~~

If they disagree, report the discrepancy before implementing.

## 4. Task intake

Every implementation task must define:

| Field | Required |
|---|---|
| Target branch | Yes |
| Target block | Yes |
| Objective | Yes |
| Problem / audit finding | Yes |
| Canonical contract | Yes |
| Scope | Yes |
| Forbidden changes | Yes |
| Acceptance criteria | Yes |
| Verification requirements | Yes |
| Compatibility requirements | Yes |
| Evidence required | Yes |

If these are not supplied, derive them from the canonical documents and current audit evidence where possible. Do not invent architectural requirements.

## 5. Phase 0 — ORIENT

Establish:

- repository
- target branch and commit
- base branch/commit
- target canonical block
- canonical domain authority
- entities involved
- canonical functions and states
- consumers
- legacy components
- upstream/downstream dependencies
- Firestore collections
- callable/API contracts
- security rules
- scheduled/background functions
- tests
- Android/Web/Admin consumers

Produce:

~~~text
TARGET:
BLOCK:
BRANCH:
BASE:

CANONICAL AUTHORITY:
AFFECTED ENTITIES:
AFFECTED FUNCTIONS:
AFFECTED CONSUMERS:

UPSTREAM:
DOWNSTREAM:
LEGACY DEPENDENCIES:

FORBIDDEN CHANGES:
~~~

## 6. Phase 1 — AUDIT

Before implementation, apply the Canonical Audit Protocol:

1. Architecture
2. Authority
3. Flow
4. Mutation
5. State
6. Consumer
7. Legacy
8. Branch/diff
9. Verification

Classify relevant components as:

- AUTHORITATIVE
- DUPLICATE
- LEGACY
- ORPHANED
- BROKEN
- MISSING

Never accept a previous agent's completion claim as audit evidence.

## 7. CHECKPOINT A — AUDIT GATE

No implementation begins until the diagnosis is established.

The agent must report:

- Current-state finding
- Canonical target
- Affected flows
- Affected mutations
- Affected state transitions
- Affected consumers
- Legacy dependencies
- Missing verification

Decision must be one of:

- **PASS — implementation may proceed**
- **BLOCKED — required evidence unavailable**
- **REMEDIATE — broader architectural issue discovered**

Do not silently move from audit into implementation when a broader architectural issue has been discovered.

## 8. Phase 2 — IMPLEMENTATION PLAN

After Checkpoint A, create a concrete plan identifying:

~~~text
FILES
FUNCTIONS
CLASSES / MODULES
CONSUMERS
DATABASE / STATE EFFECTS
TESTS
DOCUMENTATION
~~~

For every change state:

- **Change:** what changes?
- **Reason:** which audited defect requires it?
- **Authority:** which canonical authority owns it afterward?
- **Preservation:** what must remain unchanged?
- **Migration:** which consumers/legacy paths are affected?
- **Verification:** what proves it?

## 9. Scope control

Implementation must be surgical.

Allowed:

- files required by the approved plan;
- directly coupled code required to compile;
- tests required to validate changed behavior;
- documentation required to keep the canonical record truthful.

Do not silently:

- redesign unrelated domains;
- change earlier canonical contracts;
- introduce a second authority;
- rewrite unrelated UI;
- modify unrelated financial behavior;
- delete legacy consumers before migration;
- broaden scope because an adjacent issue is interesting.

If a necessary architectural change outside scope is discovered:

~~~text
STOP
 ↓
DOCUMENT FINDING
 ↓
UPDATE AUDIT
 ↓
REASSESS SCOPE
 ↓
NEW CHECKPOINT A/B IF REQUIRED
~~~

Do not hide architectural scope expansion inside a feature commit.

## 10. CHECKPOINT B — PLAN GATE

Before implementation verify:

- [ ] target authority is correct
- [ ] scope is explicit
- [ ] dependencies are known
- [ ] consumers are known
- [ ] legacy compatibility is understood
- [ ] forbidden changes are explicit
- [ ] tests are defined
- [ ] build verification is defined
- [ ] adversarial verification is defined
- [ ] rollback/remediation implications are understood

Decision:

**PASS — implement**

or

**BLOCK — revise plan**

## 11. Phase 3 — IMPLEMENT

Implement only against the approved plan.

All authoritative mutations must terminate at the canonical domain authority:

~~~text
Android
Web
Admin
  ↓
Canonical Domain Authority
  ↓
Firestore
~~~

Do not create a client-side duplicate because it is convenient.

Temporary compatibility is allowed only when it is necessary, documented, does not create a competing authority, and has an explicit removal condition.

## 12. Implementation invariants

Preserve applicable canonical invariants.

### Architecture

- one authoritative backend per domain;
- entities retain canonical meaning;
- Purchase remains distinct from Delivery;
- Listing remains distinct from Order;
- Delivery Request remains distinct from Fulfillment Job.

### Security

- clients cannot forge server-owned fields;
- ownership is validated server-side;
- callable authentication is enforced;
- authorization is enforced at authoritative mutation.

### Commerce

- authoritative price comes from backend;
- inventory cannot become negative;
- reservation/commit/release remain coherent;
- payment and order state cannot silently diverge.

### Fulfillment

- canonical state vocabulary is used;
- transitions are validated;
- provider/driver authorization is enforced;
- delivery completion cannot be forged.

### Migration

- legacy code is not deleted before consumer migration;
- compatibility paths are bounded and documented;
- new code must not expand legacy architecture.

## 13. Phase 4 — SELF-VERIFICATION

Use the applicable verification ladder.

### Static

- type-check
- syntax-check
- lint
- forbidden-reference search
- canonical-reference search
- mutation-authority search

### Unit

Run focused tests for changed behavior.

### Integration

Run emulator-backed tests where required.

### Platform

Run applicable Functions, Android, Web, and Admin builds/tests.

### Evidence status

Use only:

- 🟢 VERIFIED
- 🟡 PARTIAL
- 🔴 FAILED
- ⚪ NOT RUN
- 🔵 LEGACY
- 🟣 BLOCKED

Never mark an unrun check VERIFIED.

## 14. Mutation verification

For every changed authoritative operation, prove:

~~~text
ENTRY POINT
    ↓
CALLER
    ↓
CANONICAL FUNCTION
    ↓
PRECONDITIONS
    ↓
DATABASE MUTATION
    ↓
SIDE EFFECTS
    ↓
RESULT STATE
~~~

Record exact fields affected, especially ownership, server-owned listing fields, inventory, payment, order, delivery, fulfillment, and settlement fields.

## 15. Phase 5 — ADVERSARIAL VERIFICATION

Attempt to break the implementation.

Applicable cases include:

### Authorization
Wrong user, seller, buyer, provider, driver, or role.

### Concurrency
Duplicate request, double submission, simultaneous purchase, reservation race, duplicate payment callback, duplicate fulfillment.

### State
Invalid/repeated transition, stale state, cancelled entity, terminal entity mutation.

### Data integrity
Negative/zero quantity, manipulated price/seller/inventory, forged server-owned fields.

### Failure
Payment failure, reservation expiry, delivery failure, cancellation, partial operation, retry after failure.

### Routes
Malformed slug, nonexistent entity, wrong entity type, archived/unavailable entity, forged parameters.

If an adversarial case fails, the implementation is not accepted until fixed or explicitly classified as an approved limitation.

## 16. Phase 6 — DIFF AUDIT

Compare:

~~~text
BASE
 ↓
DIFF
 ↓
FILES
 ↓
FUNCTIONS
 ↓
BEHAVIOR
 ↓
TESTS
~~~

Answer:

1. Did every intended change occur?
2. Did any intended change fail to occur?
3. Did unrelated files change?
4. Did behavior change outside scope?
5. Did implementation introduce a duplicate authority?
6. Did it introduce new legacy references?
7. Do tests cover changed behavior?
8. Does documentation still describe reality?

**The diff is the source of truth for what was implemented.**

## 17. CHECKPOINT C — ACCEPTANCE GATE

A change may be marked **IMPLEMENTED** only when:

- [ ] implementation matches approved plan
- [ ] canonical authority is preserved
- [ ] mutation authority is verified
- [ ] affected flows are verified
- [ ] state transitions are verified
- [ ] consumers are verified
- [ ] required tests pass
- [ ] required builds pass
- [ ] adversarial verification is performed
- [ ] legacy impact is documented
- [ ] actual diff is reviewed
- [ ] remaining limitations are documented

Classify the result:

### ACCEPTED
All required evidence exists and passes.

### IMPLEMENTED — NOT YET ACCEPTED
Code is present, but required verification or consumer migration remains.

### REMEDIATE
Known defect remains.

### BLOCKED
Required verification could not execute because a dependency/environment was unavailable.

Never use “100% complete” as a substitute.

## 18. Documentation synchronization

After implementation, update applicable canonical documentation when repository reality changes:

- block audit
- authority matrix
- migration roadmap
- state definitions
- consumer matrix
- legacy matrix
- verification matrix
- audit run

Architectural disagreement between code and documentation is itself a migration defect unless explicitly documented as temporary.

## 19. Commit discipline

Implementation commits should be scoped, attributable to one logical change, easy to diff, and easy to revert.

Preferred sequence:

~~~text
audit evidence
    ↓
implementation commit
    ↓
verification
    ↓
documentation update
~~~

Avoid unrelated cleanup in canonical migration commits.

## 20. Agent completion report

Every implementation agent must finish with:

~~~text
# IMPLEMENTATION REPORT

## Target
Block:
Branch:
Base:
Commit:

## Audit Finding
What was wrong:

## Canonical Target
What authority should own it:

## Changes
- file:
- function:
- behavior:

## Preserved
What was deliberately not changed:

## Consumers
Migrated:
Remaining:

## Legacy
Retained:
Reason:
Removal condition:

## Verification
Static:
Unit:
Integration:
Build:
Adversarial:
Diff:

## Status
ACCEPTED / IMPLEMENTED — NOT YET ACCEPTED / REMEDIATE / BLOCKED

## Remaining Work
- ...

## Evidence
Exact files, tests, commands, commits, and relevant outputs.
~~~

## 21. What agents must never do

Agents must not:

- start implementation without inspecting current repository;
- trust an old audit without checking current code;
- trust another agent's “complete” claim;
- treat file existence as proof of correctness;
- create duplicate mutation authorities;
- silently redefine canonical entities or states;
- delete legacy consumers before migration;
- claim a test passed when it was not run;
- claim emulator verification without emulator execution;
- claim Android verification without an Android build/test;
- claim end-to-end verification from static inspection alone;
- hide scope expansion inside an unrelated change;
- rewrite unrelated modules for convenience;
- declare acceptance when required evidence is missing.

## 22. Handling discoveries during implementation

Classify discoveries:

- **Type A — Direct implementation defect:** fix within scope.
- **Type B — Directly coupled defect:** expand narrowly, document why.
- **Type C — Architectural defect:** stop and return to audit.
- **Type D — Unrelated defect:** record as separate remaining work.
- **Type E — Legacy dependency:** retain, classify, and document removal condition.

This prevents uncontrolled refactoring.

## 23. Multi-agent handoff protocol

Before handing work to another agent, leave:

1. current branch;
2. commit SHA;
3. audit report;
4. implementation plan;
5. files changed;
6. tests run;
7. tests not run;
8. known defects;
9. remaining consumers;
10. next checkpoint.

The receiving agent must independently verify repository state.

## 24. Block-level workflow

~~~text
BLOCK START
    ↓
READ CANONICAL CONTRACT
    ↓
READ BLOCK AUDIT
    ↓
AUDIT ACTUAL REPOSITORY
    ↓
CHECKPOINT A
    ↓
MAP IMPLEMENTATION
    ↓
CHECKPOINT B
    ↓
IMPLEMENT
    ↓
STATIC VERIFICATION
    ↓
UNIT VERIFICATION
    ↓
INTEGRATION VERIFICATION
    ↓
PLATFORM BUILD
    ↓
ADVERSARIAL VERIFICATION
    ↓
DIFF AUDIT
    ↓
CHECKPOINT C
    ↓
UPDATE BLOCK DOCUMENTATION
    ↓
BLOCK ACCEPTED
~~~

A later block must not conceal an unresolved authority defect in an earlier block.

## 25. Cross-block dependency rule

When Block N depends on Block N-1:

~~~text
BLOCK N-1
    ↓
AUTHORITY VERIFIED
    ↓
CONTRACT STABLE
    ↓
CONSUMER CONTRACT KNOWN
    ↓
BLOCK N IMPLEMENTATION
~~~

If Block N discovers that Block N-1 authority is incorrect, do not build a workaround around it. Return to the earlier block and remediate the canonical authority.

## 26. Legacy migration rule

Legacy removal follows:

~~~text
FREEZE
 ↓
MAP
 ↓
MIGRATE
 ↓
VERIFY
 ↓
REMOVE CONSUMERS
 ↓
DELETE LEGACY
 ↓
VERIFY ZERO REFERENCES
~~~

Implementation agents may delete legacy only when consumer and compatibility evidence exists. Otherwise retain, classify, and document the removal condition.

## 27. Release / main-branch rule

No canonical branch becomes new main merely because implementation blocks are complete.

Before main transition:

- Blocks 0–12 pass applicable acceptance gates;
- critical flows are verified;
- security/authorization is verified;
- cross-platform consumers agree on canonical semantics;
- legacy references are zero or explicitly approved;
- final adversarial verification is complete;
- final diff/branch audit is complete.

## 28. Definition of implementation done

~~~text
CORRECT ARCHITECTURE
        +
CORRECT AUTHORITY
        +
CORRECT FLOW
        +
CORRECT MUTATIONS
        +
CORRECT STATES
        +
CORRECT CONSUMERS
        +
VERIFICATION EVIDENCE
        +
DIFF MATCH
        =
ACCEPTABLE IMPLEMENTATION
~~~

A green compiler is one piece of evidence, not the definition of done.

## 29. Relationship to the canonical documents

~~~text
CANONICAL_PLATFORM_MIGRATION.md
        │
        │ WHAT
        ▼
CANONICAL_PLATFORM_CONTRACT.md
        │
        │ WHAT THE SYSTEM MEANS
        ▼
AUTHORITY_MATRIX.md
        │
        │ WHO OWNS IT
        ▼
CANONICAL_AUDIT_PROTOCOL.md
        │
        │ HOW TO AUDIT IT
        ▼
CANONICAL_IMPLEMENTATION_PROTOCOL.md
        │
        │ HOW TO CHANGE IT
        ▼
BLOCK AUDIT / IMPLEMENTATION
        │
        ▼
VERIFICATION
        │
        ▼
ACCEPTANCE
        │
        ▼
NEXT BLOCK
~~~

## 30. Golden rule

> **Audit before implementation. Plan before modification. Verify behavior, not just compilation. Attack the implementation before accepting it. Audit the actual diff. Never confuse “implemented” with “accepted.”**

The canonical repository is the source of truth.

The canonical documents define the intended truth.

The acceptance gate exists to prove that the two have converged.
