package com.swiftshop.feature.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.model.*
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.CommerceRepository
import com.swiftshop.domain.commerce.CreateShopUseCase
import com.swiftshop.domain.commerce.CanCreateShopUseCase
import com.swiftshop.domain.commerce.ContextEngine
import com.swiftshop.core.media.MediaUploader
import com.swiftshop.core.media.MediaUploadProgress
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
    private val contextEngine: ContextEngine,
    private val repository: com.swiftshop.domain.commerce.CommerceRepository,
    private val mediaUploader: MediaUploader
) : ViewModel() {

    private val _uiState = MutableStateFlow<CreateShopUiState>(CreateShopUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _shopId = repository.generateShopId()

    private val _logoUrl = MutableStateFlow("")
    val logoUrl = _logoUrl.asStateFlow()

    private val _coverUrl = MutableStateFlow("")
    val coverUrl = _coverUrl.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Int?>(null)
    val uploadProgress = _uploadProgress.asStateFlow()

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

    private val _selectedLocation = MutableStateFlow<GeoPoint?>(null)
    val selectedLocation = _selectedLocation.asStateFlow()

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

        viewModelScope.launch {
            // Apply market defaults for initial state
            val market = contextEngine.getActiveMarketContext().first()
            if (_locationAddress.value.isBlank()) {
                _locationAddress.value = "${market.region}, ${market.country}"
            }
        }
    }

    fun onNameChange(v: String) { _name.value = v }
    fun onDescriptionChange(v: String) { _description.value = v }
    fun onCategoryChange(v: String) { _category.value = v }
    fun onLocationAddressChange(v: String) { _locationAddress.value = v }
    fun onLocationSelect(v: GeoPoint) { _selectedLocation.value = v }

    fun onLogoSelected(uri: android.net.Uri) {
        uploadMedia(uri, isLogo = true)
    }

    fun onCoverSelected(uri: android.net.Uri) {
        uploadMedia(uri, isLogo = false)
    }

    private fun uploadMedia(uri: android.net.Uri, isLogo: Boolean) {
        viewModelScope.launch {
            val user = observeCurrentUser().first() ?: return@launch
            val fileName = if (isLogo) "logo.jpg" else "cover.jpg"
            val customPath = "media/${user.uid}/shops/$_shopId/$fileName"
            
            mediaUploader.uploadImage(user.uid, uri, customPath).collect { progress: MediaUploadProgress ->
                when (progress) {
                    is MediaUploadProgress.InProgress -> {
                        _uploadProgress.value = progress.percent
                    }
                    is MediaUploadProgress.Complete -> {
                        if (isLogo) _logoUrl.value = progress.asset.url
                        else _coverUrl.value = progress.asset.url
                        _uploadProgress.value = null
                    }
                    is MediaUploadProgress.Failed -> {
                        _uiState.value = CreateShopUiState.Error("Upload failed: ${progress.message}")
                        _uploadProgress.value = null
                    }
                }
            }
        }
    }

    fun submit() {
        if (_uiState.value is CreateShopUiState.Loading) return // Duplicate submit protection

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
                id = _shopId,
                ownerId = user.uid,
                name = _name.value.trim(),
                description = _description.value.trim(),
                category = _category.value,
                locationAddress = _locationAddress.value.trim(),
                location = _selectedLocation.value ?: GeoPoint(),
                logoUrl = _logoUrl.value,
                coverUrl = _coverUrl.value,
                isActive = true,
                createdAt = System.currentTimeMillis()
            )

            createShop(newShop).fold(
                onSuccess = {
                    // VISIBILITY GATING: Wait until the shop is observable in the user's shops list
                    repository.getUserShops(user.uid)
                        .map { list -> list.any { s -> s.id == _shopId } }
                        .filter { it }
                        .first()

                    _uiState.value = CreateShopUiState.Success



                },
                onFailure = { _uiState.value = CreateShopUiState.Error(it.message ?: "Failed to create shop") }
            )
        }
    }

}
