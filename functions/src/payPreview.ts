import { onRequest, HttpsError } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { MOPAY_API_KEY, MopayClient } from "./mopay";
import { normalizeShareSlug } from "./shareSlug";

const SITE_ORIGIN = `https://${process.env.GCLOUD_PROJECT}.web.app`;
const MAX_DIRECT_AMOUNT_MINOR = 500000;
const RATE_LIMIT_PER_MINUTE = 10;

function escapeHtml(input: string): string {
    return input
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function getClientIp(req: any): string {
    const forwarded = String(req.headers?.["x-forwarded-for"] || "");
    return (forwarded.split(",")[0] || req.ip || "unknown").trim().slice(0, 120);
}

async function enforceRateLimit(db: FirebaseFirestore.Firestore, ip: string): Promise<void> {
    const bucket = Math.floor(Date.now() / 60000);
    const rateKey = Buffer.from(ip).toString("base64url");
    const ref = db.collection("payRateLimits").doc(`${rateKey}-${bucket}`);
    await db.runTransaction(async transaction => {
        const doc = await transaction.get(ref);
        const count = Number(doc.data()?.count || 0);
        if (count >= RATE_LIMIT_PER_MINUTE) throw new Error("Too many payment requests. Please try again shortly.");
        transaction.set(ref, {
            count: count + 1,
            bucket,
            expiresAt: admin.firestore.Timestamp.fromMillis(Date.now() + 10 * 60 * 1000)
        }, { merge: true });
    });
}

async function findUserBySlug(db: FirebaseFirestore.Firestore, userSlug: string): Promise<FirebaseFirestore.DocumentSnapshot | null> {
    const direct = await db.collection("users").where("shareSlug", "==", userSlug).limit(2).get();
    if (direct.size === 1) return direct.docs[0];
    if (direct.size > 1) return null;

    const legacy = await db.collection("users").get();
    const matches = legacy.docs.filter(doc => {
        const data = doc.data();
        return normalizeShareSlug(data.displayName) === userSlug;
    });
    return matches.length === 1 ? matches[0] : null;
}

function renderPayPage(opts: {
    userSlug: string;
    displayName: string;
    avatarUrl: string;
    amount?: string;
    note?: string;
    error?: string;
}): string {
    const { userSlug, displayName, avatarUrl, amount = "", note = "", error = "" } = opts;
    const safeName = escapeHtml(displayName);
    const safeAvatar = escapeHtml(avatarUrl);
    const safeAmount = escapeHtml(amount);
    const safeNote = escapeHtml(note);
    const safeError = error ? `<div class="error">${escapeHtml(error)}</div>` : "";
    const pageUrl = `${SITE_ORIGIN}/pay/${encodeURIComponent(userSlug)}`;
    const requestDescription = amount
        ? `${displayName} requests M${amount}${note ? ` - ${note}` : ""}`
        : `Send money to ${displayName} - Swift Wallet`;
    const safeDescription = escapeHtml(requestDescription);

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${safeDescription}</title>
  <meta name="description" content="${safeDescription}">
  <link rel="canonical" href="${escapeHtml(pageUrl)}">
  <meta property="og:type" content="website">
  <meta property="og:title" content="${safeDescription}">
  <meta property="og:description" content="Swift Wallet">
  ${safeAvatar ? `<meta property="og:image" content="${safeAvatar}">` : ""}
  <meta property="og:url" content="${escapeHtml(pageUrl)}">
  <meta name="twitter:card" content="summary">
  <meta name="twitter:title" content="${safeDescription}">
  <meta name="twitter:description" content="Swift Wallet">
  ${safeAvatar ? `<meta name="twitter:image" content="${safeAvatar}">` : ""}
  <style>
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 32px 20px; color: #171717; background: #fff; }
    .card { border: 1px solid #e5e5e5; border-radius: 18px; padding: 24px; box-shadow: 0 8px 30px rgba(0,0,0,.06); }
    .avatar { width: 72px; height: 72px; border-radius: 50%; object-fit: cover; background: #eee; display: block; margin: 0 auto 16px; }
    h1 { text-align: center; font-size: 24px; margin: 0 0 8px; }
    .subtitle { text-align: center; color: #666; margin: 0 0 24px; }
    label { display: block; font-size: 14px; font-weight: 700; margin: 14px 0 7px; }
    input { width: 100%; box-sizing: border-box; padding: 14px; border: 1px solid #ccc; border-radius: 10px; font-size: 17px; }
    .btn { width: 100%; border: 0; border-radius: 10px; padding: 15px; margin-top: 20px; font-weight: 700; font-size: 16px; cursor: pointer; }
    .primary { background: #111; color: #fff; }
    .error { background: #fff1f1; color: #a00; padding: 12px; border-radius: 10px; margin-bottom: 16px; font-size: 14px; }
    .limit { color: #777; font-size: 12px; margin-top: 8px; }
  </style>
</head>
<body>
  <main class="card">
    ${safeAvatar ? `<img class="avatar" src="${safeAvatar}" alt="${safeName}">` : ""}
    <h1>Send money to ${safeName}</h1>
    <p class="subtitle">Swift Wallet</p>
    ${safeError}
    <form method="post" action="/pay/${encodeURIComponent(userSlug)}">
      <label for="amount">Amount (M)</label>
      <input id="amount" name="amount" inputmode="decimal" type="number" min="0.01" max="5000" step="0.01" value="${safeAmount}" required>
      <label for="note">Note (optional)</label>
      <input id="note" name="note" maxlength="160" value="${safeNote}" placeholder="What is this payment for?">
      <button class="btn primary" type="submit">Pay with MoPay</button>
      <div class="limit">Direct payments are limited to M5,000.</div>
    </form>
  </main>
</body>
</html>`;
}

function renderResult(title: string, message: string, whatsappUrl = ""): string {
    const safeTitle = escapeHtml(title);
    const safeMessage = escapeHtml(message);
    return `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${safeTitle} — Swift Wallet</title>
<style>body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;max-width:480px;margin:0 auto;padding:32px 20px}.card{border:1px solid #e5e5e5;border-radius:18px;padding:24px;text-align:center}.btn{display:block;text-decoration:none;padding:15px;border-radius:10px;background:#111;color:#fff;font-weight:700;margin-top:18px}</style>
</head><body><main class="card"><h1>${safeTitle}</h1><p>${safeMessage}</p>${whatsappUrl ? `<a class="btn" href="${escapeHtml(whatsappUrl)}">Share receipt on WhatsApp</a>` : ""}</main></body></html>`;
}

async function createDirectPayment(db: FirebaseFirestore.Firestore, userSlug: string, req: any, res: any): Promise<void> {
    await enforceRateLimit(db, getClientIp(req));

    const userDoc = await findUserBySlug(db, userSlug);
    if (!userDoc) {
        res.status(404).send("Swift Wallet user not found");
        return;
    }

    const body = req.body || {};
    const amountMajor = Number(body.amount);
    const amountMinor = Math.round(amountMajor * 100);
    const note = String(body.note || "").trim().slice(0, 160);

    if (!Number.isFinite(amountMinor) || amountMinor <= 0 || amountMinor > MAX_DIRECT_AMOUNT_MINOR) {
        res.status(400).send(renderPayPage({
            userSlug,
            displayName: String(userDoc.data()?.displayName || "Swift Wallet user"),
            avatarUrl: String(userDoc.data()?.avatar || userDoc.data()?.avatarUrl || userDoc.data()?.photoUrl || ""),
            amount: String(body.amount || ""),
            note,
            error: "Enter an amount between M0.01 and M5,000."
        }));
        return;
    }

    const user = userDoc.data()!;
    const transferRef = db.collection("walletTransfers").doc();
    const transferId = transferRef.id;

    await transferRef.set({
        id: transferId,
        code: null,
        type: "direct",
        status: "pending",
        recipientId: userDoc.id,
        recipientPhone: String(user.whatsappNumber || ""),
        recipientName: String(user.displayName || ""),
        amount: amountMinor,
        currency: "LSL",
        note,
        senderName: "",
        senderPhone: "",
        mopayTxId: null,
        mopaySessionId: null,
        escrowHeld: false,
        requestLink: null,
        claimLink: null,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        expiresAt: admin.firestore.Timestamp.fromMillis(Date.now() + 24 * 60 * 60 * 1000)
    });

    const mopayResponse = await MopayClient.initiatePaymentSession({
        amount: amountMajor,
        reference: transferId,
        redirectUrl: `${SITE_ORIGIN}/pay/${encodeURIComponent(userSlug)}/complete`,
        description: `Swift Wallet payment to ${String(user.displayName || "user")}`,
        idempotencyKey: transferId
    });

    if (!mopayResponse.success || !mopayResponse.sessionId || !mopayResponse.paymentUrl) {
        await transferRef.update({
            status: "declined",
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
        res.status(502).send("Unable to start MoPay payment");
        return;
    }

    await transferRef.update({
        mopaySessionId: mopayResponse.sessionId,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    res.redirect(303, mopayResponse.paymentUrl);
}

async function completeDirectPayment(db: FirebaseFirestore.Firestore, userSlug: string, req: any, res: any): Promise<void> {
    const sessionId = String(req.query?.sessionId || "");
    const reference = String(req.query?.reference || "");
    if (!sessionId || !reference) {
        res.status(400).send(renderResult("Payment incomplete", "MoPay did not return a payment session."));
        return;
    }

    const transferRef = db.collection("walletTransfers").doc(reference);
    const transferDoc = await transferRef.get();
    if (!transferDoc.exists) {
        res.status(404).send(renderResult("Payment not found", "This payment reference could not be found."));
        return;
    }

    const transfer = transferDoc.data()!;
    if (transfer.recipientId && transfer.recipientId !== (await findUserBySlug(db, userSlug))?.id) {
        res.status(403).send(renderResult("Payment mismatch", "This payment does not belong to this wallet."));
        return;
    }

    const mopaySession = await MopayClient.verifyPaymentSession(sessionId);
    if (!mopaySession || mopaySession.reference !== reference) {
        res.status(400).send(renderResult("Payment could not be verified", "Swift could not verify this MoPay payment."));
        return;
    }

    const amountMinor = Math.round(Number(mopaySession.amount) * 100);
    if (amountMinor !== Number(transfer.amount)) {
        res.status(400).send(renderResult("Payment mismatch", "The verified MoPay amount does not match this payment."));
        return;
    }

    const status = String(mopaySession.transactionStatus || mopaySession.status || "").toUpperCase();

    if (status === "SUCCESS" || status === "COMPLETED") {
        await db.runTransaction(async transaction => {
            const fresh = await transaction.get(transferRef);
            const current = fresh.data()!;
            if (current.status === "paid") return;
            if (current.status !== "pending") throw new Error(`Transfer is already ${current.status}.`);

            const walletRef = db.collection("wallets").doc(String(current.recipientId));
            const walletDoc = await transaction.get(walletRef);
            if (!walletDoc.exists) throw new Error("Recipient wallet not found.");

            const wallet = walletDoc.data()!;
            const newBalance = Number(wallet.availableBalanceMinorUnits || 0) + Number(current.amount);

            transaction.update(walletRef, {
                availableBalanceMinorUnits: newBalance,
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });

            const ledgerId = db.collection("ledgerEntries").doc().id;
            transaction.set(db.collection("ledgerEntries").doc(ledgerId), {
                id: ledgerId,
                transactionId: current.id,
                debitAccount: "system_mopay_clearing",
                creditAccount: `user_${current.recipientId}`,
                amountMinorUnits: current.amount,
                currency: current.currency || "LSL",
                reference: `WALLET_PAY_${current.id}`,
                gatewayTransactionId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                timestamp: admin.firestore.FieldValue.serverTimestamp()
            });

            transaction.update(transferRef, {
                status: "paid",
                mopayTxId: mopaySession.transactionId || `MOPAY_${sessionId}`,
                completedAt: admin.firestore.FieldValue.serverTimestamp(),
                updatedAt: admin.firestore.FieldValue.serverTimestamp()
            });
        });

        res.status(200).send(renderResult("Payment sent", `M${(amountMinor / 100).toFixed(2)} was added to ${String(transfer.recipientName || "the Swift Wallet")}.`));
        return;
    }

    if (status === "FAILED" || status === "CANCELLED" || status === "EXPIRED") {
        await transferRef.update({
            status: "declined",
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
        res.status(200).send(renderResult("Payment not completed", "The MoPay payment was not completed. No wallet credit was made."));
        return;
    }

    res.status(200).send(renderResult("Payment pending", "MoPay has not reported a completed payment yet. No wallet credit was made."));
}

export const payPreview = onRequest({ invoker: "public", secrets: [MOPAY_API_KEY] }, async (req, res) => {
    const match = req.path.match(/^\/pay\/([^/]+)(\/complete)?\/?$/);
    if (!match) {
        res.status(404).send("Swift Wallet link not found");
        return;
    }

    const userSlug = decodeURIComponent(match[1]);
    const isComplete = Boolean(match[2]);

    try {
        const db = admin.firestore();

        if (isComplete && req.method === "GET") {
            await completeDirectPayment(db, userSlug, req, res);
            return;
        }

        if (req.method === "POST") {
            await createDirectPayment(db, userSlug, req, res);
            return;
        }

        if (req.method !== "GET") {
            res.status(405).set("Allow", "GET, POST").send("Method not allowed");
            return;
        }

        await enforceRateLimit(db, getClientIp(req));
        const userDoc = await findUserBySlug(db, userSlug);
        if (!userDoc) {
            res.status(404).send("Swift Wallet user not found");
            return;
        }

        const user = userDoc.data()!;
        const displayName = String(user.displayName || "Swift Wallet user");
        const avatarUrl = String(user.avatar || user.avatarUrl || user.photoUrl || "");
        res.status(200).send(renderPayPage({ userSlug, displayName, avatarUrl }));
    } catch (error: any) {
        console.error("payPreview failed:", error);
        if (error instanceof HttpsError) {
            res.status(error.httpErrorCode?.status || 400).send(error.message);
            return;
        }
        res.status(429).send("Please try again shortly.");
    }
});
