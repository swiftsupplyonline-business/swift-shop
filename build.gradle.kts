// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

// Compose opt-in markers are only resolvable in modules that have Compose on their classpath. Applying them to
// every module (domain/data/core:model have no Compose) makes the Kotlin compiler report the markers as unresolved.
// Domain modules must stay Compose-free, so the markers are applied only to the UI modules.
val composeModules = setOf(":app", ":core:ui") +
    listOf("advertising", "auth", "checkout", "delivery", "home", "messaging", "orders", "posts", "profile",
        "reels", "search", "settings", "shop", "wallet").map { ":feature:$it" }

subprojects {
    val optIns = if (path in composeModules) listOf(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi"
    ) else emptyList()
    if (optIns.isNotEmpty()) {
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            kotlinOptions {
                freeCompilerArgs = freeCompilerArgs + optIns
            }
        }
    }
}
