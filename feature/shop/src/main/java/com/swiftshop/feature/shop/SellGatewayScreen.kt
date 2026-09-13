package com.swiftshop.feature.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swiftshop.core.model.ListingType
import com.swiftshop.core.ui.components.SwiftCard

data class SellOption(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val type: ListingType
)

private val sellOptions = listOf(
    SellOption(
        title = "Something I have",
        description = "Clothes, phones, cosmetics, furniture, gadgets.",
        icon = Icons.Default.LocalMall,
        type = ListingType.BUY
    ),
    SellOption(
        title = "Something I make",
        description = "Food, cakes, crafts, custom products.",
        icon = Icons.Default.Restaurant,
        type = ListingType.PLACE_ORDER
    ),
    SellOption(
        title = "Something I do",
        description = "Repairs, design, construction, cleaning, plumbing.",
        icon = Icons.Default.Build,
        type = ListingType.SET_APPOINTMENT // Safest existing mapping for service/work
    ),
    SellOption(
        title = "A service",
        description = "Hair, nails, photography, tutoring, transport.",
        icon = Icons.Default.ContentCut,
        type = ListingType.SET_APPOINTMENT // Safest existing mapping for appointments
    ),
    SellOption(
        title = "Something I resell",
        description = "Sourced products, wholesale goods, reselling.",
        icon = Icons.Default.Sync,
        type = ListingType.BUY
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellGatewayScreen(
    onBack: () -> Unit,
    onOptionSelected: (ListingType) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sell something", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(16.dp)
        ) {
            Text(
                text = "What are you selling today?",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Let's get it in front of people.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sellOptions) { option ->
                    SellOptionCard(option = option, onClick = { onOptionSelected(option.type) })
                }
            }
        }
    }
}

@Composable
private fun SellOptionCard(
    option: SellOption,
    onClick: () -> Unit
) {
    SwiftCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconGradient = Brush.linearGradient(listOf(
                    MaterialTheme.colorScheme.primary,
                    androidx.compose.ui.graphics.Color(0xFF00C2FF)
                ))
            Box(
                modifier = Modifier.size(56.dp).background(iconGradient, MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(28.dp)
                    )
            }
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
