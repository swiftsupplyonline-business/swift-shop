package com.swiftshop.domain.commerce

import com.swiftshop.core.model.Listing

/**
 * Tells the UI whether the NEXT listing is covered by the tier's included quota or will carry the
 * additional-listing fee. Pure policy on [TierEntitlement] values (-1 means "no limit of that kind").
 *
 *  - BASIC   : counted per shop   (includedListingsPerShop)
 *  - PREMIUM : counted per account (totalIncludedListings)
 *  - ELITE   : unlimited
 *
 * This is a display/UX helper. Enforcement and charging stay server-side (functions/src/entitlements.ts).
 */
class CheckListingEligibilityUseCase {

    enum class Result { Included, AdditionalFeeRequired }

    operator fun invoke(
        entitlement: TierEntitlement,
        existingListings: List<Listing>,
        shopId: String
    ): Result = when {
        entitlement.includedListingsPerShop >= 0 ->
            if (existingListings.count { it.shopId == shopId } < entitlement.includedListingsPerShop)
                Result.Included else Result.AdditionalFeeRequired
        entitlement.totalIncludedListings >= 0 ->
            if (existingListings.size < entitlement.totalIncludedListings)
                Result.Included else Result.AdditionalFeeRequired
        else -> Result.Included
    }
}
