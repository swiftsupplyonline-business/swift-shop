plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.swiftshop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.swiftshop.client"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MOPAY_BASE_URL", "\"${project.properties["MOPAY_BASE_URL"]}\"")
        buildConfigField("String", "MOPAY_PUBLIC_KEY", "\"${project.properties["MOPAY_PUBLIC_KEY"]}\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"${project.properties["BACKEND_BASE_URL"]}\"")
        manifestPlaceholders["MAPS_API_KEY"] = project.properties["MAPS_API_KEY"] ?: ""
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("Boolean", "ENABLE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "ENABLE_LOGGING", "false")
        }


    }

    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "FLAVOR", "\"dev\"")
        }
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "FLAVOR", "\"staging\"")
        }
        create("production") {
            dimension = "environment"
            buildConfigField("String", "FLAVOR", "\"production\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get() }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:ui"))
    implementation(project(":core:security"))
    implementation(project(":core:media"))

    // Data modules
    implementation(project(":data:repositories"))
    implementation(project(":data:firebase"))
    implementation(project(":data:local"))
    implementation(project(":data:remote"))

    // Domain modules
    implementation(project(":domain:auth"))
    implementation(project(":domain:commerce"))
    implementation(project(":domain:wallet"))
    implementation(project(":domain:feed"))
    implementation(project(":domain:profile"))
    implementation(project(":domain:messaging"))
    implementation(project(":domain:advertising"))
    implementation(project(":domain:delivery"))

    // Feature modules
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:shop"))
    implementation(project(":feature:posts"))
    implementation(project(":feature:reels"))
    implementation(project(":feature:checkout"))
    implementation(project(":feature:orders"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:wallet"))
    implementation(project(":feature:messaging"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:delivery"))
    implementation(project(":feature:advertising"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.work.runtime.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    kapt(libs.hilt.android.compiler)
    kapt(libs.androidx.hilt.compiler)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.bundles.firebase)

    // Coroutines
    implementation(libs.bundles.coroutines)

    // Timber
    implementation(libs.timber)

    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kapt { correctErrorTypes = true }
