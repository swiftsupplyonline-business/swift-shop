import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import * as fs from "fs";
import * as path from "path";

describe("Firestore Security Rules", () => {
  let testEnv: RulesTestEnvironment;

  beforeAll(async () => {
    // Relative path from functions/src/test/ to docs/firestore.rules
    const rulesPath = path.resolve(__dirname, "../../../docs/firestore.rules");
    testEnv = await initializeTestEnvironment({
      projectId: "swift-shop-reconciled",
      firestore: {
        rules: fs.readFileSync(rulesPath, "utf8"),
      },
    });
  });

  afterAll(async () => {
    await testEnv.cleanup();
  });

  test("User can read their own ledger record by canonical account identity", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("ledgerEntries").doc("ledger_alice").set({
        debitAccount: "system_clearing",
        creditAccount: "user_alice",
        amountMinorUnits: 1000,
      });
    });
    await expect(aliceDb.collection("ledgerEntries").doc("ledger_alice").get()).resolves.toBeDefined();
  });

  test("User cannot read another user's ledger record", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("ledgerEntries").doc("ledger_bob").set({
        debitAccount: "system_clearing",
        creditAccount: "user_bob",
        amountMinorUnits: 1000,
      });
    });
    await assertFails(aliceDb.collection("ledgerEntries").doc("ledger_bob").get());
  });

  test("Unauthenticated user cannot read ledger records", async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("ledgerEntries").doc("ledger_private").set({
        debitAccount: "system_clearing",
        creditAccount: "user_alice",
        amountMinorUnits: 1000,
      });
    });
    await assertFails(db.collection("ledgerEntries").doc("ledger_private").get());
  });

  test("Admin can read ledger records", async () => {
    const adminDb = testEnv.authenticatedContext("admin-user", { admin: true }).firestore();
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await context.firestore().collection("ledgerEntries").doc("ledger_admin_read").set({
        debitAccount: "system_clearing",
        creditAccount: "user_alice",
        amountMinorUnits: 1000,
      });
    });
    await expect(adminDb.collection("ledgerEntries").doc("ledger_admin_read").get()).resolves.toBeDefined();
  });

  test("Unauthorized user cannot write to deliveryRoutes", async () => {
    const unauthedDb = testEnv.unauthenticatedContext().firestore();
    await assertFails(unauthedDb.collection("deliveryRoutes").doc("route_1").set({}));
  });

  test("Merchant cannot forge price directly in listings", async () => {
    //alice owns listing_1
    const aliceDb = testEnv.authenticatedContext("alice").firestore();

    // We need to setup a listing first as admin
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("listings").doc("listing_1").set({
            sellerId: "alice",
            priceMinorUnits: 1000
        });
    });

    await assertFails(aliceDb.collection("listings").doc("listing_1").update({
      priceMinorUnits: 1 // Forged price
    }));
  });

  test("Merchant cannot manage other merchant's drivers", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    await assertFails(aliceDb.collection("deliveryProviders").doc("bob").collection("drivers").doc("alice").set({
        authorized: true
    }));
  });

  test("Seller cannot write engine-owned listing fields directly", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    await testEnv.withSecurityRulesDisabled(async (context) => {
        await context.firestore().collection("listings").doc("listing_2").set({
            sellerId: "alice", status: "SUSPENDED", inventoryMode: "STOCKED", stockQuantity: 1, reservedQuantity: 0
        });
    });
    for (const patch of [
        { status: "ACTIVE" },            // un-suspend self
        { reservedQuantity: 0 },
        { inventoryMode: "UNLIMITED" },  // infinite stock
        { isAvailable: true },
        { shareSlug: "hijacked" },
        { title: "benign edit" },        // even benign edits must use the callable
    ]) {
        await assertFails(aliceDb.collection("listings").doc("listing_2").update(patch));
    }
  });

  test("Profile owner cannot write fields outside the editable allowlist", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    await testEnv.withSecurityRulesDisabled(async (c) => { await c.firestore().collection("profiles").doc("alice").set({ displayName: "A", isVerified: false }); });
    await assertFails(aliceDb.collection("profiles").doc("alice").update({ isVerified: true }));
    await assertSucceeds(aliceDb.collection("profiles").doc("alice").update({ bio: "hello" }));
  });

  test("User cannot self-verify on their users document", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    await testEnv.withSecurityRulesDisabled(async (c) => { await c.firestore().collection("users").doc("alice").set({ uid: "alice", isVerified: false, tier: "BASIC" }); });
    await assertFails(aliceDb.collection("users").doc("alice").update({ isVerified: true }));
  });

  test("Conversation participants cannot rewrite participantIds; strangers cannot mark messages read", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    const eveDb = testEnv.authenticatedContext("eve").firestore();
    await testEnv.withSecurityRulesDisabled(async (c) => {
      await c.firestore().collection("conversations").doc("c1").set({ participantIds: ["alice", "bob"], lastMessage: "" });
      await c.firestore().collection("messages").doc("m1").set({ conversationId: "c1", senderId: "bob", isRead: false });
    });
    await assertFails(aliceDb.collection("conversations").doc("c1").update({ participantIds: ["alice", "bob", "eve"] }));
    await assertSucceeds(aliceDb.collection("conversations").doc("c1").update({ lastMessage: "hi" }));
    await assertFails(eveDb.collection("messages").doc("m1").update({ isRead: true }));
    await assertSucceeds(aliceDb.collection("messages").doc("m1").update({ isRead: true }));
  });

  test("Ad campaign draft cannot carry fake metrics or a negative budget", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    const base = { campaignId: "x", ownerId: "alice", contentId: "l1", contentType: "LISTING", budgetCurrency: "LSL",
      durationWeeks: 1, status: "DRAFT", impressions: 0, clicks: 0, conversions: 0, includeAllFeed: false, createdAt: 1 };
    await assertSucceeds(aliceDb.collection("advertisingCampaigns").doc("x").set({ ...base, budgetMinorUnits: 5000 }));
    await assertFails(aliceDb.collection("advertisingCampaigns").doc("y").set({ ...base, budgetMinorUnits: -5000 }));
    await assertFails(aliceDb.collection("advertisingCampaigns").doc("z").set({ ...base, budgetMinorUnits: 5000, impressions: 1e6 }));
  });

  test("Swift Flight collections are callable-only: clients cannot read or write game-owned records", async () => {
    const aliceDb = testEnv.authenticatedContext("alice").firestore();
    const adminClaimDb = testEnv.authenticatedContext("admin-user", { admin: true }).firestore();
    const collectionDocs = [
      ["flightState", "alice", { uid: "alice", currentPoints: 10 }],
      ["flightEvents", "alice:FOLLOW:target", { uid: "alice", awardedPoints: 10 }],
      ["flightRateLimits", "alice:FOLLOW", { uid: "alice", sourceType: "FOLLOW", count: 1 }],
      ["flightHistory", "flight-history-alice", { uid: "alice", flightNumber: 1 }],
    ] as const;

    await testEnv.withSecurityRulesDisabled(async (context) => {
      const adminDb = context.firestore();
      for (const [collection, id, data] of collectionDocs) {
        await adminDb.collection(collection).doc(id).set(data);
      }
    });

    for (const [collection, id, data] of collectionDocs) {
      // Even the owner and an account carrying the admin custom claim must use
      // the server callable boundary; these records are not direct-client APIs.
      await assertFails(aliceDb.collection(collection).doc(id).get());
      await assertFails(aliceDb.collection(collection).doc(id).set({ ...data, forged: true }));
      await assertFails(adminClaimDb.collection(collection).doc(id).get());
      await assertFails(adminClaimDb.collection(collection).doc(id).set({ ...data, forged: true }));
    }
  });

});
