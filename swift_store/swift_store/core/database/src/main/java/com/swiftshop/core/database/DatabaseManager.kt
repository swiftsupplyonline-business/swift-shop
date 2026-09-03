package com.swiftshop.core.database

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseManager @Inject constructor(
    private val database: SwiftShopDatabase
) {
    suspend fun clearAllCaches() {
        database.clearAllTables()
    }
}
