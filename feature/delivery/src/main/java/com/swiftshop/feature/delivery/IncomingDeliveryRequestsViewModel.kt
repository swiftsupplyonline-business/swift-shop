package com.swiftshop.feature.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.DeliveryRequest
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.delivery.ObservePendingDeliveryRequestsForMerchantUseCase
import com.swiftshop.domain.delivery.RespondToDeliveryRequestUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface IncomingDeliveryRequestsUiState {
    data object Loading : IncomingDeliveryRequestsUiState
    data object Empty : IncomingDeliveryRequestsUiState
    data class Loaded(val requests: List<DeliveryRequest>) : IncomingDeliveryRequestsUiState
    data class Error(val message: String) : IncomingDeliveryRequestsUiState
}

@HiltViewModel
class IncomingDeliveryRequestsViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observePendingDeliveryRequestsForMerchant: ObservePendingDeliveryRequestsForMerchantUseCase,
    private val respondToDeliveryRequestUseCase: RespondToDeliveryRequestUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<IncomingDeliveryRequestsUiState>(IncomingDeliveryRequestsUiState.Loading)
    val uiState: StateFlow<IncomingDeliveryRequestsUiState> = _uiState.asStateFlow()

    // Request IDs currently being responded to. This only disables the button in
    // the UI to avoid an obvious double-tap; it is not relied on for correctness —
    // the server transaction in respondToDeliveryRequest is the sole authority and
    // fails closed on a second response regardless of what the UI does.
    private val _respondingIds = MutableStateFlow<Set<String>>(emptySet())
    val respondingIds: StateFlow<Set<String>> = _respondingIds.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            observeCurrentUser().filterNotNull().collect { user ->
                _uiState.value = IncomingDeliveryRequestsUiState.Loading
                observePendingDeliveryRequestsForMerchant(user.uid)
                    .catch { _uiState.value = IncomingDeliveryRequestsUiState.Error(it.message ?: "Error") }
                    .collect { requests ->
                        _uiState.value = if (requests.isEmpty()) {
                            IncomingDeliveryRequestsUiState.Empty
                        } else {
                            IncomingDeliveryRequestsUiState.Loaded(requests.sortedByDescending { it.createdAt })
                        }
                    }
            }
        }
    }

    fun respond(requestId: String, accept: Boolean) {
        if (_respondingIds.value.contains(requestId)) return
        _respondingIds.value = _respondingIds.value + requestId
        viewModelScope.launch {
            respondToDeliveryRequestUseCase(requestId, accept).fold(
                onSuccess = { /* the pending list updates live via the snapshot listener */ },
                onFailure = { _actionError.value = it.message ?: "Couldn't respond to this request" }
            )
            _respondingIds.value = _respondingIds.value - requestId
        }
    }

    fun clearActionError() { _actionError.value = null }
}
