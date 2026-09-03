pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.mapbox.com/downloads/v2/releases/maven") }
    }
}

rootProject.name = "SwiftShop"

include(":app")

// Core modules
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:datastore")
include(":core:ui")
include(":core:security")
include(":core:media")

// Data layer
include(":data:repositories")
include(":data:firebase")
include(":data:local")
include(":data:remote")

// Domain layer
include(":domain:auth")
include(":domain:commerce")
include(":domain:wallet")
include(":domain:feed")
include(":domain:profile")
include(":domain:messaging")
include(":domain:advertising")
include(":domain:delivery")

// Feature modules
include(":feature:home")
include(":feature:search")
include(":feature:shop")
include(":feature:posts")
include(":feature:reels")
include(":feature:checkout")
include(":feature:orders")
include(":feature:profile")
include(":feature:wallet")
include(":feature:messaging")
include(":feature:settings")
include(":feature:auth")
include(":feature:delivery")
include(":feature:advertising")
