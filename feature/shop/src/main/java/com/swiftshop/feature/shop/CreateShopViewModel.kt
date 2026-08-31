package com.swiftshop.feature.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.commerce.CreateShopUseCase
import com.swiftshop.domain.commerce.CanCreateShopUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CreateShopUiState {
    data object Idle : CreateShopUiState
    data object Loading : CreateShopUiState
    data object Success : CreateShopUiState
    data class Error(val message: String) : CreateShopUiState
}

@HiltViewModel
class CreateShopViewModel @Inject constructor(
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val observeProfile: com.swiftshop.domain.profile.ObserveProfileUseCase,
    private val createShop: CreateShopUseCase,
    private val canCreateShop: CanCreateShopUseCase,
    private val repository: CommerceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<CreateShopUiState>(CreateShopUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _canCreate = MutableStateFlow(true)
    val canCreate = _canCreate.asStateFlow()

    private val _usageText = MutableStateFlow("")
    val usageText = _usageText.asStateFlow()

    private val _name = MutableStateFlow("")
    val name = _name.asStateFlow()

    private val _description = MutableStateFlow("")
    val description = _description.asStateFlow()

    private val _category = MutableStateFlow("Retail")
    val category = _category.asStateFlow()

    private val _locationAddress = MutableStateFlow("")
    val locationAddress = _locationAddress.asStateFlow()

    init {
        viewModelScope.launch {
            observeCurrentUser()
                .filterNotNull()
                .flatMapLatest { user ->
                    observeProfile(user.uid).map { profile -> user to profile }
                }
                .collect { (user, profile) ->
                    val allowed = canCreateShop(user.tier, profile.shopCount)
                    _canCreate.value = allowed
                    val entitlement = com.swiftshop.domain.commerce.TierEntitlements.forTier(user.tier)
                    val max = if (entitlement.maxShops == -1) "Unlimited" else entitlement.maxShops.toString()
                    _usageText.value = "Usage: ${profile.shopCount} / $max shops"
                }
        }
    }

    fun onNameChange(v: String) { _name.value = v }
    fun onDescriptionChange(v: String) { _description.value = v }
    fun onCategoryChange(v: String) { _category.value = v }
    fun onLocationAddressChange(v: String) { _locationAddress.value = v }

    fun submit() {
        viewModelScope.launch {
            if (_name.value.isBlank()) {
                _uiState.value = CreateShopUiState.Error("Shop name is required")
                return@launch
            }
            _uiState.value = CreateShopUiState.Loading
            
            val user = observeCurrentUser().first()
            if (user == null) {
                _uiState.value = CreateShopUiState.Error("Not authenticated")
                return@launch
            }

            val newShop = Shop(
                ownerId = user.uid,
                name = _name.value.trim(),
                description = _description.value.trim(),
                category = _category.value,
                locationAddress = _locationAddress.value.trim(),
                isActive = true,
                createdAt = System.currentTimeMillis()
            )

            createShop(newShop).fold(
                onSuccess = { _uiState.value = CreateShopUiState.Success },
                onFailure = { _uiState.value = CreateShopUiState.Error(it.message ?: "Failed to create shop") }
            )
        }
    }
}
