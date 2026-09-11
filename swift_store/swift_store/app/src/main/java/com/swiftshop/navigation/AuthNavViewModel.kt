package com.swiftshop.navigation

import androidx.lifecycle.ViewModel
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AuthNavViewModel @Inject constructor(
    val observeCurrentUser: ObserveCurrentUserUseCase
) : ViewModel()
