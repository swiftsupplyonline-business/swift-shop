import * as assert from "assert";
import * as admin from "firebase-admin";

admin.initializeApp({ projectId: process.env.GCLOUD_PROJECT || "swift-shop-reconciled" });

const db = admin.firestore();

const {
    processFlightEvent,
    feedBird,
    claimFlightRedemption,
} = require("../flight") as typeof import("../flight");

const realAuth = (uid: string) => ({
    uid,
    token: { firebase: { sign_in_provider: "password" } },
});

async function call(fn: any, uid: string, data: Record<string, unknown> = {}) {
    return fn.run({ data, auth: realAuth(uid) });
}

async function expectCallableError(fn: () => Promise<unknown>, code: string) {
    await assert.rejects(fn, (error: any) => error?.code === code);
}

async function seed(path: string, data: Record<string, unknown>) {
    await db.doc(path).set(data);
}

async function deleteCollection(path: string) {
    const snap = await db.collection(path).get();
    if (!snap.empty) {
        const batch = db.batch();
        snap.docs.forEach((doc) => batch.delete(doc.ref));
        await batch.commit();
    }
}

describe("Swift Flight — emulator integration", () => {
    afterEach(async () => {
        for (const collection of ["flightState", "flightEvents", "flightRateLimits", "flightHistory", "orders", "follows", "wallets", "ledgerEntries", "posts"]) {
            await deleteCollection(collection);
        }
    });

    test("forged or wrong-owner sale cannot award points", async () => {
        const uid = "flight-sale-attacker";
        await seed("orders/order-wrong-owner", {
            sellerId: "real-seller",
            status: "CONFIRMED",
            inventoryStatus: "COMMITTED",
        });

        await expectCallableError(
            () => call(processFlightEvent, uid, {
                sourceType: "SUCCESSFUL_SALE",
                sourceId: "does-not-exist",
            }),
            "not-found",
        );

        await expectCallableError(
            () => call(processFlightEvent, uid, {
                sourceType: "SUCCESSFUL_SALE",
                sourceId: "order-wrong-owner",
            }),
            "permission-denied",
        );

        assert.strictEqual((await db.doc(`flightState/${uid}`).get()).exists, false);
    });

    test("duplicate sale event awards only once", async () => {
        const uid = "flight-duplicate-sale";
        await seed("orders/order-1", {
            sellerId: uid,
            status: "CONFIRMED",
            inventoryStatus: "COMMITTED",
        });

        const first = await call(processFlightEvent, uid, {
            sourceType: "SUCCESSFUL_SALE",
            sourceId: "order-1",
        });
        const second = await call(processFlightEvent, uid, {
            sourceType: "SUCCESSFUL_SALE",
            sourceId: "order-1",
        });

        assert.strictEqual(first.duplicate, false);
        assert.strictEqual(first.awardedPoints, 800);
        assert.strictEqual(second.duplicate, true);
        assert.strictEqual(second.awardedPoints, 0);

        const state = (await db.doc(`flightState/${uid}`).get()).data()!;
        assert.strictEqual(state.flightPointsEarned, 810);
        assert.strictEqual(state.currentPoints, 810);
    });

    test("post rewards require canonical published-post fields and ownership", async () => {
        const uid = "flight-post-user";

        await seed("posts/draft-post", {
            authorId: uid,
            caption: "draft",
        });
        await expectCallableError(
            () => call(processFlightEvent, uid, {
                sourceType: "CREATE_POST",
                sourceId: "draft-post",
            }),
            "failed-precondition",
        );

        await seed("posts/other-owner-post", {
            authorId: "other-user",
            createdAt: admin.firestore.Timestamp.now(),
        });
        await expectCallableError(
            () => call(processFlightEvent, uid, {
                sourceType: "CREATE_POST",
                sourceId: "other-owner-post",
            }),
            "permission-denied",
        );

        await seed("posts/published-post", {
            authorId: uid,
            createdAt: admin.firestore.Timestamp.now(),
        });
        const result = await call(processFlightEvent, uid, {
            sourceType: "CREATE_POST",
            sourceId: "published-post",
        });
        assert.strictEqual(result.awardedPoints, 80);
    });

    test("follow then unfollow/refollow remains one reward", async () => {
        const uid = "flight-follow-user";

        await seed("follows/follow-a", { followerId: uid, followedId: "target-user" });
        const first = await call(processFlightEvent, uid, {
            sourceType: "FOLLOW",
            sourceId: "follow-a",
        });

        await seed("follows/follow-b", { followerId: uid, followedId: "target-user" });
        const second = await call(processFlightEvent, uid, {
            sourceType: "FOLLOW",
            sourceId: "follow-b",
        });

        assert.strictEqual(first.awardedPoints, 10);
        assert.strictEqual(second.duplicate, true);
        assert.strictEqual(second.awardedPoints, 0);

        const state = (await db.doc(`flightState/${uid}`).get()).data()!;
        assert.strictEqual(state.flightPointsEarned, 20);
    });

    test("follow daily cap stops the 21st reward", async () => {
        const uid = "flight-follow-cap";

        for (let i = 0; i < 20; i++) {
            await seed(`follows/follow-cap-${i}`, {
                followerId: uid,
                followedId: `target-${i}`,
            });
            await call(processFlightEvent, uid, {
                sourceType: "FOLLOW",
                sourceId: `follow-cap-${i}`,
            });
        }

        await seed("follows/follow-cap-20", {
            followerId: uid,
            followedId: "target-20",
        });

        await expectCallableError(
            () => call(processFlightEvent, uid, {
                sourceType: "FOLLOW",
                sourceId: "follow-cap-20",
            }),
            "resource-exhausted",
        );

        const state = (await db.doc(`flightState/${uid}`).get()).data()!;
        assert.strictEqual(state.flightPointsEarned, 210);
    });

    test("redemption atomically credits wallet, ledger and history and resets flight", async () => {
        const uid = "flight-redemption";
        await seed(`flightState/${uid}`, {
            uid,
            currentPoints: 100_000,
            flightPointsEarned: 100_000,
            altitudeLevel: 10,
            altitudeLabel: "Summit",
            altitudeEnv: "Open sky",
            multiplier: 5,
            flightNumber: 1,
            highestLevel: 10,
            isEligibleToRedeem: true,
        });
        await seed(`wallets/${uid}`, { availableBalanceMinorUnits: 1_000 });

        const result = await call(claimFlightRedemption, uid);

        assert.strictEqual(result.success, true);
        assert.strictEqual(result.redeemedAmountMinorUnits, 20_000);

        const wallet = (await db.doc(`wallets/${uid}`).get()).data()!;
        assert.strictEqual(wallet.availableBalanceMinorUnits, 21_000);

        const state = (await db.doc(`flightState/${uid}`).get()).data()!;
        assert.strictEqual(state.currentPoints, 0);
        assert.strictEqual(state.flightPointsEarned, 0);
        assert.strictEqual(state.flightNumber, 2);
        assert.strictEqual(state.isEligibleToRedeem, false);

        const history = await db.collection("flightHistory").where("uid", "==", uid).get();
        assert.strictEqual(history.size, 1);
        assert.strictEqual(history.docs[0].data().redemptionAmountMinorUnits, 20_000);

        const ledger = await db.collection("ledgerEntries").where("creditAccount", "==", `user_${uid}`).get();
        assert.strictEqual(ledger.size, 1);
        assert.strictEqual(ledger.docs[0].data().amountMinorUnits, 20_000);
    });

    test("failed redemption leaves flight state untouched", async () => {
        const uid = "flight-redemption-atomic-failure";
        await seed(`flightState/${uid}`, {
            uid,
            currentPoints: 100_000,
            flightPointsEarned: 100_000,
            altitudeLevel: 10,
            flightNumber: 1,
            highestLevel: 10,
            isEligibleToRedeem: true,
        });

        await expectCallableError(
            () => call(claimFlightRedemption, uid),
            "not-found",
        );

        const state = (await db.doc(`flightState/${uid}`).get()).data()!;
        assert.strictEqual(state.currentPoints, 100_000);
        assert.strictEqual(state.flightPointsEarned, 100_000);
        assert.strictEqual(state.flightNumber, 1);
    });

    test("feeding can reduce spendable points below 100000 without blocking completed-flight redemption", async () => {
        const uid = "flight-feed-completion";

        await seed(`flightState/${uid}`, {
            uid,
            currentPoints: 30,
            flightPointsEarned: 99_990,
            altitudeLevel: 10,
            altitudeLabel: "Summit",
            altitudeEnv: "Open sky",
            multiplier: 5,
            flightNumber: 1,
            highestLevel: 10,
            isEligibleToRedeem: false,
        });
        await seed(`wallets/${uid}`, { availableBalanceMinorUnits: 0 });
        await seed("follows/feed-completion-follow", {
            followerId: uid,
            followedId: "feed-target",
        });

        const feed = await call(feedBird, uid);
        assert.strictEqual(feed.fed, true);
        assert.strictEqual(feed.currentPoints, 10);

        const award = await call(processFlightEvent, uid, {
            sourceType: "FOLLOW",
            sourceId: "feed-completion-follow",
        });
        assert.strictEqual(award.awardedPoints, 50);
        assert.strictEqual(award.currentPoints, 60);
        assert.strictEqual(award.eligible, true);

        const result = await call(claimFlightRedemption, uid);
        assert.strictEqual(result.success, true);

        const state = (await db.doc(`flightState/${uid}`).get()).data()!;
        assert.strictEqual(state.flightNumber, 2);
        assert.strictEqual(state.flightPointsEarned, 0);
        assert.strictEqual(state.currentPoints, 0);
    });
});
