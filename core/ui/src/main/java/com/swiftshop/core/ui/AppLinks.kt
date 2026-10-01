package com.swiftshop.core.ui

/**
 * Public web host of the active environment (dev / staging / production).
 * Set once in Application.onCreate from the flavor's BuildConfig.APP_LINK_HOST; feature modules build
 * share URLs through [url] so no environment-specific host is hardcoded in shared code.
 */
object AppLinks {
    @Volatile
    var host: String = ""

    fun url(path: String): String {
        check(host.isNotBlank()) { "AppLinks.host was not initialised" }
        return "https://" + host + (if (path.startsWith("/")) path else "/$path")
    }
}
