package com.swiftshop.core.network

import com.swiftshop.core.model.MoneyAmount
import com.swiftshop.core.model.PaymentMethod
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// ─── Payment Gateway Abstraction ─────────────────────────────────────────────
// The client never holds secret payment credentials.
// All sensitive calls are proxied through the Swift Backend (Cloud Functions).

// DEPRECATED: Consolidating on Firebase Functions SDK for core logic.
// See SWIFT_SHOP_PHASE8_BASELINE.md
@Deprecated("Use Firebase Functions (getHttpsCallable) for authoritative logic.")
interface PaymentGateway {
    suspend fun initiatePayment(request: PaymentInitRequest): Result<PaymentInitResponse>
    suspend fun verifyPayment(paymentId: String): Result<PaymentVerifyResponse>
    suspend fun initiateWithdrawal(request: WithdrawalRequest): Result<WithdrawalResponse>
    suspend fun verifyWithdrawal(withdrawalId: String): Result<WithdrawalResponse>
}

data class PaymentInitRequest(
    val orderId: String,
    val idempotencyKey: String,
    val amount: MoneyAmount,
    val method: PaymentMethod,
    val phoneNumber: String,
    val reference: String
)

data class PaymentInitResponse(
    val paymentId: String,
    val status: String,
    val redirectUrl: String?,
    val expiresAt: Long
)

data class PaymentVerifyResponse(
    val paymentId: String,
    val status: String,
    val completedAt: Long?
)

data class WithdrawalRequest(
    val userId: String,
    val idempotencyKey: String,
    val amount: MoneyAmount,
    val method: PaymentMethod,
    val destination: String
)

data class WithdrawalResponse(
    val withdrawalId: String,
    val status: String,
    val reference: String
)

// ─── Backend API ──────────────────────────────────────────────────────────────

// DEPRECATED: Consolidating on Firebase Functions SDK.
@Deprecated("Use Firebase Functions SDK.")
interface SwiftBackendApi {
    // Payments — proxied; secrets never in client
    @POST("payments/initiate")
    suspend fun initiatePayment(@Body request: PaymentInitRequest): Response<PaymentInitResponse>

    @GET("payments/{paymentId}/verify")
    suspend fun verifyPayment(@Path("paymentId") paymentId: String): Response<PaymentVerifyResponse>

    @POST("withdrawals/initiate")
    suspend fun initiateWithdrawal(@Body request: WithdrawalRequest): Response<WithdrawalResponse>

    @GET("withdrawals/{id}/verify")
    suspend fun verifyWithdrawal(@Path("id") id: String): Response<WithdrawalResponse>

    // Fee calculation — server-authoritative
    @POST("orders/calculate-fees")
    suspend fun calculateOrderFees(@Body request: FeeCalculationRequest): Response<FeeCalculationResponse>

    // Notifications
    @PUT("users/{uid}/fcm-token")
    suspend fun updateFcmToken(@Path("uid") uid: String, @Body body: FcmTokenBody): Response<Unit>
}

data class FeeCalculationRequest(
    val orderId: String,
    val items: List<OrderItemDto>,
    val deliveryAddress: AddressDto
)

data class FeeCalculationResponse(
    val subtotalMinorUnits: Long,
    val deliveryFeeMinorUnits: Long,
    val platformFeeMinorUnits: Long,
    val totalMinorUnits: Long,
    val currency: String
)

data class OrderItemDto(val listingId: String, val quantity: Int)
data class AddressDto(val lat: Double, val lng: Double, val district: String)
data class FcmTokenBody(val token: String, val platform: String = "android")

// ─── HTTP Client ──────────────────────────────────────────────────────────────

object NetworkFactory {

    fun createOkHttpClient(
        authTokenProvider: () -> String?,
        enableLogging: Boolean
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Auth interceptor — attaches Firebase ID token
            .addInterceptor(Interceptor { chain ->
                val token = authTokenProvider()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("X-App-Version", "1.0.0")
                        .build()
                } else chain.request()
                chain.proceed(request)
            })
            // Logging (debug only — no secrets logged)
            .apply {
                if (enableLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BODY
                        // Redact sensitive headers
                        redactHeader("Authorization")
                        redactHeader("X-Api-Key")
                    })
                }
            }
            .build()
    }

    fun createBackendApi(baseUrl: String, client: OkHttpClient): SwiftBackendApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SwiftBackendApi::class.java)
}

// ─── Mock Payment Gateway (sandbox/dev) ──────────────────────────────────────

class MockPaymentGateway : PaymentGateway {
    override suspend fun initiatePayment(request: PaymentInitRequest): Result<PaymentInitResponse> =
        Result.success(
            PaymentInitResponse(
                paymentId = "mock_pay_${System.currentTimeMillis()}",
                status = "PENDING",
                redirectUrl = null,
                expiresAt = System.currentTimeMillis() + 300_000
            )
        )

    override suspend fun verifyPayment(paymentId: String): Result<PaymentVerifyResponse> =
        Result.success(
            PaymentVerifyResponse(
                paymentId = paymentId,
                status = "COMPLETED",
                completedAt = System.currentTimeMillis()
            )
        )

    override suspend fun initiateWithdrawal(request: WithdrawalRequest): Result<WithdrawalResponse> =
        Result.success(
            WithdrawalResponse(
                withdrawalId = "mock_wd_${System.currentTimeMillis()}",
                status = "PROCESSING",
                reference = "MOCK-REF-${request.userId.take(6).uppercase()}"
            )
        )

    override suspend fun verifyWithdrawal(withdrawalId: String): Result<WithdrawalResponse> =
        Result.success(
            WithdrawalResponse(withdrawalId = withdrawalId, status = "COMPLETED", reference = "")
        )
}
