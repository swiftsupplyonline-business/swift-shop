package com.swiftshop.feature.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.wallet.*
import com.swiftshop.core.security.BiometricResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed interface WalletScreenState {
    data object Loading : WalletScreenState
    data class Loaded(val wallet: Wallet, val tier: UserTier) : WalletScreenState
    data class Error(val message: String) : WalletScreenState
}

sealed interface TransactionListState {
    data object Loading : TransactionListState
    data object Empty : TransactionListState
    data class Loaded(val transactions: List<WalletTransaction>) : TransactionListState
    data class Error(val message: String) : TransactionListState
}

sealed interface ActionState {
    data object Idle : ActionState
    data object Loading : ActionState
    data class Success(val message: String) : ActionState
    data class Error(val message: String) : ActionState
}

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeWallet: ObserveWalletUseCase,
    private val observeTransactions: ObserveTransactionsUseCase,
    private val initiateWithdrawal: InitiateWithdrawalUseCase,
    private val initiateP2PTransfer: InitiateP2PTransferUseCase,
    private val confirmDepositUseCase: ConfirmDepositUseCase,
    private val confirmWithdrawalUseCase: ConfirmWithdrawalUseCase,
    private val walletRepository: WalletRepository
) : ViewModel() {

    private val _walletState = MutableStateFlow<WalletScreenState>(WalletScreenState.Loading)
    val walletState: StateFlow<WalletScreenState> = _walletState.asStateFlow()

    private val _transactions = MutableStateFlow<TransactionListState>(TransactionListState.Loading)
    val transactions: StateFlow<TransactionListState> = _transactions.asStateFlow()

    private val _actionState = MutableStateFlow<ActionState>(ActionState.Idle)
    val actionState: StateFlow<ActionState> = _actionState.asStateFlow()

    private val _showDepositSheet = MutableStateFlow(false)
    val showDepositSheet = _showDepositSheet.asStateFlow()

    private val _showWithdrawSheet = MutableStateFlow(false)
    val showWithdrawSheet = _showWithdrawSheet.asStateFlow()

    private val _showTransferSheet = MutableStateFlow(false)
    val showTransferSheet = _showTransferSheet.asStateFlow()

    private val _balanceVisible = MutableStateFlow(true)
    val balanceVisible = _balanceVisible.asStateFlow()

    private val _withdrawalIntent = MutableSharedFlow<WithdrawalIntent>()
    val withdrawalIntent = _withdrawalIntent.asSharedFlow()
    data class DepositIntent(val paymentUrl: String, val sessionId: String)
    private val _depositIntent = MutableSharedFlow<DepositIntent>()
    val depositIntent = _depositIntent.asSharedFlow()

    data class WithdrawalIntent(val amount: MoneyAmount, val gateway: String, val provider: String, val destination: String)

    init { load() }

    fun load() {
        viewModelScope.launch {
            observeCurrentUser()
                .filterNotNull()
                .filter { it.uid.isNotBlank() }
                .flatMapLatest { user ->
                    observeWallet(user.uid)
                        .map { wallet -> WalletScreenState.Loaded(wallet, user.tier) as WalletScreenState }
                        .catch { emit(WalletScreenState.Error(it.message ?: "Error")) }
                }
                .collect { _walletState.value = it }
        }
        viewModelScope.launch {
            observeCurrentUser()
                .filterNotNull()
                .filter { it.uid.isNotBlank() }
                .flatMapLatest { user ->
                    observeTransactions(user.uid)
                        .map { txs ->
                            (if (txs.isEmpty()) TransactionListState.Empty else TransactionListState.Loaded(txs)) as TransactionListState
                        }
                        .catch { emit(TransactionListState.Error(it.message ?: "Error")) }
                }
                .collect { _transactions.value = it }
        }
    }

    fun deposit(amount: MoneyAmount, provider: String, phone: String) {
        viewModelScope.launch {
            _actionState.value = ActionState.Loading
            _showDepositSheet.value = false
            val user = observeCurrentUser().first()
            if (user == null || user.uid.isBlank()) {
                _actionState.value = ActionState.Error("User not signed in")
                return@launch
            }
            val userId = user.uid
            val idempotencyKey = UUID.randomUUID().toString()
            walletRepository.initiateDeposit(userId, amount, "MOPAY", provider, phone, idempotencyKey).fold(
                onSuccess = { result ->
                    val paymentUrl = result["paymentUrl"]
                    val sessionId = result["sessionId"]
                    if (paymentUrl != null && sessionId != null) {
                        _depositIntent.emit(DepositIntent(paymentUrl, sessionId))
                        _actionState.value = ActionState.Idle
                    } else {
                        _actionState.value = ActionState.Error("Failed to get payment URL")
                    }
                },
                onFailure = { _actionState.value = ActionState.Error(it.message ?: "Deposit failed") }
            )
        }
    }

    fun confirmDeposit(sessionId: String) {
        viewModelScope.launch {
            _actionState.value = ActionState.Loading
            confirmDepositUseCase(sessionId).fold(
                onSuccess = { result ->
                    val status = result["status"]
                    when (status) {
                        "SUCCESS" -> _actionState.value = ActionState.Success("Deposit confirmed! Funds added to wallet.")
                        "FAILED", "CANCELLED" -> _actionState.value = ActionState.Error("Payment was not successful")
                        else -> _actionState.value = ActionState.Error("Payment still pending — please wait")
                    }
                },
                onFailure = { _actionState.value = ActionState.Error(it.message ?: "Confirmation failed") }
            )
        }
    }

    fun withdraw(amount: MoneyAmount, provider: String, destination: String) {
        viewModelScope.launch {
            _showWithdrawSheet.value = false
            _withdrawalIntent.emit(WithdrawalIntent(amount, "MOPAY", provider, destination))
        }
    }

    /**
     * Confirms and executes the withdrawal after successful local authentication.
     */
    fun confirmWithdrawal(intent: WithdrawalIntent) {
        viewModelScope.launch {
            _actionState.value = ActionState.Loading
            val user = observeCurrentUser().first()
            if (user == null || user.uid.isBlank()) {
                _actionState.value = ActionState.Error("User not signed in")
                return@launch
            }
            val userId = user.uid
            val idempotencyKey = UUID.randomUUID().toString()
            initiateWithdrawal(userId, intent.amount, intent.gateway, intent.provider, intent.destination, idempotencyKey).fold(
                onSuccess = { _actionState.value = ActionState.Success("Withdrawal requested") },
                onFailure = { _actionState.value = ActionState.Error(it.message ?: "Withdrawal failed") }
            )
        }
    }

    /**
     * Handles errors from the biometric security gate.
     */
    fun handleSecurityResult(result: BiometricResult) {
        _actionState.value = when (result) {
            BiometricResult.Cancelled -> ActionState.Idle
            is BiometricResult.Failure -> ActionState.Error("Security verification failed: ${result.message}")
            BiometricResult.Unsupported -> ActionState.Error("Security hardware not available")
            else -> ActionState.Idle
        }
    }

    /**
     * Initiates a P2P transfer.
     */
    fun transfer(toUserId: String, amount: MoneyAmount) {
        viewModelScope.launch {
            _actionState.value = ActionState.Loading
            _showTransferSheet.value = false
            val user = observeCurrentUser().first()
            if (user == null || user.uid.isBlank()) {
                _actionState.value = ActionState.Error("User not signed in")
                return@launch
            }
            val fromUserId = user.uid
            val idempotencyKey = UUID.randomUUID().toString()
            initiateP2PTransfer(fromUserId, toUserId, amount, idempotencyKey).fold(
                onSuccess = { _actionState.value = ActionState.Success("Transfer successful") },
                onFailure = { _actionState.value = ActionState.Error(it.message ?: "Transfer failed") }
            )
        }
    }

    fun setShowDepositSheet(show: Boolean) { _showDepositSheet.value = show }
    fun setShowWithdrawSheet(show: Boolean) { _showWithdrawSheet.value = show }
    fun setShowTransferSheet(show: Boolean) { _showTransferSheet.value = show }
    fun toggleBalanceVisibility() { _balanceVisible.value = !_balanceVisible.value }
    fun clearActionState() { _actionState.value = ActionState.Idle }
}
