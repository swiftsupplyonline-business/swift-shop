package com.swiftshop.feature.shop

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swiftshop.core.media.MediaUploadProgress
import com.swiftshop.core.media.MediaUploader
import com.swiftshop.core.model.Listing
import com.swiftshop.core.model.Shop
import com.swiftshop.domain.auth.ObserveCurrentUserUseCase
import com.swiftshop.domain.commerce.DeleteListingUseCase
import com.swiftshop.domain.commerce.GetShopListingsUseCase
import com.swiftshop.domain.commerce.GetShopUseCase
import com.swiftshop.domain.commerce.UpdateShopUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val updateShop: UpdateShopUseCase,
    private val getShopListings: GetShopListingsUseCase,
    private val deleteListing: DeleteListingUseCase,
    private val observeCurrentUser: ObserveCurrentUserUseCase,
    private val mediaUploader: MediaUploader
) : ViewModel() {

    private val shopId: String = checkNotNull(savedStateHandle["shopId"])

    private val _uiState = MutableStateFlow<ManageShopUiState>(ManageShopUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _actionState = MutableStateFlow<ManageActionState>(ManageActionState.Idle)
    val actionState = _actionState.asStateFlow()

    private val _listings = MutableStateFlow<List<Listing>>(emptyList())
    val listings = _listings.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Int?>(null)
    val uploadProgress = _uploadProgress.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _uiState.value = ManageShopUiState.Loading
            getShop(shopId).fold(
                onSuccess = {
                    _uiState.value = ManageShopUiState.Success(it)
                    loadListings()
                },
                onFailure = { _uiState.value = ManageShopUiState.Error(it.message ?: "Failed to load shop") }
            )
        }
    }

    private fun loadListings() {
        viewModelScope.launch {
            getShopListings(shopId, page = 0, pageSize = 50).collect {
                _listings.value = it
            }
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

    fun onLogoSelected(uri: Uri) {
        viewModelScope.launch {
            val uid = observeCurrentUser().first()?.uid ?: return@launch
            mediaUploader.uploadImage(uid, uri).collect { progress ->
                when (progress) {
                    is MediaUploadProgress.InProgress ->
                        _uploadProgress.value = progress.percent
                    is MediaUploadProgress.Complete -> {
                        _uploadProgress.value = null
                        val shop = (uiState.value as? ManageShopUiState.Success)?.shop ?: return@collect
                        val updated = shop.copy(logoUrl = progress.asset.url)
                        updateShop(updated).onSuccess {
                            _uiState.value = ManageShopUiState.Success(updated)
                            _actionState.value = ManageActionState.Success
                        }
                    }
                    is MediaUploadProgress.Failed -> {
                        _uploadProgress.value = null
                        _actionState.value = ManageActionState.Error("Logo upload failed: ${progress.message}")
                    }
                }
            }
        }
    }

    fun onCoverSelected(uri: Uri) {
        viewModelScope.launch {
            val uid = observeCurrentUser().first()?.uid ?: return@launch
            mediaUploader.uploadImage(uid, uri).collect { progress ->
                when (progress) {
                    is MediaUploadProgress.InProgress ->
                        _uploadProgress.value = progress.percent
                    is MediaUploadProgress.Complete -> {
                        _uploadProgress.value = null
                        val shop = (uiState.value as? ManageShopUiState.Success)?.shop ?: return@collect
                        val updated = shop.copy(coverUrl = progress.asset.url)
                        updateShop(updated).onSuccess {
                            _uiState.value = ManageShopUiState.Success(updated)
                            _actionState.value = ManageActionState.Success
                        }
                    }
                    is MediaUploadProgress.Failed -> {
                        _uploadProgress.value = null
                        _actionState.value = ManageActionState.Error("Cover upload failed: ${progress.message}")
                    }
                }
            }
        }
    }

    fun deleteListing(listingId: String) {
        viewModelScope.launch {
            deleteListing.invoke(listingId).onFailure {
                _actionState.value = ManageActionState.Error(it.message ?: "Failed to delete listing")
            }
        }
    }

    fun clearActionState() { _actionState.value = ManageActionState.Idle }
}