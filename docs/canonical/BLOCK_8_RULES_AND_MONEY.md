# Block 8: Firestore rules + money paths (branch block-8-rules-money)

Verified here: TypeScript compiles; 21 emulator-free unit tests pass. NOT run: emulator rules tests, finance flows against MoPay.

## Money-path bugs fixed (functions/src/finance.ts, advertising.ts, commerce.ts, mopay.ts)
| # | Severity | Problem | Fix |
|---|---|---|---|
| 1 | HIGH | Withdrawal minimum only applied when currency == "LSL"; any other currency string bypassed it and debited the LSL wallet | Only LSL accepted (`parseAmount`) |
| 2 | HIGH | Amounts accepted fractional / unsafe numbers | Positive safe integers only |
| 3 | HIGH | Ad campaign budget came from the client-created draft; a negative budget passed the balance check and would credit the wallet on activation | Server rejects non-integer/negative budget; rules force budget >= 0 and zero metrics |
| 4 | HIGH | `confirmDeposit` (success and failure paths) subtracted the deposit from `pendingBalance`, which only holds in-flight withdrawals. A deposit could consume a withdrawal's pending funds and make `confirmWithdrawal` fail with an integrity error | Deposits never touch pending |
| 5 | MED | Re-calling `initiateDeposit` with the same key opened a SECOND payable MoPay session; paying it credited once but charged twice | Replay returns the existing session; non-PENDING deposits are refused; payment URL stored |
| 6 | MED | Idempotency keys were global document ids (withdraw/deposit/P2P/purchase). Another user could reuse a key and read back someone's transaction/order id, or pre-claim a key | Keys namespaced by uid + operation, format-validated (UUID and web `web_..` keys still valid) |
| 7 | MED | Suspended accounts could move money | `assertAccountActive` on deposit/withdraw/P2P |
| 8 | LOW | Unvalidated destination/provider/gateway/phone/toUserId types | Validated, length-bounded |
| 9 | LOW | MoPay calls had no timeout; sessionId interpolated into URL unencoded | 15s timeout, encodeURIComponent |

## Firestore rules fixed (docs/firestore.rules)
- profiles: denylist -> allowlist (displayName, displayName_lowercase, bio, location, avatarUrl, coverUrl; exactly what Android writes).
- users: owner could set `isVerified` on own doc; now protected (also `uid`).
- conversations: any participant could rewrite `participantIds`; now limited to lastMessage, lastMessageAt, unreadCounts.
- messages: any signed-in user could flip `isRead` on any message; now participants only.
- advertisingCampaigns create: key allowlist, zeroed metrics, non-negative integer budget.
- preferences: collection had no rule (every Android preference read/write was denied); owner-only rule added.

## Still open (found, not changed)
- `messages` delete is denied by rules but Android `deleteMessage` deletes directly -> feature fails. Needs a `deleteMessage` callable or a sender-only delete rule (product decision).
- `follows` / `posts` delete and counters: counters rely on triggers; not audited.
- `isActive()` helper defaults to permissive when claims/user doc are missing ("DEV" comment). Tighten before production.
- Missing feature: admin cannot REJECT/fail a pending withdrawal and release it back to available (only `confirmWithdrawal` exists).
- P2P fee floors to 0 for amounts under 67 minor units; no daily/withdrawal limits; no max amount. Business decisions.
- Order payment/refund/settlement code in commerce.ts (wallet debit at purchase, refund on cancel, seller payout) was only skimmed.
- `payPreview.ts` (public /pay/** page) not audited for HTML injection.
- Client-supplied `idempotencyKey` in the legacy `createOrder` and in `activateCampaign` are still global.
- Money-path tests against the emulator do not exist yet.

## Deploy notes
Deploy functions first, then rules. Old app builds keep working: payload shapes are unchanged, only stricter.
