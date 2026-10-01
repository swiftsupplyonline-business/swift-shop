import * as admin from "firebase-admin";

/**
 * Delivery-fee escrow for the purchase-first flow.
 *
 *   createDeliveryRequest   buyer wallet  -> system_delivery_escrow      (feePaymentStatus ESCROWED)
 *   declined / expired / cancelled / delivery failed / order cancelled
 *                           escrow        -> buyer wallet                (REFUNDED)
 *   buyer confirms a successful delivery
 *                           escrow        -> delivery listing's author   (RELEASED)
 *
 * All helpers take a Firestore transaction. Reads (readWallet) MUST be issued before any write.
 */

export interface WalletRead { ref: FirebaseFirestore.DocumentReference; balance: number }

export async function readWallet(tx: FirebaseFirestore.Transaction, uid: string): Promise<WalletRead> {
    const ref = admin.firestore().collection("wallets").doc(uid);
    const snap = await tx.get(ref);
    return { ref, balance: snap.exists ? (snap.data()?.availableBalanceMinorUnits || 0) : 0 };
}

export function isEscrowed(dr: FirebaseFirestore.DocumentData): boolean {
    return dr.feePaymentStatus === "ESCROWED" &&
        Number.isSafeInteger(dr.deliveryFeeMinorUnits) && dr.deliveryFeeMinorUnits > 0;
}

function ledger(tx: FirebaseFirestore.Transaction, entry: Record<string, unknown>): void {
    const ref = admin.firestore().collection("ledgerEntries").doc();
    tx.set(ref, { id: ref.id, currency: "LSL", timestamp: admin.firestore.FieldValue.serverTimestamp(), ...entry });
}

/** Debit the buyer and hold the fee in escrow. Caller sets feePaymentStatus: "ESCROWED" on the request. */
export function writeEscrow(tx: FirebaseFirestore.Transaction, wallet: WalletRead, buyerId: string, amount: number, requestId: string): void {
    if (wallet.balance < amount) {
        throw new Error(`Insufficient wallet balance for the delivery fee. Required: ${amount}, Available: ${wallet.balance}`);
    }
    tx.set(wallet.ref, {
        availableBalanceMinorUnits: wallet.balance - amount,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
    ledger(tx, {
        debitAccount: `user_${buyerId}`, creditAccount: "system_delivery_escrow",
        amountMinorUnits: amount, reference: `DELIVERY_FEE_ESCROW_${requestId}`
    });
}

/** Return an escrowed fee to the buyer. No-op (returns false) unless the fee is currently escrowed. */
export function writeRefund(
    tx: FirebaseFirestore.Transaction, requestRef: FirebaseFirestore.DocumentReference,
    dr: FirebaseFirestore.DocumentData, wallet: WalletRead | null, reason: string,
    extra: Record<string, unknown> = {}
): boolean {
    if (!isEscrowed(dr)) return false;
    if (!wallet) throw new Error("Internal error: buyer wallet was not read before refunding a delivery fee");
    const amount = dr.deliveryFeeMinorUnits as number;
    tx.set(wallet.ref, {
        availableBalanceMinorUnits: wallet.balance + amount,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });
    ledger(tx, {
        debitAccount: "system_delivery_escrow", creditAccount: `user_${dr.requesterId}`,
        amountMinorUnits: amount, reference: `DELIVERY_FEE_REFUND_${requestRef.id}`
    });
    tx.update(requestRef, { feePaymentStatus: "REFUNDED", feeRefundReason: reason, updatedAt: Date.now(), ...extra });
    return true;
}
