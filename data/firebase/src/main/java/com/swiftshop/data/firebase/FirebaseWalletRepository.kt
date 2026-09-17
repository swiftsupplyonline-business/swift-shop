package com.swiftshop.data.firebase

import java.util.Date
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.swiftshop.core.model.*
import com.swiftshop.domain.wallet.WalletRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private fun walletTsToLong(v: Any?): Long = when (v) {
    is com.google.firebase.Timestamp -> v.toDate().time
    is Date -> v.time
    is Long -> v
    is Number -> v.toLong()
    else -> 0L
}

@Singleton
class FirebaseWalletRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions
) : WalletRepository {

    override fun observeWallet(userId: String): Flow<Wallet> = callbackFlow {
        val subscription = firestore.collection("wallets").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val wallet = snapshot?.toObject(FirestoreWallet::class.java)?.toDomain(userId)
                    ?: Wallet(userId = userId)
                trySend(wallet)
            }
        awaitClose { subscription.remove() }
    }

    override fun observeTransactions(userId: String, limit: Int): Flow<List<WalletTransaction>> = callbackFlow {
        val subscription = firestore.collection("walletTransactions")
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val transactions = snapshot?.toObjects(FirestoreWalletTransaction::class.java)
                    ?.map { it.toDomain() } ?: emptyList()
                trySend(transactions)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun initiateDeposit(
        userId: String, amount: MoneyAmount, gateway: String, provider: String, phone: String, idempotencyKey: String
    ): Result<Map<String, String>> = runCatching {
        val data = mapOf(
            "amount" to amount.toFirestore(),
            "gateway" to gateway,
            "provider" to provider,
            "phoneNumber" to phone,
            "idempotencyKey" to idempotencyKey
        )
        val result = functions.getHttpsCallable("initiateDeposit").call(data).await()
        @Suppress("UNCHECKED_CAST") result.data as Map<String, String>
    }

    override suspend fun initiateWithdrawal(
        userId: String, amount: MoneyAmount, gateway: String, provider: String, destination: String, idempotencyKey: String
    ): Result<String> = runCatching {
        val data = mapOf(
            "amount" to amount.toFirestore(),
            "gateway" to gateway,
            "provider" to provider,
            "destination" to destination,
            "idempotencyKey" to idempotencyKey
        )
        val result = functions.getHttpsCallable("initiateWithdrawal").call(data).await()
        result.data as String
    }

    override suspend fun initiateP2PTransfer(
        fromUserId: String, toUserId: String, amount: MoneyAmount, idempotencyKey: String
    ): Result<String> = runCatching {
        val data = mapOf(
            "toUserId" to toUserId,
            "amount" to amount.toFirestore(),
            "idempotencyKey" to idempotencyKey
        )
        val result = functions.getHttpsCallable("initiateP2PTransfer").call(data).await()
        result.data as String
    }

    override suspend fun getTransactionById(transactionId: String): Result<WalletTransaction> = runCatching {
        val snapshot = firestore.collection("walletTransactions").document(transactionId).get().await()
        snapshot.toObject(FirestoreWalletTransaction::class.java)?.toDomain() 
            ?: throw NoSuchElementException("Transaction not found")
    }

    override suspend fun confirmDeposit(sessionId: String): Result<Map<String, String>> = runCatching {
        val data = mapOf("sessionId" to sessionId)
        val result = functions.getHttpsCallable("confirmDeposit").call(data).await()
        @Suppress("UNCHECKED_CAST") result.data as Map<String, String>
    }
}

// â”€â”€â”€ DTOs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

data class FirestoreWallet(
    val availableBalanceMinorUnits: Long = 0L,
    val pendingBalanceMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val updatedAt: Any? = null
) {
    fun toDomain(userId: String) = Wallet(
        id = userId,
        userId = userId,
        availableBalance = MoneyAmount(currency, availableBalanceMinorUnits),
        pendingBalance = MoneyAmount(currency, pendingBalanceMinorUnits),
        currency = currency,
        updatedAt = walletTsToLong(updatedAt)
    )
}

data class FirestoreWalletTransaction(
    val transactionId: String = "",
    val idempotencyKey: String = "",
    val userId: String = "",
    val type: String = "PURCHASE",
    val amountMinorUnits: Long = 0L,
    val feeMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val status: String = "PENDING",
    val reference: String = "",
    val description: String = "",
    val sourceAccount: String = "",
    val destinationAccount: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Any? = null,
    val completedAt: Any? = null
) {
    fun toDomain() = WalletTransaction(
        transactionId = transactionId,
        idempotencyKey = idempotencyKey,
        userId = userId,
        type = runCatching { TransactionType.valueOf(type) }.getOrDefault(TransactionType.PURCHASE),
        amount = MoneyAmount(currency, amountMinorUnits),
        fee = MoneyAmount(currency, feeMinorUnits),
        status = runCatching { TransactionStatus.valueOf(status) }.getOrDefault(TransactionStatus.PENDING),
        reference = reference,
        description = description,
        sourceAccount = sourceAccount,
        destinationAccount = destinationAccount,
        metadata = metadata,
        createdAt = walletTsToLong(createdAt),
        completedAt = walletTsToLong(completedAt)
    )
}

fun MoneyAmount.toFirestore() = mapOf(
    "minorUnits" to minorUnits,
    "currency" to currency
)
