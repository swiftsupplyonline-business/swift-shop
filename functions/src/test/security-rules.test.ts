import {
  assertFails,
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
});
