package com.swiftshop.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.swiftshop.core.model.MoneyAmount
import com.swiftshop.domain.wallet.LedgerEntry
import com.swiftshop.domain.wallet.LedgerRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseLedgerRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : LedgerRepository {

    override suspend fun postEntry(entry: LedgerEntry): Result<String> {
        // SACRED RULE: Client cannot create financial truth. 
        // Ledger entries are immutable and written by Admin SDK/Cloud Functions only.
        return Result.failure(IllegalStateException("Ledger mutation restricted to Control Plane"))
    }

    override fun observeAccountEntries(accountId: String, limit: Int): Flow<List<LedgerEntry>> = callbackFlow {
        val subscription = firestore.collection("ledgerEntries")
            // Assuming the double-entry is indexed via an array of accounts involved
            .whereArrayContains("involvedAccounts", accountId) 
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, _ ->
                val entries = snapshot?.toObjects(FirestoreLedgerEntry::class.java)?.map { it.toDomain() } ?: emptyList()
                trySend(entries)
            }
        awaitClose { subscription.remove() }
    }

    override suspend fun getBalance(accountId: String): Result<MoneyAmount> = runCatching {
        val wallet = firestore.collection("wallets").document(accountId).get().await()
            .toObject(FirestoreWallet::class.java)
            ?: throw NoSuchElementException("Account not found")
        
        MoneyAmount(wallet.currency, wallet.availableBalanceMinorUnits)
    }
}

data class FirestoreLedgerEntry(
    val id: String = "",
    val transactionId: String = "",
    val debitAccount: String = "",
    val creditAccount: String = "",
    val amountMinorUnits: Long = 0L,
    val currency: String = "LSL",
    val reference: String = "",
    val timestamp: Long = 0L
) {
    fun toDomain() = LedgerEntry(
        id = id,
        transactionId = transactionId,
        debitAccount = debitAccount,
        creditAccount = creditAccount,
        amount = MoneyAmount(currency, amountMinorUnits),
        reference = reference,
        timestamp = timestamp
    )
}
