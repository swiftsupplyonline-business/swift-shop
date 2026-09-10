import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { resolveEntitlement, getCurrentWeeklyPeriod } from "./entitlements";

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

    const { campaignId, idempotencyKey } = request.data;
    if (!campaignId) throw new HttpsError("invalid-argument", "campaignId is required");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            if (idempotencyKey) {
                const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
                const idempotencyDoc = await transaction.get(idempotencyRef);
                if (idempotencyDoc.exists) return; // Idempotent success
            }

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

                if (budgetMinorUnits === 0) {
                    // --- Internal Promotion (Free) Path ---
                    const usageRef = db.collection("merchantUsage").doc(auth.uid);
                    const usageDoc = await transaction.get(usageRef);
                    const period = getCurrentWeeklyPeriod();

                    const userDoc = await transaction.get(db.collection("users").doc(auth.uid));
                    const tier = userDoc.data()?.tier || "BASIC";
                    const entitlement = resolveEntitlement(tier);

                    if (!entitlement.internalPromotionUnlimited) {
                        let used = 0;
                        if (usageDoc.exists && usageDoc.data()?.periodStart === period.start) {
                            used = usageDoc.data()?.internalPromotionsUsed || 0;
                        }
                        if (used >= entitlement.internalPromotionAllowance) {
                            throw new Error("INTERNAL_PROMOTION_LIMIT_REACHED");
                        }
                        transaction.set(usageRef, {
                            internalPromotionsUsed: used + 1,
                            periodStart: period.start,
                            periodEnd: period.end,
                            updatedAt: admin.firestore.FieldValue.serverTimestamp()
                        }, { merge: true });
                    }
                } else {
                    // --- Paid Campaign Path ---
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
            }

            transaction.update(campaignRef, {
                status: "ACTIVE",
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            if (idempotencyKey) {
                transaction.set(db.collection("idempotencyKeys").doc(idempotencyKey), {
                    userId: auth.uid,
                    campaignId,
                    action: "ACTIVATE_CAMPAIGN",
                    createdAt: admin.firestore.FieldValue.serverTimestamp()
                });
            }
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

/**
 * Authoritative check for promotion eligibility.
 */
export const checkPromotionEligibility = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { type } = request.data; // "INTERNAL" or "EXTERNAL"
    if (type !== "INTERNAL" && type !== "EXTERNAL") throw new HttpsError("invalid-argument", "Invalid promotion type");

    const db = admin.firestore();
    const userDoc = await db.collection("users").doc(auth.uid).get();
    const tier = userDoc.data()?.tier || "BASIC";
    const entitlement = resolveEntitlement(tier);

    const allowance = type === "INTERNAL" ? entitlement.internalPromotionAllowance : entitlement.externalPromotionAllowance;
    const isUnlimited = type === "INTERNAL" ? entitlement.internalPromotionUnlimited : entitlement.externalPromotionUnlimited;

    if (isUnlimited) return { status: "ALLOWED", remaining: -1 };

    const usageDoc = await db.collection("merchantUsage").doc(auth.uid).get();
    const usage = usageDoc.data();
    const period = getCurrentWeeklyPeriod();

    let used = 0;
    if (usage && usage.periodStart === period.start) {
        used = type === "INTERNAL" ? usage.internalPromotionsUsed : usage.externalPromotionsUsed;
    }

    if (used < allowance) {
        return { status: "ALLOWED", remaining: allowance - used };
    } else {
        return { status: "DENIED", remaining: 0 };
    }
});

/**
 * Atomically consumes a promotion allowance.
 */
export const consumePromotionAllowance = onCall(async (request) => {
    const auth = request.auth;
    if (!auth) throw new HttpsError("unauthenticated", "Auth required");

    const { type, idempotencyKey } = request.data;
    if (type !== "INTERNAL" && type !== "EXTERNAL") throw new HttpsError("invalid-argument", "Invalid promotion type");
    if (!idempotencyKey) throw new HttpsError("invalid-argument", "idempotencyKey is required");

    const db = admin.firestore();

    try {
        await db.runTransaction(async (transaction) => {
            const idempotencyRef = db.collection("idempotencyKeys").doc(idempotencyKey);
            const idempotencyDoc = await transaction.get(idempotencyRef);
            if (idempotencyDoc.exists) {
                const existingAction = idempotencyDoc.data()?.action;
                if (existingAction === "CONSUME_PROMOTION" && idempotencyDoc.data()?.type === type) {
                    return; // Idempotent success
                }
                throw new Error("IDEMPOTENCY_KEY_REUSE_MISMATCH");
            }

            const userDoc = await transaction.get(db.collection("users").doc(auth.uid));
            const tier = userDoc.data()?.tier || "BASIC";
            const entitlement = resolveEntitlement(tier);

            const allowance = type === "INTERNAL" ? entitlement.internalPromotionAllowance : entitlement.externalPromotionAllowance;
            const isUnlimited = type === "INTERNAL" ? entitlement.internalPromotionUnlimited : entitlement.externalPromotionUnlimited;

            if (isUnlimited) return;

            const usageRef = db.collection("merchantUsage").doc(auth.uid);
            const usageDoc = await transaction.get(usageRef);
            const period = getCurrentWeeklyPeriod();

            let usageData = usageDoc.data();

            if (!usageData || usageData.periodStart !== period.start) {
                // Reset for new period
                usageData = {
                    userId: auth.uid,
                    periodStart: period.start,
                    periodEnd: period.end,
                    internalPromotionsUsed: 0,
                    externalPromotionsUsed: 0,
                    updatedAt: admin.firestore.FieldValue.serverTimestamp()
                };
            }

            const used = type === "INTERNAL" ? usageData.internalPromotionsUsed : usageData.externalPromotionsUsed;

            if (used >= allowance) {
                throw new Error(`${type}_PROMOTION_LIMIT_REACHED`);
            }

            const updates: any = {
                periodStart: period.start,
                periodEnd: period.end,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            };
            if (type === "INTERNAL") {
                updates.internalPromotionsUsed = used + 1;
            } else {
                updates.externalPromotionsUsed = used + 1;
            }

            transaction.set(usageRef, { ...usageData, ...updates }, { merge: true });

            transaction.set(idempotencyRef, {
                userId: auth.uid,
                type,
                action: "CONSUME_PROMOTION",
                createdAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });
        return { success: true };
    } catch (error: any) {
        throw new HttpsError("failed-precondition", error.message);
    }
});
