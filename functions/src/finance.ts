import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { MopayClient, MOPAY_API_KEY } from "./mopay";
import { assertAccountActive } from "./accountGuard";
import { parseAmount, idempotencyDocId, requireText, requireUid, MoneyValidationError } from "./moneyValidation";

function badInput(e: unknown): never {
    if (e instanceof MoneyValidationError) throw new HttpsError("invalid-argument", e.message);
    throw e;
}

/**
 * Initiates a withdrawal from the user's wallet to an external destination.
 */
export const initiateWithdrawal = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    let amount: { minorUnits: number; currency: string };
    let idempotencyKey: string, destination: string, provider: string, activeGateway: string;
    try {
        amount = parseAmount(request.data?.amount);
        idempotencyKey = idempotencyDocId(uid, "withdraw", request.data?.idempotencyKey);
        destination = requireText(request.data?.destination, "destination", 64);
        provider = requireText(request.data?.provider ?? "UNKNOWN", "provider", 40);
        activeGateway = requireText(request.data?.gateway ?? "MOPAY", "gateway", 20);
    } catch (e) { badInput(e); }

    if (amount.minorUnits < 20000) {
        throw new HttpsError("failed-precondition", "Minimum withdrawal amount is M200.00.");
    }

    await assertAccountActive(uid);
    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) return idempotencyDoc.data()?.transactionId;

            const walletRef = db.collection("wallets").doc(uid);
            const walletDoc = await transaction.get(walletRef);
            if (!walletDoc.exists) throw new Error("Wallet not found.");

            const walletData = walletDoc.data()!;
            const availableBalance = walletData.availableBalanceMinorUnits || 0;

            if (availableBalance < amount.minorUnits) throw new Error("Insufficient available balance.");

            const transactionId = db.collection("walletTransactions").doc().id;
            const ledgerEntryId = db.collection("ledgerEntries").doc().id;

            const newAvailable = availableBalance - amount.minorUnits;
            const newPending = (walletData.pendingBalanceMinorUnits || 0) + amount.minorUnits;

            transaction.set(db.collection("walletTransactions").doc(transactionId), {
                transactionId, userId: uid, type: "WITHDRAWAL",
                amountMinorUnits: amount.minorUnits, feeMinorUnits: 0,
                currency: amount.currency, status: "PENDING",
                description: `Withdrawal via ${activeGateway} (${provider}) to ${destination}`,
                gateway: activeGateway, provider,
                destinationAccount: destination, idempotencyKey,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.set(db.collection("ledgerEntries").doc(ledgerEntryId), {
                id: ledgerEntryId, transactionId, debitAccount: `user_${uid}`,
                creditAccount: "system_withdrawal_escrow", amountMinorUnits: amount.minorUnits,
                currency: amount.currency, reference: `WITHDRAW_${transactionId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(walletRef, {
                availableBalanceMinorUnits: newAvailable,
                pendingBalanceMinorUnits: newPending,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.set(idempotencyRef, { transactionId, userId: uid, createdAt: admin.firestore.FieldValue.serverTimestamp() });

            return transactionId;
        });
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Initiates a deposit into the user's wallet via MoPay.
 *
 * Creates a PENDING walletTransaction and opens a MoPay payment session.
 * Does NOT credit the wallet or write a ledger entry — that happens only
 * after confirmDeposit verifies the external payment server-side.
 *
 * Returns: { transactionId, paymentUrl, sessionId }
 */
export const initiateDeposit = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    let amount: { minorUnits: number; currency: string };
    let idempotencyKey: string, phoneNumber: string, provider: string, activeGateway: string;
    try {
        amount = parseAmount(request.data?.amount);
        idempotencyKey = idempotencyDocId(uid, "deposit", request.data?.idempotencyKey);
        phoneNumber = requireText(request.data?.phoneNumber, "phoneNumber", 20);
        provider = requireText(request.data?.provider ?? "UNKNOWN", "provider", 40);
        activeGateway = requireText(request.data?.gateway ?? "MOPAY", "gateway", 20);
    } catch (e) { badInput(e); }

    await assertAccountActive(uid);
    const db = admin.firestore();

    let transactionId = "";

    try {
        // Step 1: Create PENDING walletTransaction atomically with idempotency guard.
        // No wallet balance mutation. No ledger entry. Payment not yet verified.
        transactionId = await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) return idempotencyDoc.data()?.transactionId as string;

            const walletRef = db.collection("wallets").doc(uid);
            const walletDoc = await transaction.get(walletRef);
            if (!walletDoc.exists) throw new Error("Wallet not found.");

            const txId = db.collection("walletTransactions").doc().id;

            transaction.set(db.collection("walletTransactions").doc(txId), {
                transactionId: txId, userId: uid, type: "DEPOSIT",
                amountMinorUnits: amount.minorUnits, feeMinorUnits: 0,
                currency: amount.currency, status: "PENDING",
                description: `Deposit via ${activeGateway} (${provider}) from ${phoneNumber}`,
                gateway: activeGateway, provider: provider || "UNKNOWN",
                sourceAccount: phoneNumber, idempotencyKey,
                mopaySessionId: null,
                gatewayTransactionId: null,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.set(idempotencyRef, {
                transactionId: txId, userId: uid,
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            });

            return txId;
        });

        // Replay guard: a repeated call with the same idempotency key must NOT open a second MoPay
        // session. A second payable session for an already-processed reference would let the user pay
        // twice while being credited once (confirmDeposit is idempotent per transaction).
        const existing = (await db.collection("walletTransactions").doc(transactionId).get()).data()!;
        if (existing.status !== "PENDING") {
            throw new HttpsError("failed-precondition", `Deposit already ${String(existing.status).toLowerCase()}.`);
        }
        if (existing.mopaySessionId) {
            return { transactionId, paymentUrl: existing.mopayPaymentUrl ?? "", sessionId: existing.mopaySessionId };
        }

        // Step 2: Open MoPay payment session (outside transaction — external API call).
        const mopayRequest = {
            amount: amount.minorUnits / 100,
            reference: transactionId,
            redirectUrl: "swiftshop://wallet/verify",
            description: `Wallet deposit of M${(amount.minorUnits / 100).toFixed(2)}`,
            notificationPhoneNumber: phoneNumber,
        };

        const mopayResponse = await MopayClient.initiatePaymentSession(mopayRequest);

        if (mopayResponse.success && mopayResponse.sessionId) {
            // Store sessionId on the walletTransaction for later verification lookup.
            await db.collection("walletTransactions").doc(transactionId).update({
                mopaySessionId: mopayResponse.sessionId,
                mopayPaymentUrl: mopayResponse.paymentUrl ?? null,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            return {
                transactionId,
                paymentUrl: mopayResponse.paymentUrl,
                sessionId: mopayResponse.sessionId
            };
        } else {
            // MoPay session creation failed — mark transaction FAILED.
            await db.collection("walletTransactions").doc(transactionId).update({
                status: "FAILED",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            throw new Error(mopayResponse.message || "Failed to initiate payment gateway");
        }

    } catch (error: any) {
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Verifies a MoPay deposit session and atomically credits the wallet on success.
 *
 * Server-authoritative: the client supplies only the sessionId from the deep-link
 * return. All amount, currency, and ownership checks are performed server-side.
 *
 * Idempotent: repeated calls with the same sessionId produce exactly one
 * wallet credit and one ledger entry.
 *
 * Financial flow on SUCCESS:
 *   system_deposit_clearing → user_{uid}   (ledger entry)
 *   pendingBalanceMinorUnits -= amount
 *   availableBalanceMinorUnits += amount
 *   walletTransaction.status → COMPLETED
 */
export const confirmDeposit = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    const { sessionId } = request.data;
    if (!sessionId) throw new HttpsError("invalid-argument", "Missing sessionId");

    const db = admin.firestore();

    try {
        // 1. Locate the walletTransaction by MoPay sessionId, scoped to this user.
        const txQuery = await db.collection("walletTransactions")
            .where("mopaySessionId", "==", sessionId)
            .where("userId", "==", uid)
            .limit(1)
            .get();

        if (txQuery.empty) throw new HttpsError("not-found", "Deposit transaction not found for this session.");

        const txDoc = txQuery.docs[0];
        const txData = txDoc.data();

        // Ownership double-check (defense in depth beyond the query filter).
        if (txData.userId !== uid) throw new HttpsError("permission-denied", "Deposit does not belong to this user.");

        // 2. Server-side MoPay verification — never trust client-supplied status.
        const mopaySession = await MopayClient.verifyPaymentSession(sessionId);
        if (!mopaySession) throw new HttpsError("unavailable", "Could not verify session with MoPay.");

        // 3. Reference integrity: MoPay reference must match our transactionId.
        if (mopaySession.reference !== txData.transactionId) {
            throw new HttpsError("failed-precondition", "Payment reference mismatch.");
        }

        // 4. Amount integrity: MoPay returns major units; convert to minor units.
        const mopayAmountMinor = Math.round(mopaySession.amount * 100);
        if (mopayAmountMinor !== txData.amountMinorUnits) {
            throw new HttpsError("failed-precondition",
                `Amount mismatch. Expected: ${txData.amountMinorUnits}, Got: ${mopayAmountMinor}`);
        }

        // 5. Handle by MoPay transaction status.
        if (mopaySession.transactionStatus === "SUCCESS") {
            // 6. Atomic financial confirmation — idempotent via status guard.
            await db.runTransaction(async (transaction) => {
                const freshTxDoc = await transaction.get(txDoc.ref);
                const freshTx = freshTxDoc.data()!;

                // Idempotency guard: if already COMPLETED, do nothing.
                if (freshTx.status === "COMPLETED") return;

                // Only process PENDING deposits — reject any other state.
                if (freshTx.status !== "PENDING") {
                    throw new Error(`Deposit is in state ${freshTx.status}, cannot confirm.`);
                }

                const walletRef = db.collection("wallets").doc(uid);
                const walletDoc = await transaction.get(walletRef);
                if (!walletDoc.exists) throw new Error("Wallet not found.");

                const walletData = walletDoc.data()!;
                const now = admin.firestore.Timestamp.now();
                const amountMinorUnits = freshTx.amountMinorUnits as number;

                // Credit wallet. initiateDeposit never adds to pendingBalance, so confirmation must not
                // subtract from it either: pendingBalance holds in-flight WITHDRAWALS, and eating into it
                // would make confirmWithdrawal fail with an "integrity issue" for an unrelated withdrawal.
                const newAvailable = (walletData.availableBalanceMinorUnits || 0) + amountMinorUnits;

                transaction.update(walletRef, {
                    availableBalanceMinorUnits: newAvailable,
                    updatedAt: now
                });

                // Immutable ledger entry: external MoPay clearing → user wallet.
                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    transactionId: txData.transactionId,
                    debitAccount: "system_deposit_clearing",
                    creditAccount: `user_${uid}`,
                    amountMinorUnits: amountMinorUnits,
                    currency: freshTx.currency || "LSL",
                    reference: `DEPOSIT_CONFIRM_${txData.transactionId}`,
                    gatewayTransactionId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                    timestamp: now
                });

                // Mark transaction COMPLETED.
                transaction.update(txDoc.ref, {
                    status: "COMPLETED",
                    gatewayTransactionId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                    completedAt: now,
                    updatedAt: now
                });
            });

            return { status: "SUCCESS", transactionId: txData.transactionId };

        } else if (mopaySession.transactionStatus === "FAILED" || mopaySession.transactionStatus === "CANCELLED") {
            // Terminal failure: mark FAILED, release pending balance.
            await db.runTransaction(async (transaction) => {
                const freshTxDoc = await transaction.get(txDoc.ref);
                const freshTx = freshTxDoc.data()!;
                if (freshTx.status !== "PENDING") return; // Already resolved.

                const now = admin.firestore.Timestamp.now();
                // No wallet change: a failed deposit never reserved any balance.

                transaction.update(txDoc.ref, {
                    status: "FAILED",
                    updatedAt: now
                });
            });

            return { status: mopaySession.transactionStatus, transactionId: txData.transactionId };

        } else {
            // PENDING or unknown — payment not yet resolved. Do not credit. Preserve state.
            return { status: mopaySession.transactionStatus, transactionId: txData.transactionId };
        }

    } catch (error: any) {
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Initiates a P2P transfer between two users.
 * Enforces 1.5% sender-paid fee.
 */
export const initiateP2PTransfer = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const fromUid = auth.uid;
    let toUserId: string, amount: { minorUnits: number; currency: string }, idempotencyKey: string;
    try {
        toUserId = requireUid(request.data?.toUserId, "toUserId");
        amount = parseAmount(request.data?.amount);
        idempotencyKey = idempotencyDocId(fromUid, "p2p", request.data?.idempotencyKey);
    } catch (e) { badInput(e); }
    if (fromUid === toUserId) throw new HttpsError("invalid-argument", "Cannot transfer to yourself.");

    await assertAccountActive(fromUid);
    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) return idempotencyDoc.data()?.transactionId;

            const sourceRef = db.collection("wallets").doc(fromUid);
            const destRef = db.collection("wallets").doc(toUserId);
            const [sourceDoc, destDoc] = await Promise.all([transaction.get(sourceRef), transaction.get(destRef)]);

            if (!sourceDoc.exists) throw new Error("Source wallet not found.");
            if (!destDoc.exists) throw new Error("Recipient wallet not found.");

            const feeMinorUnits = Math.floor((amount.minorUnits * 15) / 1000);
            const totalDebit = amount.minorUnits + feeMinorUnits;

            const sourceData = sourceDoc.data()!;
            if ((sourceData.availableBalanceMinorUnits || 0) < totalDebit) throw new Error("Insufficient balance for transfer + fee.");

            const transactionId = db.collection("walletTransactions").doc().id;

            transaction.update(sourceRef, {
                availableBalanceMinorUnits: sourceData.availableBalanceMinorUnits - totalDebit,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(destRef, {
                availableBalanceMinorUnits: (destDoc.data()?.availableBalanceMinorUnits || 0) + amount.minorUnits,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.set(db.collection("walletTransactions").doc(transactionId), {
                transactionId, userId: fromUid, recipientUserId: toUserId,
                type: "TRANSFER_OUT", amountMinorUnits: amount.minorUnits,
                feeMinorUnits, currency: amount.currency,
                status: "COMPLETED", description: `Transfer to ${toUserId}`,
                idempotencyKey, createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId, transactionId, debitAccount: `user_${fromUid}`,
                creditAccount: `user_${toUserId}`, amountMinorUnits: amount.minorUnits,
                currency: amount.currency, reference: `P2P_${transactionId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            if (feeMinorUnits > 0) {
                const feeLedgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(feeLedgerId), {
                    id: feeLedgerId, transactionId, debitAccount: `user_${fromUid}`,
                    creditAccount: "system_fees", amountMinorUnits: feeMinorUnits,
                    currency: amount.currency, reference: `P2P_FEE_${transactionId}`,
                    timestamp: admin.firestore.FieldValue.serverTimestamp()
                });
            }

            transaction.set(idempotencyRef, { transactionId, userId: fromUid, createdAt: admin.firestore.FieldValue.serverTimestamp() });

            return transactionId;
        });
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Settles a PENDING withdrawal — admin only.
 *
 * Invariants:
 *  - Caller must hold the `admin` custom claim.
 *  - Transaction must exist, be type WITHDRAWAL, and be status PENDING.
 *  - Idempotent: a second call for an already-COMPLETED transaction returns
 *    the transactionId without any further mutation.
 *  - No client can call this; Firebase rules + admin claim enforce it.
 *
 * Settlement ledger entry:
 *   debit : system_withdrawal_escrow
 *   credit: external_${provider}
 */
export const confirmWithdrawal = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required.");

    const isAdmin = auth.token?.admin === true;
    if (!isAdmin) throw new HttpsError("permission-denied", "Admin access required.");

    const { transactionId } = request.data;
    if (!transactionId || typeof transactionId !== "string") {
        throw new HttpsError("invalid-argument", "transactionId is required.");
    }

    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const txRef = db.collection("walletTransactions").doc(transactionId);
            const txDoc = await transaction.get(txRef);

            if (!txDoc.exists) {
                throw new HttpsError("not-found", `Transaction ${transactionId} not found.`);
            }

            const txData = txDoc.data()!;

            // Guard: type must be WITHDRAWAL — checked before idempotency.
            if (txData.type !== "WITHDRAWAL") {
                throw new HttpsError("failed-precondition",
                    `Transaction ${transactionId} is not a WITHDRAWAL (got: ${txData.type}).`);
            }

            // Idempotency: already settled — return without mutation.
            if (txData.status === "COMPLETED") {
                return { transactionId, status: "COMPLETED", idempotent: true };
            }

            // Guard: must be PENDING to settle.
            if (txData.status !== "PENDING") {
                throw new HttpsError("failed-precondition",
                    `Transaction ${transactionId} is not PENDING (got: ${txData.status}).`);
            }

            const userId    = txData.userId;
            const amount    = txData.amountMinorUnits as number;
            const currency  = txData.currency || "LSL";
            const provider  = txData.provider || "UNKNOWN";

            // Read wallet inside transaction to guard concurrent mutations.
            const walletRef = db.collection("wallets").doc(userId);
            const walletDoc = await transaction.get(walletRef);
            if (!walletDoc.exists) {
                throw new HttpsError("not-found", `Wallet for user ${userId} not found.`);
            }

            const walletData    = walletDoc.data()!;
            const currentPending = walletData.pendingBalanceMinorUnits as number || 0;

            if (currentPending < amount) {
                throw new HttpsError("failed-precondition",
                    "Pending balance is less than withdrawal amount — data integrity issue.");
            }

            const ledgerEntryId = db.collection("ledgerEntries").doc().id;

            // 1. Mark transaction COMPLETED.
            transaction.update(txRef, {
                status: "COMPLETED",
                completedAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt:   admin.firestore.FieldValue.serverTimestamp(),
            });

            // 2. Decrement pendingBalance — available was already decremented at initiation.
            transaction.update(walletRef, {
                pendingBalanceMinorUnits: currentPending - amount,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });

            // 3. Settlement ledger entry: escrow → external provider.
            transaction.set(db.collection("ledgerEntries").doc(ledgerEntryId), {
                id:                ledgerEntryId,
                transactionId,
                debitAccount:      "system_withdrawal_escrow",
                creditAccount:     `external_${provider}`,
                amountMinorUnits:  amount,
                currency,
                reference:         `WITHDRAW_SETTLE_${transactionId}`,
                timestamp:         admin.firestore.FieldValue.serverTimestamp(),
            });

            return { transactionId, status: "COMPLETED", idempotent: false };
        });
    } catch (error: any) {
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", error.message);
    }
});


