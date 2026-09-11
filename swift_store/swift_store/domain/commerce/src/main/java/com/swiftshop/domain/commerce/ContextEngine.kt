package com.swiftshop.domain.commerce

import com.swiftshop.core.model.*
import kotlinx.coroutines.flow.Flow

interface ContextEngine {
    /**
     * Returns the active market context (e.g., Maseru).
     */
    fun getActiveMarketContext(): Flow<MarketContext>

    /**
     * Resolves a smart default for a specific field based on the hierarchy:
     * User Input > User Preference > Contextual > Market > Global.
     */
    suspend fun <T> getSmartDefault(
        field: String,
        currentContext: WorkflowContext,
        fallback: T
    ): SmartDefault<T>

    /**
     * Learns from a user's choice to update future defaults.
     */
    suspend fun recordUserChoice(field: String, value: Any, context: WorkflowContext)
}

data class WorkflowContext(
    val categoryId: String? = null,
    val shopId: String? = null,
    val locality: String? = null
)
