package com.swiftshop.core.ui.navigation

sealed class Screen(val route: String) {
    data object Auth : Screen("auth")
    data object Home : Screen("home")
    data object Profile : Screen("profile")
    data object Search : Screen("search")
    data object PostDetail : Screen("post/{postId}") {
        fun createRoute(postId: String) = "post/$postId"
    }
    data object ReelDetail : Screen("reel/{reelId}") {
        fun createRoute(reelId: String) = "reel/$reelId"
    }
    data object ListingDetail : Screen("listing/{listingId}") {
        fun createRoute(id: String) = "listing/$id"
    }
    data object ShopDetail : Screen("shop/{shopId}") {
        fun createRoute(id: String) = "shop/$id"
    }
    data object UserProfile : Screen("user/{uid}") {
        fun createRoute(uid: String) = "user/$uid"
    }
    data object Checkout : Screen("checkout?orderId={orderId}") {
        fun createRoute(orderId: String? = null) = if (orderId != null) "checkout?orderId=$orderId" else "checkout"
    }
    data object Orders : Screen("orders")
    data object OrderDetail : Screen("order/{orderId}") {
        fun createRoute(id: String) = "order/$id"
    }
    data object CreatePost : Screen("create_post")
    data object CreateReel : Screen("create_reel")
    data object CreateListing : Screen("create_listing")
    data object CreateAd : Screen("create_ad/{contentId}/{contentType}") {
        fun createRoute(contentId: String, type: String) = "create_ad/$contentId/$type"
    }
    data object Wallet : Screen("wallet")
    data object MessagingList : Screen("messages")
    data object Conversation : Screen("conversation/{conversationId}") {
        fun createRoute(id: String) = "conversation/$id"
    }
    data object DeliveryTracking : Screen("delivery/{routeId}") {
        fun createRoute(id: String) = "delivery/$id"
    }
    data object Settings : Screen("settings")
    data object EditProfile : Screen("edit_profile")
    data object Bookmarks : Screen("bookmarks")
    data object ManageShop : Screen("manage_shop/{shopId}") {
        fun createRoute(id: String) = "manage_shop/$id"
    }
    data object CreateShop : Screen("create_shop")
}

