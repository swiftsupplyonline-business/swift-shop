package com.swiftshop.core.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class MarketContext(
    val id: String = "", // e.g., "maseru"
    val country: String = "", // e.g., "Lesotho"
    val currency: String = "", // e.g., "LSL"
    val region: String = "", // e.g., "Maseru District"
    val availableDistricts: List<String> = emptyList(),
    val categories: List<CategorySchema> = emptyList(),
    val defaultUnits: List<String> = emptyList(),
    val metadata: MarketMetadata = MarketMetadata()
) : Parcelable

@Serializable
@Parcelize
data class MarketMetadata(
    val source: String = "",
    val sourceYear: String = "",
    val geography: String = "",
    val confidence: Float = 0f,
    val lastUpdated: Long = 0L
) : Parcelable

@Serializable
@Parcelize
data class CategorySchema(
    val id: String = "",
    val label: String = "",
    val subcategories: List<String> = emptyList(),
    val suggestedFields: List<FieldSchema> = emptyList(),
    val suggestedUnits: List<String> = emptyList(),
    val postTemplates: List<PostTemplate> = emptyList()
) : Parcelable

@Serializable
@Parcelize
data class FieldSchema(
    val id: String = "",
    val label: String = "",
    val type: String = "text", // text, number, dropdown
    val options: List<String> = emptyList(),
    val isRequired: Boolean = false,
    val unit: String? = null
) : Parcelable

@Serializable
@Parcelize
data class PostTemplate(
    val id: String = "",
    val label: String = "",
    val prompt: String = ""
) : Parcelable

@Serializable
@Parcelize
data class SmartDefault<T>(
    val field: String,
    val value: @RawValue T,
    val source: DefaultSource,
    val confidence: Float = 1.0f,
    val geography: String? = null,
    val category: String? = null,
    val sourceVersion: String? = null
) : Parcelable

enum class DefaultSource {
    USER_EXPLICIT,
    USER_PREFERENCE,
    USER_BEHAVIOR,
    CONTEXTUAL,
    MARKET_DEFAULT,
    GLOBAL_FALLBACK
}
