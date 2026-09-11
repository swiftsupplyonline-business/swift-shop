package com.swiftshop.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.swiftshop.core.ui.components.SwiftCard
import com.swiftshop.core.ui.components.swiftGlowBorder

@Composable
fun SettingsScreen(navController: NavController) {
    var notificationsEnabled by remember { mutableStateOf(true) }
    var marketingEnabled by remember { mutableStateOf(false) }
    var darkMode by remember { mutableStateOf(false) }
    var biometricEnabled by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Account
            item {
                SettingsSection(title = "Account") {
                    SettingsItem(Icons.Default.Person, "Edit Profile", onClick = {
                        navController.navigate(com.swiftshop.core.ui.navigation.Screen.EditProfile.route)
                    })
                    SettingsItem(Icons.Default.Phone, "Phone Number", subtitle = "Not verified", onClick = {})
                    SettingsItem(Icons.Default.Email, "Email Address", onClick = {})
                    SettingsItem(Icons.Default.Lock, "Change Password", onClick = {})
                }
            }

            // Notifications
            item {
                SettingsSection(title = "Notifications") {
                    SettingsToggleItem(
                        icon = Icons.Default.Notifications,
                        label = "Push Notifications",
                        checked = notificationsEnabled,
                        onCheckedChange = { notificationsEnabled = it }
                    )
                    SettingsToggleItem(
                        icon = Icons.Default.Campaign,
                        label = "Marketing & Promotions",
                        checked = marketingEnabled,
                        onCheckedChange = { marketingEnabled = it }
                    )
                    SettingsItem(Icons.Default.NotificationImportant,
                        "Notification Preferences", onClick = {})
                }
            }

            // Appearance
            item {
                SettingsSection(title = "Appearance") {
                    SettingsToggleItem(
                        icon = Icons.Default.DarkMode,
                        label = "Dark Mode",
                        checked = darkMode,
                        onCheckedChange = { darkMode = it }
                    )
                }
            }

            // Security
            item {
                SettingsSection(title = "Security") {
                    SettingsToggleItem(
                        icon = Icons.Default.Fingerprint,
                        label = "Biometric Login",
                        checked = biometricEnabled,
                        onCheckedChange = { biometricEnabled = it }
                    )
                    SettingsItem(Icons.Default.Security, "Two-Factor Authentication", onClick = {})
                    SettingsItem(Icons.Default.History, "Login Activity", onClick = {})
                }
            }

            // Privacy
            item {
                SettingsSection(title = "Privacy") {
                    SettingsItem(Icons.Default.VisibilityOff, "Blocked Users", onClick = {})
                    SettingsItem(Icons.Default.AdminPanelSettings,
                        "Data & Privacy", onClick = {})
                }
            }

            // Subscription
            item {
                SettingsSection(title = "Subscription") {
                    SettingsItem(Icons.Default.Stars, "Current Plan: Basic",
                        subtitle = "Upgrade for more features",
                        onClick = {}, trailingText = "Upgrade")
                    SettingsItem(Icons.Default.Receipt, "Billing History", onClick = {})
                }
            }

            // Support
            item {
                SettingsSection(title = "Support") {
                    SettingsItem(Icons.Default.HelpCenter, "Help Centre", onClick = {})
                    SettingsItem(Icons.Default.BugReport, "Report a Problem", onClick = {})
                    SettingsItem(Icons.Default.Info, "About Swift Shop", onClick = {})
                    SettingsItem(Icons.Default.Description, "Terms of Service", onClick = {})
                    SettingsItem(Icons.Default.PrivacyTip, "Privacy Policy", onClick = {})
                }
            }

            // Version
            item {
                Text(
                    "Swift Shop v1.0.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SwiftCard(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    label: String,
    subtitle: String = "",
    trailingText: String = "",
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (subtitle.isNotEmpty()) {
            { Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        leadingContent = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            if (trailingText.isNotEmpty()) {
                Text(trailingText, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Default.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = if (checked) Modifier.swiftGlowBorder(shape = MaterialTheme.shapes.extraLarge) else Modifier
            )
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}
