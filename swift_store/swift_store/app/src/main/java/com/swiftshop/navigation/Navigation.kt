package com.swiftshop.navigation

import com.swiftshop.core.ui.navigation.Screen
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.*
import androidx.navigation.compose.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import androidx.compose.ui.Alignment
import com.swiftshop.core.security.BiometricGuard
import com.swiftshop.feature.auth.AuthScreen
import com.swiftshop.feature.checkout.CheckoutScreen
import com.swiftshop.feature.delivery.DeliveryTrackingScreen
import com.swiftshop.feature.home.HomeScreen
import com.swiftshop.feature.messaging.ConversationScreen
import com.swiftshop.feature.messaging.MessagingListScreen
import com.swiftshop.feature.orders.OrderDetailScreen
import com.swiftshop.feature.orders.TrackOrderScreen
import com.swiftshop.feature.orders.OrdersScreen
import com.swiftshop.feature.posts.CreatePostScreen
import com.swiftshop.feature.posts.PostDetailScreen
import com.swiftshop.feature.profile.ProfileScreen
import com.swiftshop.feature.profile.EditProfileScreen
import com.swiftshop.feature.reels.CreateReelScreen
import com.swiftshop.feature.reels.ReelDetailScreen
import com.swiftshop.feature.profile.BookmarksScreen
import com.swiftshop.feature.search.SearchScreen
import com.swiftshop.feature.settings.SettingsScreen
import com.swiftshop.feature.shop.CreateListingScreen
import com.swiftshop.feature.shop.ListingDetailScreen
import com.swiftshop.feature.shop.ShopDetailScreen
import com.swiftshop.feature.shop.CreateShopScreen
import com.swiftshop.feature.shop.ManageShopScreen
import com.swiftshop.feature.wallet.WalletScreen
import com.swiftshop.feature.advertising.CreateAdScreen
import kotlinx.coroutines.flow.first

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Home, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem(Screen.Profile, "Profile", Icons.Filled.Person, Icons.Outlined.Person)
)

