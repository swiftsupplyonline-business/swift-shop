package com.swiftshop.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface BiometricGuard {
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String = ""
    ): BiometricResult
}

sealed interface BiometricResult {
    data object Success : BiometricResult
    data class Failure(val message: String) : BiometricResult
    data object Cancelled : BiometricResult
    data object Unsupported : BiometricResult
}

class AndroidBiometricGuard @Inject constructor(
    @ApplicationContext private val context: Context
) : BiometricGuard {

    override suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String
    ): BiometricResult = suspendCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or 
                             BiometricManager.Authenticators.DEVICE_CREDENTIAL

        when (biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(authenticators)
                    .build()

                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || 
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                continuation.resume(BiometricResult.Cancelled)
                            } else {
                                continuation.resume(BiometricResult.Failure(errString.toString()))
                            }
                        }

                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            continuation.resume(BiometricResult.Success)
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            // Note: Prompt usually allows retries automatically. 
                            // Error callback is called on final failure.
                        }
                    })

                biometricPrompt.authenticate(promptInfo)
            }
            else -> continuation.resume(BiometricResult.Unsupported)
        }
    }
}
