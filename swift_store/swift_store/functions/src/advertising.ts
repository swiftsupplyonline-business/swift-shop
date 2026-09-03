import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";

/**
 * Activates a DRAFT or PAUSED advertising campaign.
 *
 * Contract (matches FirebaseAdvertisingRepository.activateCampaign on Android):
 * - Request: { campaignId: string }
 *
 * Financial model: the campaign's full budget is escrowed from the owner's
 * wallet the first time it goes live (DRAFT -> ACTIVE). Resuming from PAUSED
 * does not re-charge, since the budget is already held in escrow.
 */
export const activateCampaign = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { campaignId } = request.data;
    if (!campaignId) throw new HttpsError("invalid-argument", "campaignId is required");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const campaignRef = db.collection("advertisingCampaigns").doc(campaignId);
            const campaignDoc = await transaction.get(campaignRef);
            if (!campaignDoc.exists) throw new Error("Campaign not found");
            const campaign = campaignDoc.data()!;

            if (campaign.ownerId !== auth.uid && !auth.token.admin) throw new Error("Unauthorized");

            if (campaign.status !== "DRAFT" && campaign.status !== "PAUSED") {
                throw new Error(`Campaign cannot be activated from status ${campaign.status}`);
            }

            if (campaign.status === "DRAFT") {
                const budgetMinorUnits = campaign.budgetMinorUnits || 0;
                const currency = campaign.budgetCurrency || "LSL";
                if (budgetMinorUnits <= 0) throw new Error("Campaign has no budget set");

                const walletRef = db.collection("wallets").doc(auth.uid);
                const walletDoc = await transaction.get(walletRef);
                if (!walletDoc.exists) throw new Error("Wallet not found");

                const availableBalance = walletDoc.data()?.availableBalanceMinorUnits || 0;
                if (availableBalance < budgetMinorUnits) {
                    throw new Error(`Insufficient wallet balance to fund campaign. Required: ${budgetMinorUnits}, Available: ${availableBalance}`);
                }

                transaction.update(walletRef, {
                    availableBalanceMinorUnits: availableBalance - budgetMinorUnits,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                });

                const ledgerId = db.collection("ledgerEntries").doc().id;
                transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                    id: ledgerId,
                    debitAccount: `user_${auth.uid}`,
                    creditAccount: "system_ad_escrow",
                    amountMinorUnits: budgetMinorUnits,
                    currency,
                    reference: `CAMPAIGN_FUND_${campaignId}`,
                    timestamp: admin.firestore.FieldValue.serverTimestamp()
                });
            }

            transaction.update(campaignRef, {
                status: "ACTIVE",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});

/**
 * Cancels a campaign and refunds any escrowed budget.
 *
 * Contract (matches FirebaseAdvertisingRepository.cancelCampaign on Android):
 * - Request: { campaignId: string }
 *
 * NOTE: CampaignStatus has no dedicated CANCELLED value (DRAFT/ACTIVE/PAUSED/
 * COMPLETED/REJECTED — see core/model/Models.kt). COMPLETED is used here to
 * represent "ended early by the owner", flagged via cancelledEarly so it can
 * be told apart from a campaign that ran its full durationWeeks. Consider
 * adding a real CANCELLED value to the enum instead.
 *
 * There is also no per-impression spend metering in the current schema, so a
 * cancelled campaign's full escrowed budget is refunded regardless of how
 * long it ran (blueprint §27 lists escrow wait-time rules as unresolved).
 */
export const cancelCampaign = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { campaignId } = request.data;
    if (!campaignId) throw new HttpsError("invalid-argument", "campaignId is required");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const campaignRef = db.collection("advertisingCampaigns").doc(campaignId);
            const campaignDoc = await transaction.get(campaignRef);
            if (!campaignDoc.exists) throw new Error("Campaign not found");
            const campaign = campaignDoc.data()!;

            if (campaign.ownerId !== auth.uid && !auth.token.admin) throw new Error("Unauthorized");

            if (campaign.status === "COMPLETED" || campaign.status === "REJECTED") {
                return; // Already terminal; idempotent no-op.
            }

            const wasFunded = campaign.status === "ACTIVE" || campaign.status === "PAUSED";

            if (wasFunded) {
                const budgetMinorUnits = campaign.budgetMinorUnits || 0;
                const currency = campaign.budgetCurrency || "LSL";

                if (budgetMinorUnits > 0) {
                    const walletRef = db.collection("wallets").doc(campaign.ownerId);
                    const walletDoc = await transaction.get(walletRef);
                    if (walletDoc.exists) {
                        const availableBalance = walletDoc.data()?.availableBalanceMinorUnits || 0;
                        transaction.update(walletRef, {
                            availableBalanceMinorUnits: availableBalance + budgetMinorUnits,
                            updatedAt: admin.firestore.FieldValue.serverTimestamp()
                        });

                        const ledgerId = db.collection("ledgerEntries").doc().id;
                        transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                            id: ledgerId,
                            debitAccount: "system_ad_escrow",
                            creditAccount: `user_${campaign.ownerId}`,
                            amountMinorUnits: budgetMinorUnits,
                            currency,
                            reference: `CAMPAIGN_REFUND_${campaignId}`,
                            timestamp: admin.firestore.FieldValue.serverTimestamp()
                        });
                    }
                }
            }

            transaction.update(campaignRef, {
                status: "COMPLETED",
                cancelledEarly: true,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});
