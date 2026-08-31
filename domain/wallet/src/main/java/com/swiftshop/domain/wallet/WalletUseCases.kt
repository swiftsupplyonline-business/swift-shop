package com.swiftshop.domain.wallet

import com.swiftshop.core.model.*
import kotlinx.coroutines.flow.Flow

// ─── Repository Interfaces ────────────────────────────────────────────────────

interface WalletRepository {
    fun observeWallet(userId: String): Flow<Wallet>
    fun observeTransactions(userId: String, limit: Int): Flow<List<WalletTransaction>>
    suspend fun initiateDeposit(userId: String, amount: MoneyAmount, gateway: String, provider: String, phone: String, idempotencyKey: String): Result<String>
    suspend fun initiateWithdrawal(userId: String, amount: MoneyAmount, gateway: String, provider: String, destination: String, idempotencyKey: String): Result<String>
    suspend fun initiateP2PTransfer(fromUserId: String, toUserId: String, amount: MoneyAmount, idempotencyKey: String): Result<String>
    suspend fun getTransactionById(transactionId: String): Result<WalletTransaction>
}

interface LedgerRepository {
    /**
     * All financial events are written as immutable double-entry ledger entries.
     * Balance is always derived server-side from ledger entries — never from UI state.
     */
    suspend fun postEntry(entry: LedgerEntry): Result<String>
    fun observeAccountEntries(accountId: String, limit: Int): Flow<List<LedgerEntry>>
    suspend fun getBalance(accountId: String): Result<MoneyAmount>
}

data class LedgerEntry(
    val id: String = "",
    val transactionId: String = "",
    val debitAccount: String = "",
    val creditAccount: String = "",
    val amount: MoneyAmount = MoneyAmount.ZERO,
    val reference: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

// ─── Constants ────────────────────────────────────────────────────────────────

object WalletRules {
    val MINIMUM_WITHDRAWAL = MoneyAmount("LSL", 20000L)  // M200.00
    const val P2P_FEE_PERCENT = 0.015 // 1.5%

    fun calculateP2PFee(amount: MoneyAmount): MoneyAmount =
        MoneyAmount(amount.currency, (amount.minorUnits * P2P_FEE_PERCENT).toLong())
}

// ─── Use Cases ────────────────────────────────────────────────────────────────

class ObserveWalletUseCase(private val repository: WalletRepository) {
    operator fun invoke(userId: String): Flow<Wallet> = repository.observeWallet(userId)
}

class ObserveTransactionsUseCase(private val repository: WalletRepository) {
    operator fun invoke(userId: String, limit: Int = 50): Flow<List<WalletTransaction>> =
        repository.observeTransactions(userId, limit)
}

class InitiateWithdrawalUseCase(private val repository: WalletRepository) {
    suspend operator fun invoke(
        userId: String, amount: MoneyAmount,
        gateway: String, provider: String, destination: String,
        idempotencyKey: String
    ): Result<String> {
        if (amount.minorUnits < WalletRules.MINIMUM_WITHDRAWAL.minorUnits) {
            return Result.failure(
                IllegalArgumentException(
                    "Minimum withdrawal is ${WalletRules.MINIMUM_WITHDRAWAL.toDisplayString()}"
                )
            )
        }
        if (destination.isBlank()) {
            return Result.failure(IllegalArgumentException("Destination account required"))
        }
        if (idempotencyKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Idempotency key required"))
        }
        return repository.initiateWithdrawal(userId, amount, gateway, provider, destination, idempotencyKey)
    }
}

class InitiateP2PTransferUseCase(private val repository: WalletRepository) {
    suspend operator fun invoke(
        fromUserId: String, toUserId: String, amount: MoneyAmount, idempotencyKey: String
    ): Result<String> {
        if (fromUserId == toUserId) return Result.failure(IllegalArgumentException("Cannot transfer to yourself"))
        if (amount.minorUnits <= 0) return Result.failure(IllegalArgumentException("Amount must be positive"))
        if (idempotencyKey.isBlank()) return Result.failure(IllegalArgumentException("Idempotency key required"))
        // Fee is calculated server-side; this is client-side pre-validation only
        return repository.initiateP2PTransfer(fromUserId, toUserId, amount, idempotencyKey)
    }
}
