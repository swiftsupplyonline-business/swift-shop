# Swift Supply: Launch Gates (go/no-go)

Status date: 15 October 2026
Branch: `flight-reward-hardening`, based on Jules remediation commit `84aadf48b2557d7a90d3b37bf30024c0666ca911`
Verdict: **NOT YET LAUNCH-CERTIFIED.** The Flight reward logic is now fixed and unit-tested. The commerce journey, release parity and production operations are still unproven.

Everything below is marked as one of: **Verified** (run in this pass, evidence given), **Not run** (written but could not be executed here), or **Open** (needs work or an owner decision).

---

## 1. What this branch changes

| Change | File |
| --- | --- |
| `flight.ts` rewritten to the v2 callable API so it compiles (it did not, see 2.1) | `functions/src/flight.ts` |
| Sale reward requires an order that is **paid, delivered and settled**, and a real buyer/seller pair | `functions/src/flight.ts` |
| Reward event ids are **scoped to the user** (`{uid}__{type}__{key}`); old shared-key events by other users no longer block anyone, and the same user's old events still count as already rewarded | `functions/src/flight.ts` |
| FOLLOW / NEW_FOLLOWER verify **which side the caller is on**, reject self-follows and missing accounts, and dedupe on the follower-followed **pair**, not the document id | `functions/src/flight.ts` |
| INVITE and SHARE_SMART_LINK **fail closed** (no server record exists to verify them) | `functions/src/flight.ts` |
| Per-type `maxMultiplier` is now enforced (it was ignored) | `functions/src/flight.ts` |
| Suspended accounts cannot earn or redeem (existing `assertAccountActive`) | `functions/src/flight.ts` |
| Redemption refuses to overwrite an existing history record, and marks the claim `fulfillmentStatus: "PENDING"` so a recorded claim is not mistaken for a payout | `functions/src/flight.ts` |
| `follows` create rule: one document per pair with id `{followerId}_{followedId}` (what Android already writes), no self-follow | `docs/firestore.rules` |
| 62 adversarial unit tests (forged events, wrong users, duplicates, concurrency, incomplete orders, double redemption) | `functions/src/test/flight.rewards.unit.test.ts` |
| Emulator-backed rules tests for `follows` | `functions/src/test/follow-rules.test.ts` |

## 2. Findings beyond the original audit

1. **The Flight backend did not compile on the Jules branch.** `flight.ts` used the old v1 callable API (`functions.https.onCall`, `CallableContext`) against `firebase-functions` v7. `tsc` failed and the Flight test suite could not run. That branch could not have produced a deployable Functions build. *Verified: baseline `tsc` errors, then clean after the fix.*
2. **Multiplier caps were ignored.** The UI advertises Share Smart Link at 40 to 160 points (40 x 4), but the code applied the altitude band alone, so the top altitude paid 200. Follows paid up to 50 instead of 30.
3. **Follow farming was trivial.** Clients could create `follows/{any-id}` documents freely, and each distinct id was a new reward event. Follower counters were also inflated by duplicate documents.
4. **No client in this branch calls `processFlightEvent`.** The web app calls `getFlightState`, `feedBird` and `claimFlightRedemption` only. The Android app has a Flight tab (`HomeScreen.kt`) but I found no code in it that calls any Flight function. So points cannot currently be earned from either app on this branch, and the Android Flight tab is not backed by this backend yet.

## 3. Evidence from this pass

| Check | Result |
| --- | --- |
| `tsc --noEmit` on `functions/` | **Verified**: clean (baseline failed with 3 errors in `flight.ts`) |
| Unit tests, non-emulator suites | **Verified**: 16 suites, 146 tests passing (baseline: 14 passing, 1 failing to compile) |
| Do the new tests catch the original bugs? | **Verified** by mutation: re-introducing each of seven bugs (sale check removed, unscoped event id, cap ignored, follow keyed by doc id, follow owner check removed, invite/share allowed, redemption guard removed) made the suite fail |
| Rules tests (`follow-rules`, `security-rules`) | **Not run**: the Firestore emulator jar could not be downloaded in this sandbox (network allowlist). Run with `firebase emulators:exec --only firestore "npx jest follow-rules security-rules"` |
| Emulator-backed commerce/logistics/notifications suites | **Not run**, same reason |
| Android build, web deploy, Functions deploy, live purchase | **Not run** |

## 4. Decisions needed from the owner

1. **Which branch is the release baseline?** Flight exists only on the Jules branch; the default branch (`canonical-platform-tree-2026-09-29`, `5f81058`) has no `flight.ts`. Pick one, merge deliberately, and retire the other.
2. **M200 funding and payout.** Redemption records a claim only. No wallet credit or payout is made. Decide how M200 is funded and fulfilled, and build and test that before any real-money promise.
3. **Anti-farming limits.** Unlimited posts or listings can each earn points. Needs owner-set numbers: daily caps per reward type, a minimum order value for SUCCESSFUL_SALE, and a policy on alt accounts for NEW_FOLLOWER.
4. **Who earns a sale reward.** Today both buyer and seller earn it, once each. Confirm that is intended.
5. **Surplus points at redemption.** Points above 100,000 are discarded when the flight resets. Decide whether to carry them over.
6. **INVITE and SHARE_SMART_LINK.** Need a server-side record and eligibility rules before they can be enabled.

## 5. Remaining launch gates

### Release baseline
- [ ] Decision 4.1 made; one approved branch and commit SHA
- [ ] Deployed web hosting and Cloud Functions compared with that SHA
- [ ] APK build traced to that exact SHA
- [ ] Branch protection on the release branch: required review and passing checks
- [ ] CI runs `tsc` and the Functions tests on every push (no status checks were reported on the Jules commit)

### Commerce journey (real money)
- [ ] Purchase: totals, stock reservation, payment confirmation, order creation
- [ ] Payment failure: no false order, no duplicate charge, stock recovered
- [ ] Seller preparation: only permitted participants advance valid states
- [ ] Delivery: request, provider acceptance, fulfilment job
- [ ] Tracking visible only to authorised users
- [ ] Settlement: correct recipient, platform fee and provider share, exactly once
- [ ] Refunds and cancellation leave consistent financial records

### Flight
- [ ] Decisions 4.2 to 4.6 resolved
- [ ] Client wiring that calls `processFlightEvent` (or, better, server triggers on listing/post/order records)
- [ ] Rules tests run under the emulator
- [ ] Installed Android app: Flight tab, persistent points, duplicate prevention, redemption

### Web and Android parity
- [ ] Same test account shows the same listings, order states, delivery state and rewards on both

### Production operations
- [ ] Every callable checks ownership, role and valid state transition
- [ ] Payment callbacks verified; retries idempotent
- [ ] No production secrets in the app or in source
- [ ] Firebase project, region and function versions confirmed per environment
- [ ] Alerts for failed payments, unusual reward activity and settlement errors
- [ ] Backups, a tested restore, and a tested rollback path
