package com.swiftshop.feature.wallet.components

/*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.swiftshop.core.security.BiometricGuard
import com.swiftshop.core.security.BiometricResult
import com.swiftshop.feature.wallet.WalletViewModel
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletContent(
    viewModel: WalletViewModel,
    biometricGuard: BiometricGuard,
    balanceVisible: Boolean,
    onBack: () -> Unit
) {
    val walletState by viewModel.walletState.collectAsState()
    val context = LocalContext.current

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        WalletDashboard(
            walletState = walletState,
            balanceVisible = balanceVisible,
            onToggleBalance = { viewModel.toggleBalanceVisibility() },
            onDepositRequest = { viewModel.setShowDepositSheet(true) },
            onWithdrawRequest = { viewModel.setShowWithdrawSheet(true) },
            onTransferRequest = { viewModel.setShowTransferSheet(true) },
            onRetry = { viewModel.load() },
            modifier = Modifier.padding(padding)
        )
    }
}
*/
