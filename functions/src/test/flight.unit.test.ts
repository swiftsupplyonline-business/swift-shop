import {
  calculateAltitudeBand,
  calculateAltitudeFromPoints,
  REWARD_RULES,
  REDEMPTION_TARGET_POINTS,
  FEED_POINT_COST
} from "../flight";

describe("Swift Flight Mechanics & Calculations", () => {
  test("Altitude bands calculate correctly across thresholds", () => {
    expect(calculateAltitudeBand(1)).toEqual({ band: "Ground / Rooftops", multiplier: 1.0 });
    expect(calculateAltitudeBand(2)).toEqual({ band: "Ground / Rooftops", multiplier: 1.0 });
    expect(calculateAltitudeBand(3)).toEqual({ band: "Above Rooftops", multiplier: 1.5 });
    expect(calculateAltitudeBand(5)).toEqual({ band: "Clouds", multiplier: 2.0 });
    expect(calculateAltitudeBand(7)).toEqual({ band: "Storm", multiplier: 3.0 });
    expect(calculateAltitudeBand(10)).toEqual({ band: "Open Sky", multiplier: 5.0 });
  });

  test("Altitude points progression formula", () => {
    expect(calculateAltitudeFromPoints(0)).toBe(1);
    expect(calculateAltitudeFromPoints(9999)).toBe(1);
    expect(calculateAltitudeFromPoints(10000)).toBe(2);
    expect(calculateAltitudeFromPoints(50000)).toBe(6);
    expect(calculateAltitudeFromPoints(100000)).toBe(10);
    expect(calculateAltitudeFromPoints(200000)).toBe(10);
  });

  test("Reward rules exist for all core activity types", () => {
    expect(REWARD_RULES.FOLLOW).toBeDefined();
    expect(REWARD_RULES.CREATE_LISTING).toBeDefined();
    expect(REWARD_RULES.SUCCESSFUL_SALE).toBeDefined();
    expect(REWARD_RULES.SUCCESSFUL_SALE.basePoints).toBeGreaterThanOrEqual(500);
  });

  test("Redemption target is set to 100,000 points", () => {
    expect(REDEMPTION_TARGET_POINTS).toBe(100000);
    expect(FEED_POINT_COST).toBe(20);
  });
});
