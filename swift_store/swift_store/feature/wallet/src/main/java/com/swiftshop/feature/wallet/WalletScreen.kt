package com.swiftshop.feature.wallet

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import com.swiftshop.core.security.BiometricGuard
import com.swiftshop.core.security.BiometricResult
import com.swiftshop.feature.wallet.components.BalanceCard
import com.swiftshop.feature.wallet.components.DepositSheet
import com.swiftshop.feature.wallet.components.TransactionList
import com.swiftshop.feature.wallet.components.WithdrawSheet
import com.swiftshop.feature.wallet.components.TransferSheet
import kotlinx.coroutines.flow.collectLatest

/**
 * WalletScreen - Phase 9E.2C: WalletSheets Activation.
 */
@Composable
fun WalletScreen(
    navController: NavController,
    viewModel: WalletViewModel,
    biometricGuard: BiometricGuard
) {
    val walletState by viewModel.walletState.collectAsState()
    val transactionsState by viewModel.transactions.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val balanceVisible by viewModel.balanceVisible.collectAsState()
    
    val showDepositSheet by viewModel.showDepositSheet.collectAsState()
    val showWithdrawSheet by viewModel.showWithdrawSheet.collectAsState()
    val showTransferSheet by viewModel.showTransferSheet.collectAsState()
    
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Security Gate Side-Effect
    LaunchedEffect(Unit) {
        viewModel.withdrawalIntent.collectLatest { intent ->
            val activity = context as? FragmentActivity ?: return@collectLatest
            val result = biometricGuard.authenticate(
                activity = activity,
                title = "Confirm Withdrawal",
                subtitle = "Authorize withdrawal of ${intent.amount.toDisplayString()}"
            )
            if (result is BiometricResult.Success) {
                viewModel.confirmWithdrawal(intent)
            } else {
                viewModel.handleSecurityResult(result)
            }
        }
    }

    // Action Result Side-Effect
    LaunchedEffect(actionState) {
        when (val state = actionState) {
            is ActionState.Success -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearActionState()
            }
            is ActionState.Error -> {
                snackbarHostState.showSnackbar(state.message)
                viewModel.clearActionState()
            }
            else -> {}
        }
    }

    // MoPay Launch Side-Effect
    LaunchedEffect(Unit) {
        viewModel.depositInitiated.collectLatest { initiation ->
            val url = initiation.paymentUrl ?: return@collectLatest
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (val state = walletState) {
                is WalletScreenState.Loaded -> {
                    BalanceCard(
                        wallet = state.wallet,
                        tier = state.tier,
                        balanceVisible = balanceVisible,
                        onToggleBalance = { viewModel.toggleBalanceVisibility() },
                        onDeposit = { viewModel.setShowDepositSheet(true) },
                        onWithdraw = { viewModel.setShowWithdrawSheet(true) },
                        onTransfer = { viewModel.setShowTransferSheet(true) }
                    )
                }
                is WalletScreenState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
                is WalletScreenState.Error -> {
                    Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))

            TransactionList(state = transactionsState)
        }
    }

    // Sheet Orchestration
    if (showDepositSheet) {
        DepositSheet(
            onDismiss = { viewModel.setShowDepositSheet(false) },
            onDeposit = { amount, method, phone -> viewModel.deposit(amount, method, phone) },
            isLoading = actionState is ActionState.Loading
        )
    }

    if (showWithdrawSheet) {
        WithdrawSheet(
            availableBalance = (walletState as? WalletScreenState.Loaded)?.wallet?.availableBalance,
            onDismiss = { viewModel.setShowWithdrawSheet(false) },
            onWithdraw = { amount, method, dest -> viewModel.withdraw(amount, method, dest) },
            isLoading = actionState is ActionState.Loading
        )
    }

    if (showTransferSheet) {
        TransferSheet(
            onDismiss = { viewModel.setShowTransferSheet(false) },
            onTransfer = { recipientId, amount -> viewModel.transfer(recipientId, amount) },
            isLoading = actionState is ActionState.Loading
        )
    }
}
