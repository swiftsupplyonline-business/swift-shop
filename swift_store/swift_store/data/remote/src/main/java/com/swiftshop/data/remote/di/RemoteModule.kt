package com.swiftshop.data.remote.di

import com.swiftshop.core.network.MockPaymentGateway
import com.swiftshop.core.network.PaymentGateway
import com.swiftshop.data.remote.BackendPaymentGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MockGateway

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackendGateway

/**
 * Exposes both PaymentGateway implementations under qualifiers instead of
 * picking one here. :data:remote doesn't reliably know the app's real build
 * flavor (core:network's own BuildConfig.FLAVOR is hardcoded to "dev" in its
 * defaultConfig — a pre-existing inconsistency, not something to build
 * further logic on top of). The actual dev-vs-real choice is made once, in
 * app/di/AppModule.kt, where BuildConfig.FLAVOR is unambiguous because it's
 * :app's own.
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

    @Provides
    @Singleton
    @MockGateway
    fun provideMockPaymentGateway(): PaymentGateway = MockPaymentGateway()

    @Provides
    @Singleton
    @BackendGateway
    fun provideBackendPaymentGateway(impl: BackendPaymentGateway): PaymentGateway = impl
}
