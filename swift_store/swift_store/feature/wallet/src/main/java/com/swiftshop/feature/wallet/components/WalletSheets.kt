package com.swiftshop.feature.wallet.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.swiftshop.core.model.MoneyAmount
import com.swiftshop.core.model.PaymentMethod
import com.swiftshop.core.ui.components.SwiftPrimaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositSheet(
    onDismiss: () -> Unit,
    onDeposit: (MoneyAmount, String, String) -> Unit,
    isLoading: Boolean
) {
    var amount by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf("MPESA") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Deposit Funds", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            // Method selector
            Text("Payment Provider", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MPESA", "ECOCASH", "BANK")
                    .forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = { Text(provider) }
                        )
                    }
            }
            Spacer(Modifier.height(16.dp))

            // Amount
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount (LSL)") },
                prefix = { Text("M") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(12.dp))

            // Phone
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone Number") },
                prefix = { Text("+266 ") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Phone
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(12.dp))

            Text(
                text = "You will be redirected to MoPay to complete your payment with M-Pesa or EcoCash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))

            SwiftPrimaryButton(
                text = "Deposit",
                isLoading = isLoading,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val amountVal = amount.toDoubleOrNull() ?: return@SwiftPrimaryButton
                    onDeposit(
                        MoneyAmount.fromMajorUnits(amountVal),
                        selectedProvider,
                        phone
                    )
                }
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithdrawSheet(
    availableBalance: MoneyAmount?,
    onDismiss: () -> Unit,
    onWithdraw: (MoneyAmount, String, String) -> Unit,
    isLoading: Boolean
) {
    var amount by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf("MPESA") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Withdraw Funds", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Minimum withdrawal: M200.00",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (availableBalance != null) {
                Text(
                    "Available: ${availableBalance.toDisplayString()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("MPESA", "ECOCASH", "BANK")
                    .forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = { Text(provider) }
                        )
                    }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount (LSL)") },
                prefix = { Text("M") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Account / Phone Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(24.dp))

            SwiftPrimaryButton(
                text = "Request Withdrawal",
                isLoading = isLoading,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val amountVal = amount.toDoubleOrNull() ?: return@SwiftPrimaryButton
                    onWithdraw(MoneyAmount.fromMajorUnits(amountVal), selectedProvider, destination)
                }
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(
    onDismiss: () -> Unit,
    onTransfer: (String, MoneyAmount) -> Unit,
    isLoading: Boolean
) {
    var amount by remember { mutableStateOf("") }
    var recipientId by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Transfer Funds", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "1.5% fee applies. Transfers are instant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = recipientId,
                onValueChange = { recipientId = it },
                label = { Text("Recipient ID / Phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount (LSL)") },
                prefix = { Text("M") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(24.dp))

            SwiftPrimaryButton(
                text = "Transfer",
                isLoading = isLoading,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val amountVal = amount.toDoubleOrNull() ?: return@SwiftPrimaryButton
                    onTransfer(recipientId, MoneyAmount.fromMajorUnits(amountVal))
                }
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
