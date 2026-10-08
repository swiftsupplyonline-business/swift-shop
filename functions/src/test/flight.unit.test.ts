import * as assert from "assert";
import * as admin from "firebase-admin";

admin.initializeApp();

const {
    ANTI_FARMING,
    ALTITUDE_LEVELS,
    BASE_REWARDS,
    FEED_COST,
    REDEMPTION_THRESHOLD,
    altitudeForFlight,
    eventIdempotencyKey,
    initialFlightState,
} = require("../flight") as typeof import("../flight");

let passed = 0;
let failed = 0;

function test(name: string, fn: () => void) {
    try {
        fn();
        console.log(`  ✅ ${name}`);
        passed++;
    } catch (error: any) {
        console.error(`  ❌ ${name}: ${error.message}`);
        failed++;
    }
}

console.log("\nSwift Flight — pure unit tests\n");

// ── Altitude ──────────────────────────────────────────────────────────────────

test("level 1 at 0 flight points", () => {
    assert.strictEqual(altitudeForFlight(0).level, 1);
});

test("level 2 at 2000 flight points", () => {
    assert.strictEqual(altitudeForFlight(2_000).level, 2);
});

test("level 5 at 28000 flight points", () => {
    assert.strictEqual(altitudeForFlight(28_000).level, 5);
});

test("level 10 at 98000 flight points", () => {
    assert.strictEqual(altitudeForFlight(98_000).level, 10);
});

test("level 10 remains the ceiling above redemption threshold", () => {
    assert.strictEqual(altitudeForFlight(150_000).level, 10);
});

test("level 1 multiplier is 1.0x", () => {
    assert.strictEqual(altitudeForFlight(0).multiplier, 1.0);
});

test("level 9 multiplier is 5.0x", () => {
    assert.strictEqual(altitudeForFlight(92_000).multiplier, 5.0);
});

// ── Reward curve ──────────────────────────────────────────────────────────────

function awardedPoints(sourceType: string, flightPoints: number): number {
    const base = BASE_REWARDS[sourceType];
    assert.ok(base !== undefined, `missing base reward for ${sourceType}`);
    return Math.round(base * altitudeForFlight(flightPoints).multiplier);
}

test("SUCCESSFUL_SALE at level 1 = 800 points", () => {
    assert.strictEqual(awardedPoints("SUCCESSFUL_SALE", 0), 800);
});

test("SUCCESSFUL_SALE at level 9 = 4000 points", () => {
    assert.strictEqual(awardedPoints("SUCCESSFUL_SALE", 92_000), 4000);
});

test("CREATE_LISTING at level 5 = 300 points", () => {
    assert.strictEqual(awardedPoints("CREATE_LISTING", 28_000), 300);
});

test("FOLLOW at level 1 = 10 points", () => {
    assert.strictEqual(awardedPoints("FOLLOW", 0), 10);
});

test("SMART_LINK is intentionally unsupported", () => {
    assert.strictEqual(BASE_REWARDS.SMART_LINK, undefined);
});

test("unsupported event cannot be awarded", () => {
    assert.strictEqual(BASE_REWARDS.FAKE_EVENT, undefined);
});

// ── Anti-farming policy ───────────────────────────────────────────────────────

test("FOLLOW has a 20-per-24h cap", () => {
    assert.strictEqual(ANTI_FARMING.FOLLOW.maxPerDay, 20);
});

test("NEW_FOLLOWER has a 200-per-24h cap", () => {
    assert.strictEqual(ANTI_FARMING.NEW_FOLLOWER.maxPerDay, 200);
});

test("CREATE_POST has a 5-per-24h cap", () => {
    assert.strictEqual(ANTI_FARMING.CREATE_POST.maxPerDay, 5);
});

test("CREATE_LISTING has a 10-per-24h cap", () => {
    assert.strictEqual(ANTI_FARMING.CREATE_LISTING.maxPerDay, 10);
});

test("SUCCESSFUL_SALE has a 500-per-24h cap", () => {
    assert.strictEqual(ANTI_FARMING.SUCCESSFUL_SALE.maxPerDay, 500);
});

// ── Relationship idempotency ─────────────────────────────────────────────────

test("FOLLOW reward key is stable per followed user", () => {
    const first = eventIdempotencyKey("alice", "FOLLOW", "followDocA", "bob");
    const second = eventIdempotencyKey("alice", "FOLLOW", "followDocB", "bob");
    assert.strictEqual(first, second);
});

test("NEW_FOLLOWER reward key is stable per follower", () => {
    const first = eventIdempotencyKey("bob", "NEW_FOLLOWER", "followDocA", "alice");
    const second = eventIdempotencyKey("bob", "NEW_FOLLOWER", "followDocB", "alice");
    assert.strictEqual(first, second);
});

test("different follow targets have different reward keys", () => {
    const bob = eventIdempotencyKey("alice", "FOLLOW", "docA", "bob");
    const carol = eventIdempotencyKey("alice", "FOLLOW", "docB", "carol");
    assert.notStrictEqual(bob, carol);
});

test("ordinary event keys remain source-document scoped", () => {
    const key = eventIdempotencyKey("seller", "SUCCESSFUL_SALE", "order123");
    assert.strictEqual(key, "seller:SUCCESSFUL_SALE:order123");
});

// ── State naming / reset semantics ────────────────────────────────────────────

test("initial state uses flightPointsEarned, not lifetime naming", () => {
    const state = initialFlightState("user123", {} as any);
    assert.strictEqual(state.flightPointsEarned, 10);
    assert.strictEqual((state as any).totalPointsEarned, undefined);
});

test("feed costs 20 points", () => {
    assert.strictEqual(FEED_COST, 20);
});

test("redemption threshold is 100000", () => {
    assert.strictEqual(REDEMPTION_THRESHOLD, 100_000);
});

test("altitude table contains exactly ten levels", () => {
    assert.strictEqual(ALTITUDE_LEVELS.length, 10);
});

console.log(`\n${passed + failed} tests — ${passed} passed, ${failed} failed\n`);
if (failed > 0) process.exit(1);
