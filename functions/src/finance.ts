import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

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
export const initiateDeposit = onCall(async (request) => {
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
        return await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) return idempotencyDoc.data()?.transactionId;

            const walletRef = db.collection("wallets").doc(uid);
            const walletDoc = await transaction.get(walletRef);
            if (!walletDoc.exists) throw new Error("Wallet not found.");

            const transactionId = db.collection("walletTransactions").doc().id;
            const ledgerEntryId = db.collection("ledgerEntries").doc().id;

            const newPending = (walletDoc.data()!.pendingBalanceMinorUnits || 0) + amount.minorUnits;

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

            transaction.set(db.collection("ledgerEntries").doc(ledgerEntryId), {
                id: ledgerEntryId, transactionId, debitAccount: "system_deposit_clearing",
                creditAccount: `user_${uid}`, amountMinorUnits: amount.minorUnits,
                currency: amount.currency || "LSL", reference: `DEPOSIT_${transactionId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(walletRef, {
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
