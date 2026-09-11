package com.swiftshop.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "swift_shop_prefs")

@Singleton
class PreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    object Keys {
        val BIOMETRICS_ENABLED = booleanPreferencesKey("biometrics_enabled")
        val LAST_SYNCED_AT = longPreferencesKey("last_synced_at")
        val ACTIVE_PAYMENT_ORDER_ID = stringPreferencesKey("active_payment_order_id")
    }

    val biometricsEnabled: Flow<Boolean> = dataStore.data.map { it[Keys.BIOMETRICS_ENABLED] ?: false }
    val lastSyncedAt: Flow<Long> = dataStore.data.map { it[Keys.LAST_SYNCED_AT] ?: 0L }
    val activePaymentOrderId: Flow<String?> = dataStore.data.map { it[Keys.ACTIVE_PAYMENT_ORDER_ID] }

    suspend fun setBiometricsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIOMETRICS_ENABLED] = enabled }
    }

    suspend fun setLastSyncedAt(timestamp: Long) {
        dataStore.edit { it[Keys.LAST_SYNCED_AT] = timestamp }
    }

    suspend fun setActivePaymentOrderId(orderId: String?) {
        dataStore.edit { 
            if (orderId == null) it.remove(Keys.ACTIVE_PAYMENT_ORDER_ID)
            else it[Keys.ACTIVE_PAYMENT_ORDER_ID] = orderId
        }
    }


    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
