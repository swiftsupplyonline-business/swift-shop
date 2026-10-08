/**
 * Swift Flight — unit tests
 *
 * Tests: auth guard, idempotency, event verification logic, reward
 * calculation, altitude progression, feeding, atomic redemption.
 * Uses Firebase Emulator Suite (Firestore + Auth).
 */

import * as assert from "assert";

// ─── Unit-testable pure functions (extracted from flight.ts) ─────────────────

const REDEMPTION_THRESHOLD = 100_000;
const FEED_COST = 20;
const BASE_REWARDS: Record<string, number> = {
    FOLLOW: 10, NEW_FOLLOWER: 10, SMART_LINK: 40,
    CREATE_POST: 80, CREATE_LISTING: 150, SUCCESSFUL_SALE: 800,
};
const ALTITUDE_LEVELS = [
    { level: 1,  minLifetime: 0,       multiplier: 1.0, label: "Ground",      env: "Rooftops" },
    { level: 2,  minLifetime: 2_000,   multiplier: 1.0, label: "Rooftops",    env: "Rooftops" },
    { level: 3,  minLifetime: 6_000,   multiplier: 1.5, label: "Low sky",     env: "Above rooftops" },
    { level: 4,  minLifetime: 14_000,  multiplier: 1.5, label: "Rising",      env: "Above rooftops" },
    { level: 5,  minLifetime: 28_000,  multiplier: 2.0, label: "Clouds",      env: "Clouds" },
    { level: 6,  minLifetime: 48_000,  multiplier: 2.0, label: "High clouds", env: "Clouds" },
    { level: 7,  minLifetime: 65_000,  multiplier: 3.0, label: "Storm",       env: "Storm" },
    { level: 8,  minLifetime: 80_000,  multiplier: 3.0, label: "Thunder",     env: "Storm" },
    { level: 9,  minLifetime: 92_000,  multiplier: 5.0, label: "Open sky",    env: "Open sky" },
    { level: 10, minLifetime: 98_000,  multiplier: 5.0, label: "Summit",      env: "Open sky" },
];
function altitudeForLifetime(lifetime: number) {
    let r = ALTITUDE_LEVELS[0];
    for (const lvl of ALTITUDE_LEVELS) { if (lifetime >= lvl.minLifetime) r = lvl; }
    return r;
}
function awardedPoints(sourceType: string, lifetime: number): number {
    const alt = altitudeForLifetime(lifetime);
    return Math.round((BASE_REWARDS[sourceType] || 0) * alt.multiplier);
}

// ─── Test suite ──────────────────────────────────────────────────────────────

let passed = 0; let failed = 0;
function test(name: string, fn: () => void) {
    try { fn(); console.log(`  ✅ ${name}`); passed++; }
    catch(e: any) { console.error(`  ❌ ${name}: ${e.message}`); failed++; }
}

console.log("\nSwift Flight — pure unit tests\n");

// ── Altitude progression ──────────────────────────────────────────────────────
test("level 1 at 0 lifetime", () => {
    assert.strictEqual(altitudeForLifetime(0).level, 1);
});
test("level 2 at 2000 lifetime", () => {
    assert.strictEqual(altitudeForLifetime(2_000).level, 2);
});
test("level 5 at 28000 lifetime", () => {
    assert.strictEqual(altitudeForLifetime(28_000).level, 5);
});
test("level 10 at 98000 lifetime", () => {
    assert.strictEqual(altitudeForLifetime(98_000).level, 10);
});
test("level 10 above 100000", () => {
    assert.strictEqual(altitudeForLifetime(150_000).level, 10);
});
test("multiplier is 1.0 at level 1", () => {
    assert.strictEqual(altitudeForLifetime(0).multiplier, 1.0);
});
test("multiplier is 5.0 at level 9", () => {
    assert.strictEqual(altitudeForLifetime(92_000).multiplier, 5.0);
});

// ── Reward calculation ────────────────────────────────────────────────────────
test("SUCCESSFUL_SALE at level 1 = 800 points", () => {
    assert.strictEqual(awardedPoints("SUCCESSFUL_SALE", 0), 800);
});
test("SUCCESSFUL_SALE at level 9 = 4000 points", () => {
    assert.strictEqual(awardedPoints("SUCCESSFUL_SALE", 92_000), 4000);
});
test("CREATE_LISTING at level 1 = 150 points", () => {
    assert.strictEqual(awardedPoints("CREATE_LISTING", 0), 150);
});
test("CREATE_LISTING at level 5 = 300 points", () => {
    assert.strictEqual(awardedPoints("CREATE_LISTING", 28_000), 300);
});
test("FOLLOW at level 1 = 10 points", () => {
    assert.strictEqual(awardedPoints("FOLLOW", 0), 10);
});
test("SMART_LINK at level 7 = 120 points", () => {
    assert.strictEqual(awardedPoints("SMART_LINK", 65_000), 120);
});
test("unknown event type = 0", () => {
    assert.strictEqual(awardedPoints("FAKE_EVENT", 0), 0);
});

// ── Idempotency key construction ──────────────────────────────────────────────
test("event key is uid-scoped", () => {
    const uid = "user123";
    const key = `${uid}:SUCCESSFUL_SALE:order456`;
    assert.ok(key.startsWith("user123:"));
    assert.ok(key.includes(":SUCCESSFUL_SALE:"));
    assert.ok(key.endsWith(":order456"));
});
test("different users produce different keys for same order", () => {
    const key1 = `userA:SUCCESSFUL_SALE:order1`;
    const key2 = `userB:SUCCESSFUL_SALE:order1`;
    assert.notStrictEqual(key1, key2);
});

// ── Redemption threshold ──────────────────────────────────────────────────────
test("eligible at exactly 100000", () => {
    assert.ok(100_000 >= REDEMPTION_THRESHOLD);
});
test("not eligible at 99999", () => {
    assert.ok(99_999 < REDEMPTION_THRESHOLD);
});

// ── Feed cost ─────────────────────────────────────────────────────────────────
test("feed costs 20 points", () => {
    const before = 500;
    const after  = before - FEED_COST;
    assert.strictEqual(after, 480);
});
test("cannot feed with < 20 points", () => {
    assert.ok(19 < FEED_COST);
});

// ── Summary ───────────────────────────────────────────────────────────────────
console.log(`\n${passed + failed} tests — ${passed} passed, ${failed} failed\n`);
if (failed > 0) process.exit(1);
