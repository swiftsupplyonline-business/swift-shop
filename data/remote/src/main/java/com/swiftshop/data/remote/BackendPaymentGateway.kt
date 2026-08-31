package com.swiftshop.data.remote

import com.swiftshop.core.network.PaymentGateway
import com.swiftshop.core.network.PaymentInitRequest
import com.swiftshop.core.network.PaymentInitResponse
import com.swiftshop.core.network.PaymentVerifyResponse
import com.swiftshop.core.network.SwiftBackendApi
import com.swiftshop.core.network.WithdrawalRequest
import com.swiftshop.core.network.WithdrawalResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real `:data:remote` implementation of [PaymentGateway] — routes every call
 * through `SwiftBackendApi`, i.e. through the Swift Backend Cloud Functions.
 *
 * This exists to fix a self-contradiction that was already sitting in
 * `core/network/di/NetworkModule.kt`: it comments "In production: return
 * MopayAdapter(baseUrl = BuildConfig.MOPAY_BASE_URL, publicKey =
 * BuildConfig.MOPAY_PUBLIC_KEY)" — i.e. talking to Mopay *directly* from the
 * client — while the very same file's `SwiftBackendApi` interface is
 * annotated "Payments — proxied; secrets never in client", and D-003/D-006
 * in the canonical decision register make the backend the sole financial
 * authority. A direct-to-Mopay client adapter would have violated that
 * decision (and required a real public key baked into the APK). This
 * adapter is the version consistent with the ecosystem's own contract: the
 * client only ever calls the backend, the backend talks to Mopay.
 *
 * Still open, deliberately not invented here: the actual Mopay merchant
 * account/API contract on the *backend* side — that's server code outside
 * this Android project and isn't something to fabricate client-side.
 */
@Singleton
class BackendPaymentGateway @Inject constructor(
    private val api: SwiftBackendApi
) : PaymentGateway {

    override suspend fun initiatePayment(request: PaymentInitRequest): Result<PaymentInitResponse> =
        runCatching {
            val response = api.initiatePayment(request)
            response.body()?.takeIf { response.isSuccessful }
                ?: throw IllegalStateException("Payment initiation failed: HTTP ${response.code()}")
        }

    override suspend fun verifyPayment(paymentId: String): Result<PaymentVerifyResponse> =
        runCatching {
            val response = api.verifyPayment(paymentId)
            response.body()?.takeIf { response.isSuccessful }
                ?: throw IllegalStateException("Payment verification failed: HTTP ${response.code()}")
        }

    override suspend fun initiateWithdrawal(request: WithdrawalRequest): Result<WithdrawalResponse> =
        runCatching {
            val response = api.initiateWithdrawal(request)
            response.body()?.takeIf { response.isSuccessful }
                ?: throw IllegalStateException("Withdrawal initiation failed: HTTP ${response.code()}")
        }

    override suspend fun verifyWithdrawal(withdrawalId: String): Result<WithdrawalResponse> =
        runCatching {
            val response = api.verifyWithdrawal(withdrawalId)
            response.body()?.takeIf { response.isSuccessful }
                ?: throw IllegalStateException("Withdrawal verification failed: HTTP ${response.code()}")
        }
}
