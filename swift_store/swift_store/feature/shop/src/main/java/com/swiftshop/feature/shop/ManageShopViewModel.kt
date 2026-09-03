package com.swiftshop.feature.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.commerce.UpdateShopUseCase
import com.swiftshop.domain.commerce.GetShopUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ManageShopUiState {
    data object Loading : ManageShopUiState
    data class Success(val shop: Shop) : ManageShopUiState
    data class Error(val message: String) : ManageShopUiState
}

sealed interface ManageActionState {
    data object Idle : ManageActionState
    data object Loading : ManageActionState
    data object Success : ManageActionState
    data class Error(val message: String) : ManageActionState
}

@HiltViewModel
class ManageShopViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getShop: GetShopUseCase,
    private val updateShop: UpdateShopUseCase
) : ViewModel() {

    private val shopId: String = checkNotNull(savedStateHandle["shopId"])

    private val _uiState = MutableStateFlow<ManageShopUiState>(ManageShopUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _actionState = MutableStateFlow<ManageActionState>(ManageActionState.Idle)
    val actionState = _actionState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = ManageShopUiState.Loading
            getShop(shopId).fold(
                onSuccess = { _uiState.value = ManageShopUiState.Success(it) },
                onFailure = { _uiState.value = ManageShopUiState.Error(it.message ?: "Failed to load shop") }
            )
        }
    }

    fun update(name: String, description: String, category: String, address: String) {
        val currentShop = (uiState.value as? ManageShopUiState.Success)?.shop ?: return
        viewModelScope.launch {
            _actionState.value = ManageActionState.Loading
            val updated = currentShop.copy(
                name = name.trim(),
                description = description.trim(),
                category = category.trim(),
                locationAddress = address.trim()
            )
            updateShop(updated).fold(
                onSuccess = {
                    _actionState.value = ManageActionState.Success
                    _uiState.value = ManageShopUiState.Success(updated)
                },
                onFailure = { _actionState.value = ManageActionState.Error(it.message ?: "Failed to update") }
            )
        }
    }

    fun clearActionState() { _actionState.value = ManageActionState.Idle }
}
