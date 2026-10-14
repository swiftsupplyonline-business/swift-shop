package com.swiftshop.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Public web host of the ACTIVE environment (dev / staging / production). Provided once by :app from
 * BuildConfig.WEB_HOST, which is set per product flavor beside the manifest's APP_LINK_HOST.
 *
 * Deliberately has NO default value: a missing provider must fail loudly rather than silently build
 * links for the wrong environment.
 */
val LocalWebHost = staticCompositionLocalOf<String> {
    error("LocalWebHost not provided. MainActivity must wrap the UI in CompositionLocalProvider(LocalWebHost provides BuildConfig.WEB_HOST).")
}
