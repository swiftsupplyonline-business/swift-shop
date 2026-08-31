package com.swiftshop.feature.wallet.components

/*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swiftshop.core.ui.components.ShimmerBox
import com.swiftshop.feature.wallet.WalletScreenState

@Composable
fun WalletDashboard(
    walletState: WalletScreenState,
    balanceVisible: Boolean,
    onToggleBalance: () -> Unit,
    onDepositRequest: () -> Unit,
    onWithdrawRequest: () -> Unit,
    onTransferRequest: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Balance card
        item {
            when (walletState) {
                is WalletScreenState.Loading -> ShimmerBox(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    shape = MaterialTheme.shapes.extraLarge
                )
                is WalletScreenState.Loaded -> BalanceCard(
                    wallet = walletState.wallet,
                    tier = walletState.tier,
                    balanceVisible = balanceVisible,
                    onToggleBalance = onToggleBalance,
                    onDeposit = onDepositRequest,
                    onWithdraw = onWithdrawRequest,
                    onTransfer = onTransferRequest
                )
                is WalletScreenState.Error -> Text(walletState.message, color = MaterialTheme.colorScheme.error)
            }
        }

        // Quick actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionCard(
                    icon = Icons.Default.Add,
                    label = "Deposit",
                    modifier = Modifier.weight(1f),
                    onClick = onDepositRequest
                )
                QuickActionCard(
                    icon = Icons.Default.ArrowUpward,
                    label = "Withdraw",
                    modifier = Modifier.weight(1f),
                    onClick = onWithdrawRequest
                )
                QuickActionCard(
                    icon = Icons.Default.SwapHoriz,
                    label = "Transfer",
                    modifier = Modifier.weight(1f),
                    onClick = onTransferRequest
                )
            }
        }
    }
}
*/
