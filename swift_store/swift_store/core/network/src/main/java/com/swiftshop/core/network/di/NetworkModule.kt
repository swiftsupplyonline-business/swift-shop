package com.swiftshop.core.network.di

import com.google.firebase.auth.FirebaseAuth
import com.swiftshop.core.network.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(auth: FirebaseAuth): okhttp3.OkHttpClient =
        NetworkFactory.createOkHttpClient(
            authTokenProvider = {
                runBlocking {
                    runCatching {
                        auth.currentUser?.getIdToken(false)?.await()?.token
                    }.getOrNull()
                }
            },
            enableLogging = BuildConfig.ENABLE_LOGGING
        )

    @Provides
    @Singleton
    fun provideBackendApi(client: okhttp3.OkHttpClient): SwiftBackendApi =
        NetworkFactory.createBackendApi(
            baseUrl = BuildConfig.BACKEND_BASE_URL,
            client = client
        )

    // NOTE: PaymentGateway is bound in :data:remote (RemoteModule), not here.
    // core:network only defines the contract + the dev-only MockPaymentGateway;
    // the real backend-proxied implementation depends on SwiftBackendApi and
    // lives one layer up so core:network doesn't need to know about build
    // flavors making that choice. See BackendPaymentGateway.kt for why this
    // replaced the direct-to-Mopay adapter that was sketched here before.
}
