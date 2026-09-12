package com.swiftshop.data.firebase

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.swiftshop.core.model.*
import com.swiftshop.domain.commerce.ContextEngine
import com.swiftshop.domain.commerce.WorkflowContext
import com.swiftshop.domain.profile.UserPreferenceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseContextEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val preferenceRepository: UserPreferenceRepository
) : ContextEngine {

    private val json = Json { ignoreUnknownKeys = true }
    
    private val marketContextFlow = flow {
        val maseruConfig = context.assets.open("market_maseru.json").bufferedReader().use { it.readText() }
        val market = json.decodeFromString<MarketContext>(maseruConfig)
        emit(market)
    }.shareIn(
        scope = kotlinx.coroutines.GlobalScope, // Acceptable for app-wide config cache
        started = SharingStarted.Eagerly,
        replay = 1
    )

    override fun getActiveMarketContext(): Flow<MarketContext> = marketContextFlow

    override suspend fun <T> getSmartDefault(
        field: String,
        currentContext: WorkflowContext,
        fallback: T
    ): SmartDefault<T> {
        val uid = auth.currentUser?.uid
        val market = marketContextFlow.first()
        
        // 1. Check User Preference (Tier 3)
        if (uid != null) {
            val pref = preferenceRepository.getPreference(uid, "pref_$field").first()
            if (pref != null) {
                return SmartDefault(field, pref as T, DefaultSource.USER_PREFERENCE)
            }
        }

        // 2. Check Contextual Suggestion (Tier 2)
        if (currentContext.categoryId != null) {
            val category = market.categories.find { it.id == currentContext.categoryId }
            if (category != null) {
                // Field-specific logic
                val contextualValue = when (field) {
                    "unit" -> category.suggestedUnits.firstOrNull()
                    else -> null
                }
                if (contextualValue != null) {
                    return SmartDefault(field, contextualValue as T, DefaultSource.CONTEXTUAL, category = category.id)
                }
            }
        }

        // 3. Market Default (Tier 1)
        val marketValue: Any? = when (field) {
            "currency" -> market.currency
            "country" -> market.country
            "city" -> market.region.replace(" District", "")
            "unit" -> market.defaultUnits.firstOrNull()
            else -> null
        }

        if (marketValue != null) {
            return SmartDefault(field, marketValue as T, DefaultSource.MARKET_DEFAULT, geography = market.id)
        }

        // 4. Global Fallback
        return SmartDefault(field, fallback, DefaultSource.GLOBAL_FALLBACK)
    }

    override suspend fun recordUserChoice(field: String, value: Any, context: WorkflowContext) {
        val uid = auth.currentUser?.uid ?: return
        // We only persist preferences that deviate from market defaults or are worth learning
        preferenceRepository.setPreference(uid, "pref_$field", value)
    }
}
