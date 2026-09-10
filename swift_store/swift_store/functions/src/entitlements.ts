export enum UserTier {
    BASIC = "BASIC",
    PREMIUM = "PREMIUM",
    ELITE = "ELITE"
}

export enum ExposureLevel {
    STANDARD = "STANDARD",
    ENHANCED = "ENHANCED",
    MAXIMUM = "MAXIMUM"
}

export enum AnalyticsLevel {
    BASIC = "BASIC",
    ADVANCED = "ADVANCED",
    FULL = "FULL"
}

export interface MerchantEntitlement {
    tier: UserTier;
    maxShops: number;
    includedListingsPerShop: number;
    totalIncludedListings: number;
    additionalListingFeeMinorUnits: number;
    monthlyFeeMinorUnits: number;
    internalPromotionAllowance: number;
    internalPromotionUnlimited: boolean;
    externalPromotionAllowance: number;
    externalPromotionUnlimited: boolean;
    exposureLevel: ExposureLevel;
    salesAnalyticsLevel: AnalyticsLevel;
    advertAnalyticsEnabled: boolean;
}

export const TIER_ENTITLEMENTS: Record<UserTier, MerchantEntitlement> = {
    [UserTier.BASIC]: {
        tier: UserTier.BASIC,
        maxShops: 3,
        includedListingsPerShop: 10,
        totalIncludedListings: 10,
        additionalListingFeeMinorUnits: 500,
        monthlyFeeMinorUnits: 0,
        internalPromotionAllowance: 1,
        internalPromotionUnlimited: false,
        externalPromotionAllowance: 0,
        externalPromotionUnlimited: false,
        exposureLevel: ExposureLevel.STANDARD,
        salesAnalyticsLevel: AnalyticsLevel.BASIC,
        advertAnalyticsEnabled: false
    },
    [UserTier.PREMIUM]: {
        tier: UserTier.PREMIUM,
        maxShops: 5,
        includedListingsPerShop: 50,
        totalIncludedListings: 50,
        additionalListingFeeMinorUnits: 500,
        monthlyFeeMinorUnits: 9900,
        internalPromotionAllowance: -1,
        internalPromotionUnlimited: true,
        externalPromotionAllowance: 10,
        externalPromotionUnlimited: false,
        exposureLevel: ExposureLevel.ENHANCED,
        salesAnalyticsLevel: AnalyticsLevel.ADVANCED,
        advertAnalyticsEnabled: true
    },
    [UserTier.ELITE]: {
        tier: UserTier.ELITE,
        maxShops: -1,
        includedListingsPerShop: -1,
        totalIncludedListings: -1,
        additionalListingFeeMinorUnits: 0,
        monthlyFeeMinorUnits: 49900,
        internalPromotionAllowance: -1,
        internalPromotionUnlimited: true,
        externalPromotionAllowance: 50,
        externalPromotionUnlimited: false,
        exposureLevel: ExposureLevel.MAXIMUM,
        salesAnalyticsLevel: AnalyticsLevel.FULL,
        advertAnalyticsEnabled: true
    }
};

export function resolveEntitlement(tier: string): MerchantEntitlement {
    const t = (tier as UserTier) || UserTier.BASIC;
    return TIER_ENTITLEMENTS[t] || TIER_ENTITLEMENTS[UserTier.BASIC];
}

export function getCurrentWeeklyPeriod(): { start: number, end: number } {
    const now = new Date();
    const day = now.getUTCDay(); // 0 (Sun) to 6 (Sat)
    const mondayDiff = (day === 0 ? -6 : 1) - day;

    const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + mondayDiff, 0, 0, 0, 0));
    const end = new Date(start.getTime() + 7 * 24 * 60 * 60 * 1000);

    return { start: start.getTime(), end: end.getTime() };
}
