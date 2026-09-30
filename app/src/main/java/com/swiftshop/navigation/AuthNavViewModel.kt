package com.swiftshop.navigation

import com.google.firebase.messaging.FirebaseMessaging
import com.swiftshop.core.datastore.PreferenceManager
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.profile.UpdateFcmTokenUseCase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AuthNavViewModel @Inject constructor(
    val observeCurrentUser: ObserveCurrentUserUseCase,
    private val updateFcmToken: UpdateFcmTokenUseCase,
    private val preferenceManager: PreferenceManager
) : ViewModel() {
    init {
        viewModelScope.launch {
            observeCurrentUser().collect { user ->
                if (user != null) {
                    val deviceId = getOrCreateDeviceId()
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val token = task.result
                            viewModelScope.launch {
                                updateFcmToken(token, deviceId)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun getOrCreateDeviceId(): String {
        val existing = preferenceManager.deviceId.first()
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        preferenceManager.setDeviceId(newId)
        return newId
    }
}
