package com.swiftshop.feature.advertising

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.swiftshop.core.model.*
import com.swiftshop.core.ui.components.*
import com.swiftshop.core.ui.theme.swiftColors

// Pricing is loaded from remote config in production.
// These are display defaults only — actual billing is server-side.
data class AdPricing(
    val type: CampaignContentType,
    val label: String,
    val weeklyPriceMinorUnits: Long,
    val description: String
)

val AD_PRICING_OPTIONS = listOf(
    AdPricing(CampaignContentType.POST, "Post Promotion", 500L, "Boost a post to more users"),
    AdPricing(CampaignContentType.LISTING, "Listing Promotion", 500L, "Feature a listing at the top of shop feed"),
    AdPricing(CampaignContentType.REEL, "Reel Promotion", 1000L, "Promote your reel to a wider audience")
)

@Composable
fun CreateAdScreen(
    navController: NavController,
    viewModel: AdvertisingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val contentId = navController.currentBackStackEntry?.arguments?.getString("contentId") ?: ""
    val contentTypeStr = navController.currentBackStackEntry?.arguments?.getString("contentType") ?: "LISTING"
    val contentType = runCatching { CampaignContentType.valueOf(contentTypeStr) }
        .getOrDefault(CampaignContentType.LISTING)

    var selectedWeeks by remember { mutableIntStateOf(1) }
    var selectedPricing by remember {
        mutableStateOf(AD_PRICING_OPTIONS.first { it.type == contentType })
    }
    var includeAllFeed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Advert", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Ad Type ────────────────────────────────────────────────────
            Text("Ad Type", style = MaterialTheme.typography.titleMedium)

            AD_PRICING_OPTIONS.forEach { option ->
                AdTypeCard(
                    option = option,
                    isSelected = selectedPricing == option,
                    onClick = { selectedPricing = option }
                )
            }

            // ── All Feed Toggle ────────────────────────────────────────────
            SwiftCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .clickable { includeAllFeed = !includeAllFeed },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Universal All-Feed Exposure", style = MaterialTheme.typography.titleSmall)
                        Text("Appear across Shop, Posts & Reels feeds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("+M20/week", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Switch(
                        checked = includeAllFeed,
                        onCheckedChange = { includeAllFeed = it }
                    )
                }
            }

            // ── Duration ──────────────────────────────────────────────────
            Text("Duration", style = MaterialTheme.typography.titleMedium)

            SwiftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$selectedWeeks week${if (selectedWeeks > 1) "s" else ""}",
                            style = MaterialTheme.typography.titleMedium)
                        Row {
                            IconButton(
                                onClick = { if (selectedWeeks > 1) selectedWeeks-- },
                                enabled = selectedWeeks > 1
                            ) {
                                Icon(Icons.Default.Remove, "Decrease")
                            }
                            IconButton(
                                onClick = { if (selectedWeeks < 52) selectedWeeks++ },
                                enabled = selectedWeeks < 52
                            ) {
                                Icon(Icons.Default.Add, "Increase")
                            }
                        }
                    }
                    Slider(
                        value = selectedWeeks.toFloat(),
                        onValueChange = { selectedWeeks = it.toInt() },
                        valueRange = 1f..12f,
                        steps = 10
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 week", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("12 weeks", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Cost Summary ───────────────────────────────────────────────
            val baseCostMinorUnits = selectedPricing.weeklyPriceMinorUnits * selectedWeeks
            val allFeedCost = if (includeAllFeed) 2000L * selectedWeeks else 0L
            val totalMinorUnits = baseCostMinorUnits + allFeedCost
            val total = MoneyAmount("LSL", totalMinorUnits)

            SwiftCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cost Summary", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(selectedPricing.label, style = MaterialTheme.typography.bodyMedium)
                        Text(MoneyAmount("LSL", baseCostMinorUnits).toDisplayString(),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (includeAllFeed) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("All-Feed Exposure", style = MaterialTheme.typography.bodyMedium)
                            Text(MoneyAmount("LSL", allFeedCost).toDisplayString(),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Total", style = MaterialTheme.typography.titleSmall)
                        Text(total.toDisplayString(), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text("All fees are deducted from your Swift Wallet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Launch Button ──────────────────────────────────────────────
            SwiftGradientButton(
                text = "Launch Campaign — ${total.toDisplayString()}",
                isLoading = uiState is AdState.Launching,
                onClick = {
                    viewModel.createCampaign(
                        contentId = contentId,
                        contentType = selectedPricing.type,
                        budget = total,
                        durationWeeks = selectedWeeks,
                        includeAllFeed = includeAllFeed
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (uiState is AdState.Error) {
                Text((uiState as AdState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (uiState is AdState.Success) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AdTypeCard(
    option: AdPricing,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.swiftColors
    SwiftCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(
                    2.dp, colors.brandBlue, MaterialTheme.shapes.large
                ) else Modifier
            )
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.titleSmall)
                Text(option.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "M${option.weeklyPriceMinorUnits / 100}/wk",
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) colors.brandBlue
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
