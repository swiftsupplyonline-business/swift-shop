import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { MopayClient, MOPAY_API_KEY } from "./mopay";

/**
 * Initiates a withdrawal from the user's wallet to an external destination.
 */
export const initiateWithdrawal = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    const { amount, gateway, provider, destination, idempotencyKey } = request.data;

    if (!amount || typeof amount.minorUnits !== "number" || amount.minorUnits <= 0 || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Invalid amount or missing idempotencyKey.");
    }

    const activeGateway = gateway || "MOPAY";

    if (amount.currency === "LSL" && amount.minorUnits < 20000) {
        throw new HttpsError("failed-precondition", "Minimum withdrawal amount is M200.00.");
    }

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
                currency: amount.currency || "LSL", status: "PENDING",
                description: `Withdrawal via ${activeGateway} (${provider}) to ${destination}`,
                gateway: activeGateway, provider: provider || "UNKNOWN",
                destinationAccount: destination, idempotencyKey,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.set(db.collection("ledgerEntries").doc(ledgerEntryId), {
                id: ledgerEntryId, transactionId, debitAccount: `user_${uid}`,
                creditAccount: "system_withdrawal_escrow", amountMinorUnits: amount.minorUnits,
                currency: amount.currency || "LSL", reference: `WITHDRAW_${transactionId}`,
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
 * Initiates a deposit into the user's wallet.
 */
export const initiateDeposit = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    const { amount, gateway, provider, phoneNumber, idempotencyKey } = request.data;

    if (!amount || typeof amount.minorUnits !== "number" || amount.minorUnits <= 0 || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Invalid amount or missing idempotencyKey.");
    }

    const activeGateway = gateway || "MOPAY";
    const db = admin.firestore();

    try {
        const result = await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) return { transactionId: idempotencyDoc.data()?.transactionId, existing: true };

            const walletRef = db.collection("wallets").doc(uid);
            const walletDoc = await transaction.get(walletRef);

            if (!walletDoc.exists) {
                console.log(`Lazily provisioning wallet for user ${uid}.`);
                transaction.set(walletRef, {
                    id: uid,
                    userId: uid,
                    availableBalanceMinorUnits: 0,
                    pendingBalanceMinorUnits: 0,
                    currency: "LSL",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }

            const transactionId = db.collection("walletTransactions").doc().id;

            transaction.set(db.collection("walletTransactions").doc(transactionId), {
                transactionId, userId: uid, type: "DEPOSIT",
                amountMinorUnits: amount.minorUnits, feeMinorUnits: 0,
                currency: amount.currency || "LSL", status: "PENDING",
                description: `Deposit via ${activeGateway} (${provider}) from ${phoneNumber}`,
                gateway: activeGateway, provider: provider || "UNKNOWN",
                sourceAccount: phoneNumber, idempotencyKey,
                createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            // GHOST PENDING PROTECTION: Defer balance increment and ledger entry
            // until MoPay session is successfully created in the next step.

            transaction.set(idempotencyRef, { transactionId, userId: uid, createdAt: admin.firestore.FieldValue.serverTimestamp() });

            return { transactionId, existing: false };
        });

        const transactionId = result.transactionId;

        // Initiate MoPay session if not already done
        if (activeGateway === "MOPAY") {
            const mopayRequest = {
                amount: (amount.minorUnits / 100).toFixed(2), // DECIMAL STRING
                reference: transactionId,
                redirectUrl: "swiftshop://wallet/deposit/verify",
                description: `Deposit ${transactionId} to Swift Wallet`
                // notificationPhoneNumber OMITTED in Sandbox as per MoPay contract.
                // The user number is not sent here; they will enter it on the MoPay hosted page.
            };

            const mopayResponse = await MopayClient.initiatePaymentSession(mopayRequest);

            if (mopayResponse.success && mopayResponse.sessionId) {
                // Step 2: Atomic Balance & Ledger Mutation
                await db.runTransaction(async (transaction) => {
                    const txRef = db.collection("walletTransactions").doc(transactionId);
                    const walletRef = db.collection("wallets").doc(uid);
                    const ledgerEntryId = db.collection("ledgerEntries").doc().id;

                    const walletDoc = await transaction.get(walletRef);
                    const currentPending = walletDoc.data()?.pendingBalanceMinorUnits || 0;

                    transaction.update(txRef, {
                        mopaySessionId: mopayResponse.sessionId,
                        paymentUrl: mopayResponse.paymentUrl,
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });

                    transaction.update(walletRef, {
                        pendingBalanceMinorUnits: currentPending + amount.minorUnits,
                        updatedAt: admin.firestore.FieldValue.serverTimestamp()
                    });

                    transaction.set(db.collection("ledgerEntries").doc(ledgerEntryId), {
                        id: ledgerEntryId, transactionId, debitAccount: "system_deposit_clearing",
                        creditAccount: `user_${uid}`, amountMinorUnits: amount.minorUnits,
                        currency: amount.currency || "LSL", reference: `DEPOSIT_INIT_${transactionId}`,
                        timestamp: admin.firestore.FieldValue.serverTimestamp()
                    });
                });

                return {
                    transactionId,
                    paymentUrl: mopayResponse.paymentUrl,
                    sessionId: mopayResponse.sessionId
                };
            } else {
                console.error("MoPay Session Creation Failed:", mopayResponse.message);
                // Mark transaction as FAILED to prevent it from lingering as PENDING
                await db.collection("walletTransactions").doc(transactionId).update({
                    status: "FAILED",
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });
                return {
                    transactionId,
                    error: mopayResponse.message || "Gateway initiation failed"
                };
            }
        }

        return { transactionId };
    } catch (error: any) {
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
    const { toUserId, amount, idempotencyKey } = request.data;

    if (!toUserId || fromUid === toUserId || !amount || typeof amount.minorUnits !== "number" || amount.minorUnits <= 0 || !idempotencyKey) {
        throw new HttpsError("invalid-argument", "Invalid parameters.");
    }

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

            // Debit Source
            transaction.update(sourceRef, {
                availableBalanceMinorUnits: sourceData.availableBalanceMinorUnits - totalDebit,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            // Credit Destination
            transaction.update(destRef, {
                availableBalanceMinorUnits: (destDoc.data()?.availableBalanceMinorUnits || 0) + amount.minorUnits,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            // Transactions
            transaction.set(db.collection("walletTransactions").doc(transactionId), {
                transactionId, userId: fromUid, recipientUserId: toUserId,
                type: "TRANSFER_OUT", amountMinorUnits: amount.minorUnits,
                feeMinorUnits, currency: amount.currency || "LSL",
                status: "COMPLETED", description: `Transfer to ${toUserId}`,
                idempotencyKey, createdAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            // Ledger
            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId, transactionId, debitAccount: `user_${fromUid}`,
                creditAccount: `user_${toUserId}`, amountMinorUnits: amount.minorUnits,
                currency: amount.currency || "LSL", reference: `P2P_${transactionId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            // Fee Ledger
            if (feeMinorUnits > 0) {
                const feeLedgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(feeLedgerId), {
                    id: feeLedgerId, transactionId, debitAccount: `user_${fromUid}`,
                    creditAccount: "system_fees", amountMinorUnits: feeMinorUnits,
                    currency: amount.currency || "LSL", reference: `P2P_FEE_${transactionId}`,
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
 * Authoritatively verifies a Mopay deposit session and funds the user wallet.
 */
export const verifyDeposit = onCall({ secrets: [MOPAY_API_KEY] }, async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const uid = auth.uid;
    const { sessionId } = request.data;

    if (!sessionId) throw new HttpsError("invalid-argument", "Missing sessionId.");

    const db = admin.firestore();

    try {
        // 1. Locate Transaction by Mopay Session ID
        const txQuery = await db.collection("walletTransactions")
            .where("mopaySessionId", "==", sessionId)
            .where("userId", "==", uid)
            .limit(1)
            .get();

        if (txQuery.empty) throw new Error("Transaction not found for this session.");

        const txDoc = txQuery.docs[0];
        const txData = txDoc.data();

        // 2. Authoritative Gateway Verification
        const mopaySession = await MopayClient.verifyPaymentSession(sessionId);
        if (!mopaySession) throw new Error("Could not verify session with MoPay.");

        // 3. Validation
        if (mopaySession.reference !== txData.transactionId) {
            throw new Error("Session reference mismatch.");
        }

        const mopayAmountMinor = Math.round(mopaySession.amount * 100);
        if (mopayAmountMinor !== txData.amountMinorUnits) {
            throw new Error(`Amount mismatch. Expected: ${txData.amountMinorUnits}, Got: ${mopayAmountMinor}`);
        }

        // 4. Atomic Transition (only if SUCCESS)
        if (mopaySession.transactionStatus === "SUCCESS") {
            await db.runTransaction(async (transaction) => {
                const freshTxDoc = await transaction.get(txDoc.ref);
                const freshTx = freshTxDoc.data()!;

                if (freshTx.status === "COMPLETED") return; // Idempotency

                const walletRef = db.collection("wallets").doc(uid);
                const walletDoc = await transaction.get(walletRef);
                if (!walletDoc.exists) throw new Error("Wallet not found.");

                const walletData = walletDoc.data()!;

                transaction.update(txDoc.ref, {
                    status: "COMPLETED",
                    gatewayTransactionId: mopaySession.transactionId,
                    completedAt: admin.firestore.FieldValue.serverTimestamp(),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                transaction.update(walletRef, {
                    availableBalanceMinorUnits: (walletData.availableBalanceMinorUnits || 0) + txData.amountMinorUnits,
                    pendingBalanceMinorUnits: Math.max(0, (walletData.pendingBalanceMinorUnits || 0) - txData.amountMinorUnits),
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                // Reconciliation Ledger Entry
                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    transactionId: txData.transactionId,
                    debitAccount: "system_mopay_clearing",
                    creditAccount: "system_deposit_clearing",
                    amountMinorUnits: txData.amountMinorUnits,
                    currency: txData.currency || "LSL",
                    reference: `DEPOSIT_CONFIRM_${txData.transactionId}`,
                    timestamp: admin.firestore.FieldValue.serverTimestamp()
                });
            });

            return { status: "SUCCESS", transactionId: txData.transactionId };
        } else {
            await txDoc.ref.update({
                status: mopaySession.transactionStatus === "FAILED" ? "FAILED" : txData.status,
                gatewayStatus: mopaySession.transactionStatus,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
            return { status: mopaySession.transactionStatus, transactionId: txData.transactionId };
        }
    } catch (error: any) {
        console.error("Deposit verification failed:", error);
        throw new HttpsError("failed-precondition", error.message);
    }
});
