package com.swiftshop.feature.wallet.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.swiftshop.core.model.TransactionStatus
import com.swiftshop.core.model.TransactionType
import com.swiftshop.core.model.WalletTransaction
import com.swiftshop.core.ui.components.SwiftCard
import com.swiftshop.core.ui.theme.SwiftShopColors
import com.swiftshop.feature.wallet.TransactionListState

@Composable
fun TransactionList(
    state: TransactionListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "Recent Transactions",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        
        when (state) {
            is TransactionListState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            }
            is TransactionListState.Empty -> {
                Text(
                    text = "No transactions found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            is TransactionListState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            is TransactionListState.Loaded -> {
                state.transactions.forEach { transaction ->
                    TransactionItem(transaction = transaction)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun TransactionItem(transaction: WalletTransaction) {
    val isCredit = transaction.type in listOf(
        TransactionType.DEPOSIT, TransactionType.SALE,
        TransactionType.TRANSFER_IN, TransactionType.REFUND
    )

    SwiftCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCredit) SwiftShopColors.Success.copy(alpha = 0.15f)
                        else SwiftShopColors.Error.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isCredit) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    null,
                    tint = if (isCredit) SwiftShopColors.Success else SwiftShopColors.Error,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(transaction.description.ifEmpty { transaction.type.name },
                    style = MaterialTheme.typography.titleSmall)
                Text(transaction.reference, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (isCredit) "+" else "-"}${transaction.amount.toDisplayString()}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCredit) SwiftShopColors.Success else SwiftShopColors.Error
                )
                TransactionStatusChip(transaction.status)
            }
        }
    }
}

@Composable
fun TransactionStatusChip(status: TransactionStatus) {
    val (label, color) = when (status) {
        TransactionStatus.COMPLETED -> "Done" to SwiftShopColors.Success
        TransactionStatus.PENDING -> "Pending" to SwiftShopColors.Warning
        TransactionStatus.PROCESSING -> "Processing" to SwiftShopColors.BrandBlue
        TransactionStatus.FAILED -> "Failed" to SwiftShopColors.Error
        TransactionStatus.REVERSED -> "Reversed" to SwiftShopColors.Warning
        TransactionStatus.REFUNDED -> "Refunded" to SwiftShopColors.BrandBlue
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
