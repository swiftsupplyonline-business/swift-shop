import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import * as fs from "fs";
import * as path from "path";

// Needs the Firestore emulator (run via `firebase emulators:exec --only firestore "npx jest follow-rules"`).
describe("Firestore rules: follows", () => {
  let testEnv: RulesTestEnvironment;

  beforeAll(async () => {
    const rulesPath = path.resolve(__dirname, "../../../docs/firestore.rules");
    testEnv = await initializeTestEnvironment({
      projectId: "swift-shop-follow-rules",
      firestore: { rules: fs.readFileSync(rulesPath, "utf8") },
    });
  });

  afterAll(async () => {
    await testEnv.cleanup();
  });

  beforeEach(async () => {
    await testEnv.clearFirestore();
  });

  const follow = (followerId: string, followedId: unknown) => ({ followerId, followedId, createdAt: 1 });

  test("a user can follow another using the {followerId}_{followedId} id the Android app writes", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(db.collection("follows").doc("alice_bob").set(follow("alice", "bob")));
  });

  test("a follow under any other document id is refused (one document per pair)", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("follows").doc("second-copy").set(follow("alice", "bob")));
    await assertFails(db.collection("follows").doc("bob_alice").set(follow("alice", "bob")));
  });

  test("a user cannot follow themselves", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("follows").doc("alice_alice").set(follow("alice", "alice")));
  });

  test("a user cannot create a follow on behalf of someone else", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("follows").doc("bob_carol").set(follow("bob", "carol")));
  });

  test("followedId must be a string", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("follows").doc("alice_undefined").set(follow("alice", 42)));
  });

  test("signed-out users cannot follow", async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(db.collection("follows").doc("alice_bob").set(follow("alice", "bob")));
  });

  test("an existing follow cannot be rewritten to point at someone else", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().collection("follows").doc("alice_bob").set(follow("alice", "bob"));
    });
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(db.collection("follows").doc("alice_bob").set(follow("alice", "carol")));
  });

  test("only the follower can remove a follow", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().collection("follows").doc("alice_bob").set(follow("alice", "bob"));
    });
    await assertFails(testEnv.authenticatedContext("bob").firestore().collection("follows").doc("alice_bob").delete());
    await assertSucceeds(testEnv.authenticatedContext("alice").firestore().collection("follows").doc("alice_bob").delete());
  });
});
