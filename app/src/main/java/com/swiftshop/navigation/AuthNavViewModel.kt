package com.swiftshop.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.profile.UpdateFcmTokenUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthNavViewModel @Inject constructor(
    val observeCurrentUser: ObserveCurrentUserUseCase,
    private val updateFcmToken: UpdateFcmTokenUseCase
) : ViewModel() {
    init {
        viewModelScope.launch {
            observeCurrentUser().collect { user ->
                if (user != null) {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val token = task.result
                            viewModelScope.launch {
                                updateFcmToken(token)
                            }
                        }
                    }
                }
            }
        }
    }
}
