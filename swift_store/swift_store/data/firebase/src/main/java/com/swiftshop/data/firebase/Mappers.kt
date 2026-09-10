package com.swiftshop.data.firebase

fun tsToLong(v: Any?): Long = when (v) {
    is com.google.firebase.Timestamp -> v.toDate().time
    is java.util.Date -> v.time
    is Long -> v
    is Number -> v.toLong()
    is Map<*, *> -> {
        val seconds = (v["seconds"] as? Number)?.toLong() ?: 0L
        val nanoseconds = (v["nanoseconds"] as? Number)?.toInt() ?: 0
        seconds * 1000 + nanoseconds / 1000000
    }
    else -> 0L
}

inline fun <reified T : Enum<T>> safeEnumValueOf(
    name: String?,
    default: T? = null
): T? {
    if (name.isNullOrBlank()) return default
    return try {
        java.lang.Enum.valueOf(T::class.java, name)
    } catch (e: Exception) {
        // Handle legacy or unknown values gracefully
        when (T::class.java) {
            com.swiftshop.core.model.FulfillmentType::class.java -> {
                if (name == "SERVICE") return com.swiftshop.core.model.FulfillmentType.AT_PROVIDER as T
            }
        }
        null
    }
}
