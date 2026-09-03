package com.swiftshop

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.swiftshop.core.security.BiometricGuard
import com.swiftshop.core.ui.theme.SwiftShopTheme
import com.swiftshop.navigation.SwiftShopNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var biometricGuard: BiometricGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SwiftShopTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SwiftShopNavHost(biometricGuard = biometricGuard)
                }
            }
        }
    }
}
