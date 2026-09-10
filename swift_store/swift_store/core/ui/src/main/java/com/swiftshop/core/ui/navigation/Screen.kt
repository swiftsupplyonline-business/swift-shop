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
    data object CreateListing : Screen("create_listing?shopId={shopId}") {
        fun createRoute(shopId: String? = null) = if (shopId != null) "create_listing?shopId=$shopId" else "create_listing"
    }
    data object EditListing : Screen("edit_listing/{listingId}") {
        fun createRoute(listingId: String) = "edit_listing/$listingId"
    }
    data object CreateListingForm : Screen("create_listing_form/{listingType}?shopId={shopId}") {
        fun createRoute(type: com.swiftshop.core.model.ListingType, shopId: String? = null) = 
            if (shopId != null) "create_listing_form/${type.name}?shopId=$shopId" else "create_listing_form/${type.name}"
    }
    data object CreateAd : Screen("create_ad/{contentId}/{contentType}") {
        fun createRoute(contentId: String, type: String) = "create_ad/$contentId/$type"
    }
    data object Wallet : Screen("wallet")
    data object MessagingList : Screen("messages")
    data object Conversation : Screen("conversation/{conversationId}") {
        fun createRoute(id: String) = "conversation/$id"
    }
    data object DeliveryTracking : Screen("delivery?routeId={routeId}&orderId={orderId}&role={role}") {
        fun createRoute(routeId: String? = null, orderId: String? = null, role: String? = null): String {
            val params = mutableListOf<String>()
            routeId?.let { params.add("routeId=$it") }
            orderId?.let { params.add("orderId=$it") }
            role?.let { params.add("role=$it") }
            return if (params.isEmpty()) "delivery" else "delivery?${params.joinToString("&")}"
        }
    }
    data object Settings : Screen("settings")
    data object EditProfile : Screen("edit_profile")
    data object Bookmarks : Screen("bookmarks")
    data object ManageShop : Screen("manage_shop/{shopId}") {
        fun createRoute(id: String) = "manage_shop/$id"
    }
    data object CreateShop : Screen("create_shop")
    data object TrackOrder : Screen("track_order/{orderId}") {
        fun createRoute(orderId: String) = "track_order/$orderId"
    }
    data object RequestDelivery : Screen("request_delivery/{orderId}") {
        fun createRoute(orderId: String) = "request_delivery/$orderId"
    }
}

