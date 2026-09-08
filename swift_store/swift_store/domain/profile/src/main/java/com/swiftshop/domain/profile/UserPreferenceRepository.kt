package com.swiftshop.domain.profile

import kotlinx.coroutines.flow.Flow

interface UserPreferenceRepository {
    fun getPreference(uid: String, key: String): Flow<Any?>
    suspend fun setPreference(uid: String, key: String, value: Any)
    fun getAllPreferences(uid: String): Flow<Map<String, Any>>
}
