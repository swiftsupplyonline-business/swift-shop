plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.swiftshop.core.network"
    compileSdk = 34
    defaultConfig {
        minSdk = 24

        buildConfigField("String", "MOPAY_BASE_URL", "\"${project.properties["MOPAY_BASE_URL"]}\"")
        buildConfigField("String", "MOPAY_PUBLIC_KEY", "\"${project.properties["MOPAY_PUBLIC_KEY"]}\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"${project.properties["BACKEND_BASE_URL"]}\"")
        buildConfigField("String", "FLAVOR", "\"dev\"") // Default to dev for core:network
        buildConfigField("Boolean", "ENABLE_LOGGING", "true")
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core:model"))
    implementation(libs.bundles.retrofit)
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.timber)
}
