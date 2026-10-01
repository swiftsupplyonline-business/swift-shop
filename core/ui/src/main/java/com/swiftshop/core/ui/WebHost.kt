package com.swiftshop.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Public web host of the ACTIVE environment (dev / staging / production), provided once by :app
 * from BuildConfig.WEB_HOST. The default is deliberately PRODUCTION so a missing provider can never
 * leak a DEV link into a release build.
 */
val LocalWebHost = staticCompositionLocalOf { "swift-d1baa.web.app" }
