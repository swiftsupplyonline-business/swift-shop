package com.swiftshop.core.database.di

import android.content.Context
import androidx.room.Room
import com.swiftshop.core.database.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SwiftShopDatabase =
        Room.databaseBuilder(
            context,
            SwiftShopDatabase::class.java,
            "swiftshop.db"
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideListingDao(db: SwiftShopDatabase): ListingDao = db.listingDao()
    @Provides fun provideShopDao(db: SwiftShopDatabase): ShopDao = db.shopDao()
    @Provides fun providePostDao(db: SwiftShopDatabase): PostDao = db.postDao()
    @Provides fun provideOrderDao(db: SwiftShopDatabase): OrderDao = db.orderDao()
    @Provides fun provideMessageDao(db: SwiftShopDatabase): MessageDao = db.messageDao()
    @Provides fun provideWalletDao(db: SwiftShopDatabase): WalletTransactionDao = db.walletTransactionDao()
}