/**
 * Admin-only: rejects a PENDING withdrawal and returns the money to the user's available balance.
 *
 *  - Transaction must be type WITHDRAWAL and status PENDING.
 *  - Idempotent: a repeat call on an already-FAILED withdrawal returns without mutation.
 *  - A COMPLETED withdrawal can never be rejected (the money has already left the platform).
 *
 * Reversal ledger entry:
 *   debit : system_withdrawal_escrow
 *   credit: user_${userId}
 */
export const rejectWithdrawal = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required.");
    if (auth.token?.admin !== true) throw new HttpsError("permission-denied", "Admin access required.");

    const { transactionId, reason } = request.data || {};
    if (!transactionId || typeof transactionId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(transactionId)) {
        throw new HttpsError("invalid-argument", "transactionId is required.");
    }
    if (reason !== undefined && (typeof reason !== "string" || reason.length > 300)) {
        throw new HttpsError("invalid-argument", "Invalid reason.");
    }
    const rejectReason = (typeof reason === "string" && reason.trim()) || "Rejected by admin";

    const db = admin.firestore();

    try {
        return await db.runTransaction(async (transaction) => {
            const txRef = db.collection("walletTransactions").doc(transactionId);
            const txDoc = await transaction.get(txRef);
            if (!txDoc.exists) throw new HttpsError("not-found", `Transaction ${transactionId} not found.`);
            const txData = txDoc.data()!;

            if (txData.type !== "WITHDRAWAL") {
                throw new HttpsError("failed-precondition", `Transaction ${transactionId} is not a WITHDRAWAL (got: ${txData.type}).`);
            }
            if (txData.status === "FAILED") {
                return { transactionId, status: "FAILED", idempotent: true };
            }
            if (txData.status !== "PENDING") {
                throw new HttpsError("failed-precondition", `Transaction ${transactionId} is not PENDING (got: ${txData.status}).`);
            }

            const userId = txData.userId as string;
            const amount = txData.amountMinorUnits as number;
            const currency = txData.currency || "LSL";
            if (!Number.isSafeInteger(amount) || amount <= 0) {
                throw new HttpsError("failed-precondition", "Withdrawal has an invalid amount.");
            }

            const walletRef = db.collection("wallets").doc(userId);
            const walletDoc = await transaction.get(walletRef);
            if (!walletDoc.exists) throw new HttpsError("not-found", `Wallet for user ${userId} not found.`);
            const walletData = walletDoc.data()!;
            const currentPending = (walletData.pendingBalanceMinorUnits as number) || 0;
            const currentAvailable = (walletData.availableBalanceMinorUnits as number) || 0;

            if (currentPending < amount) {
                throw new HttpsError("failed-precondition",
                    "Pending balance is less than withdrawal amount — data integrity issue.");
            }

            const ledgerEntryId = db.collection("ledgerEntries").doc().id;

            transaction.update(txRef, {
                status: "FAILED",
                failureReason: rejectReason,
                rejectedBy: auth.uid,
                failedAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });

            transaction.update(walletRef, {
                availableBalanceMinorUnits: currentAvailable + amount,
                pendingBalanceMinorUnits: currentPending - amount,
                updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            });

            transaction.set(db.collection("ledgerEntries").doc(ledgerEntryId), {
                id: ledgerEntryId,
                transactionId,
                debitAccount: "system_withdrawal_escrow",
                creditAccount: `user_${userId}`,
                amountMinorUnits: amount,
                currency,
                reference: `WITHDRAW_REJECT_${transactionId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp(),
            });

            return { transactionId, status: "FAILED", idempotent: false };
        });
    } catch (error: any) {
        if (error instanceof HttpsError) throw error;
        throw new HttpsError("internal", error.message);
    }
});