@Composable
fun SwiftShopNavHost(
    biometricGuard: BiometricGuard,
    observeCurrentUser: ObserveCurrentUserUseCase = hiltViewModel<AuthNavViewModel>().observeCurrentUser
) {
    val navController = rememberNavController()
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val user = observeCurrentUser().first()
        startDestination = if (user != null) Screen.Home.route else Screen.Auth.route
    }

    if (startDestination == null) {
        // Splash or Loading
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    NavHost(
        navController = navController,
        startDestination = startDestination!!
    ) {
        // Auth
        composable(Screen.Auth.route) {
            AuthScreen(onAuthSuccess = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Auth.route) { inclusive = true }
                }
            })
        }

        // Main scaffold with bottom nav
        composable(Screen.Home.route) {
            MainScaffold(navController = navController, startTab = Screen.Home)
        }
        composable(Screen.Profile.route) {
            MainScaffold(navController = navController, startTab = Screen.Profile)
        }

        // Sub-screens
        composable(Screen.Search.route) {
            SearchScreen(onBack = { navController.popBackStack() },
                onNavigate = { navController.navigate(it) })
        }
        composable(
            route = Screen.PostDetail.route,
            arguments = listOf(navArgument("postId") { type = NavType.StringType })
        ) { PostDetailScreen(navController = navController) }

        composable(
            route = Screen.ReelDetail.route,
            arguments = listOf(navArgument("reelId") { type = NavType.StringType })
        ) { ReelDetailScreen(onBack = { navController.popBackStack() }) }

        composable(
            route = Screen.ListingDetail.route,
            arguments = listOf(navArgument("listingId") { type = NavType.StringType })
        ) { ListingDetailScreen(navController = navController) }

        composable(
            route = Screen.ShopDetail.route,
            arguments = listOf(navArgument("shopId") { type = NavType.StringType })
        ) { ShopDetailScreen(navController = navController) }

        composable(
            route = Screen.Checkout.route,
            arguments = listOf(
                navArgument("orderId") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) {
            CheckoutScreen(navController = navController)
        }

        composable(Screen.Orders.route) {
            OrdersScreen(navController = navController)
        }

        composable(
            route = Screen.OrderDetail.route,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { OrderDetailScreen(navController = navController) }

        composable(
            route = Screen.TrackOrder.route,
            arguments = listOf(navArgument("orderId") { type = NavType.StringType })
        ) { TrackOrderScreen(navController = navController) }
        composable(Screen.CreatePost.route) {
            CreatePostScreen(onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() })
        }
        composable(Screen.CreateReel.route) {
            CreateReelScreen(onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() })
        }
        composable(Screen.CreateListing.route) {
            com.swiftshop.feature.shop.CreateListingGatewayScreen(
                onBack = { navController.popBackStack() },
                onNavigateToForm = { type ->
                    navController.navigate(Screen.CreateListingForm.createRoute(type))
                }
            )
        }
        composable(
            route = Screen.CreateListingForm.route,
            arguments = listOf(navArgument("listingType") { type = NavType.StringType })
        ) {
            CreateListingScreen(
                onBack = { navController.popBackStack() },
                onCreated = {
                    navController.popBackStack(Screen.CreateListing.route, inclusive = true)
                }
            )
        }
        composable(
            route = Screen.CreateAd.route,
            arguments = listOf(
                navArgument("contentId") { type = NavType.StringType },
                navArgument("contentType") { type = NavType.StringType }
            )
        ) { CreateAdScreen(navController = navController) }

        composable(
            route = Screen.Wallet.route,
            deepLinks = listOf(
                navDeepLink { uriPattern = "swiftshop://wallet/deposit/verify?sessionId={sessionId}" }
            ),
            arguments = listOf(
                navArgument("sessionId") { 
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId")
            val viewModel: com.swiftshop.feature.wallet.WalletViewModel = hiltViewModel()
            
            LaunchedEffect(sessionId) {
                if (sessionId != null) {
                    viewModel.verifyDeposit(sessionId)
                }
            }

            WalletScreen(
                navController = navController,
                viewModel = viewModel,
                biometricGuard = biometricGuard
            )
        }
        composable(Screen.MessagingList.route) {
            MessagingListScreen(navController = navController)
        }
        composable(
            route = Screen.Conversation.route,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) { ConversationScreen(navController = navController) }

        composable(
            route = Screen.DeliveryTracking.route,
            arguments = listOf(
                navArgument("routeId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("orderId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("role") { type = NavType.StringType; nullable = true; defaultValue = null }
            )
        ) { DeliveryTrackingScreen(navController = navController) }

        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        composable(Screen.EditProfile.route) {
            EditProfileScreen(navController = navController)
        }

        composable(Screen.Bookmarks.route) {
            BookmarksScreen(navController = navController)
        }

        composable(Screen.CreateShop.route) {
            CreateShopScreen(
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ManageShop.route,
            arguments = listOf(navArgument("shopId") { type = NavType.StringType })
        ) {
            ManageShopScreen(navController = navController)
        }

        composable(
            route = Screen.UserProfile.route,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { ProfileScreen(navController = navController) }
    }
}

@Composable
fun MainScaffold(navController: NavController, startTab: Screen) {
    val innerNav = rememberNavController()
    val currentBackStack by innerNav.currentBackStackEntryAsState()
    val currentRoute = currentBackStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                tonalElevation = 8.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                bottomNavItems.forEach { item ->
                    val selected = currentRoute == item.screen.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            innerNav.navigate(item.screen.route) {
                                popUpTo(innerNav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = innerNav,
            startDestination = startTab.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigate = { route -> navController.navigate(route) },
                    onSearch = { navController.navigate(Screen.Search.route) }
                )
            }
            composable(Screen.Profile.route) {
                ProfileScreen(navController = navController)
            }
        }
    }
}
