package com.swiftshop.core.ui.components

import android.content.Context
import org.osmdroid.config.Configuration

object MapInitializer {
    fun initialize(context: Context) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        }
    }
}
