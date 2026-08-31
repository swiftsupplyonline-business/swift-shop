package com.swiftshop.feature.delivery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.DeliveryRoute
import com.swiftshop.domain.delivery.DeliveryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DeliveryUiState {
    data object Loading : DeliveryUiState
    data class Tracking(val route: DeliveryRoute) : DeliveryUiState
    data class Error(val message: String) : DeliveryUiState
}

@HiltViewModel
class DeliveryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: DeliveryRepository
) : ViewModel() {

    private val routeId: String = checkNotNull(savedStateHandle["routeId"])

    private val _uiState = MutableStateFlow<DeliveryUiState>(DeliveryUiState.Loading)
    val uiState: StateFlow<DeliveryUiState> = _uiState.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            repository.observeDeliveryRoute(routeId)
                .catch { _uiState.value = DeliveryUiState.Error(it.message ?: "Tracking error") }
                .collect { route ->
                    _uiState.value = DeliveryUiState.Tracking(route)
                }
        }
    }
}
